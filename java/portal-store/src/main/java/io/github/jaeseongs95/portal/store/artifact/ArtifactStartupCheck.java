package io.github.jaeseongs95.portal.store.artifact;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.portal.app.artifact.ArtifactPayloadStore;
import io.github.jaeseongs95.portal.app.artifact.ArtifactRecord;
import io.github.jaeseongs95.portal.app.artifact.ArtifactRegistry;
import io.github.jaeseongs95.portal.app.artifact.ChunkDigest;
import io.github.jaeseongs95.portal.app.artifact.ConsumeState;
import io.github.jaeseongs95.portal.app.artifact.QuarantineNotice;
import io.github.jaeseongs95.portal.app.artifact.ReleaseReason;
import io.github.jaeseongs95.portal.app.storage.PayloadRead;
import io.github.jaeseongs95.portal.domain.id.ArtifactId;
import io.github.jaeseongs95.portal.domain.id.Digester;

/**
 * Makes the payload files and the metadata agree after a start (design 8.5, 2.x {@code artifacts.mjs:130-144}). The use case writes the bytes first and
 * forces them to disk and commits the metadata second, so a crash can only leave a file that is <em>longer</em> than the metadata says, never a shorter one:
 * <ol>
 *   <li>a payload file without metadata moves to quarantine, bytes kept;</li>
 *   <li>a payload longer than {@code received_bytes} is cut back to that length;</li>
 *   <li>a payload shorter than {@code received_bytes}, or a chunk whose hash differs, refuses the start with {@link ArtifactStoreCorruptedException};</li>
 *   <li>a row in {@code DELETING} has its bytes deleted and the row released.</li>
 * </ol>
 * A row in {@code CONSUMING} is finished: nothing is in use at a start, the file was moved or removed already or is as good as gone, so its bytes are deleted and the row becomes the
 * tombstone for the reason it recorded (defect D-0012: it used to be only reported and kept its charge for good). A deletion that fails is logged and left to the reap; it never refuses the start. The check does file I/O and several short registry calls and is not called inside a transaction (Principles 4.4); stage 3 calls
 * {@link #reconcile()} once at start-up. A refusal in the middle leaves the repairs already made in place; they are idempotent, so a second run continues.
 */
@Component
public class ArtifactStartupCheck {

    private static final Logger LOG = LoggerFactory.getLogger(ArtifactStartupCheck.class);

    private final ArtifactRegistry registry;
    private final ArtifactPayloadStore payloads;
    private final Digester digester;
    private final QuarantineNotice quarantine;

    public ArtifactStartupCheck(ArtifactRegistry registry, ArtifactPayloadStore payloads, Digester digester, QuarantineNotice quarantine) {
        this.registry = registry;
        this.payloads = payloads;
        this.digester = digester;
        this.quarantine = quarantine;
    }

    public ArtifactReconciliation reconcile() {
        Set<ArtifactId> stored = payloads.stored();
        Map<ArtifactId, ArtifactRecord> rows = new HashMap<>();
        for (ArtifactRecord record : registry.holding()) {
            rows.put(record.id(), record);
        }
        List<ArtifactId> quarantined = new ArrayList<>();
        List<ArtifactId> truncated = new ArrayList<>();
        List<ArtifactId> deleted = new ArrayList<>();
        List<ArtifactId> finishedConsumptions = new ArrayList<>();

        for (ArtifactId id : stored) {
            if (!rows.containsKey(id)) {
                payloads.quarantine(id);
                quarantined.add(id);
                LOG.warn("Payload {} has no metadata and was moved to quarantine", id.value());
            }
        }
        for (ArtifactRecord record : rows.values()) {
            switch (record.state()) {
                case DELETING -> {
                    if (deleteBytes(record)) {
                        registry.release(record.id(), ReleaseReason.ABANDONED);
                        deleted.add(record.id());
                    }
                }
                case CONSUMING -> {
                    // Nothing is in use at a start, so a use that began and did not finish is finished now, for the reason it recorded (defect D-0012).
                    if (record.consumption() instanceof ConsumeState.Consuming consuming && deleteBytes(record)) {
                        registry.completeConsume(record.id(), consuming.reason());
                        finishedConsumptions.add(record.id());
                    }
                }
                case STAGING, READY -> checkPayload(record, stored, truncated);
                case CONSUMED -> LOG.debug("A consumed artifact holds no bytes: {}", record.id().value());
            }
        }
        LOG.info("Artifact start-up check: {} quarantined, {} truncated, {} deletions finished, {} consumptions finished", quarantined.size(), truncated.size(),
            deleted.size(), finishedConsumptions.size());
        quarantine.log();
        return new ArtifactReconciliation(quarantined, truncated, deleted, finishedConsumptions);
    }

    /**
     * Deletes the bytes of a row that is being removed. A file that cannot be deleted (locked, a failing disk) must not refuse the start of the whole application: the failure is logged
     * with its cause, the row stays as it is and the reap tries it again on every run (defect D-0012, S-D0012-19). Returns whether the bytes are gone.
     */
    private boolean deleteBytes(ArtifactRecord record) {
        try {
            payloads.delete(record.id());
            return true;
        } catch (RuntimeException e) {
            LOG.warn("The bytes of the artifact " + record.id().value() + " (" + record.state() + ") could not be deleted at the start; the row stays and the reap tries again", e);
            return false;
        }
    }

    private void checkPayload(ArtifactRecord record, Set<ArtifactId> stored, List<ArtifactId> truncated) {
        long expected = record.receivedBytes();
        long actual = stored.contains(record.id()) ? payloads.length(record.id()) : 0L;
        if (actual < expected) {
            throw new ArtifactStoreCorruptedException(record.id(), ArtifactStoreCorruptedException.Reason.PAYLOAD_SHORTER);
        }
        if (actual > expected) {
            payloads.truncate(record.id(), expected);
            truncated.add(record.id());
            LOG.warn("Payload {} was cut back from {} to {} bytes: the tail was never acknowledged", record.id().value(), actual, expected);
        }
        if (expected > 0) {
            verifyChunks(record);
        }
    }

    private void verifyChunks(ArtifactRecord record) {
        List<ChunkDigest> chunks = registry.chunks(record.id());
        try (PayloadRead read = payloads.open(record.id())) {
            for (ChunkDigest chunk : chunks) {
                byte[] bytes = new byte[chunk.sizeBytes()];
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    if (read.channel().read(buffer) < 0) {
                        throw new ArtifactStoreCorruptedException(record.id(), ArtifactStoreCorruptedException.Reason.PAYLOAD_SHORTER);
                    }
                }
                if (!digester.digest(bytes).equals(chunk.sha256())) {
                    throw new ArtifactStoreCorruptedException(record.id(), ArtifactStoreCorruptedException.Reason.CHUNK_MISMATCH);
                }
            }
        } catch (IOException e) {
            throw new ArtifactStoreCorruptedException(record.id(), ArtifactStoreCorruptedException.Reason.PAYLOAD_UNREADABLE, e);
        }
    }
}
