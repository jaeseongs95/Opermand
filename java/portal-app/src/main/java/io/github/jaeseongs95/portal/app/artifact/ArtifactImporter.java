package io.github.jaeseongs95.portal.app.artifact;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.portal.app.remote.PayloadSource;
import io.github.jaeseongs95.portal.app.remote.SinkResult;
import io.github.jaeseongs95.portal.app.storage.DiskCheck;
import io.github.jaeseongs95.portal.app.storage.PayloadRead;
import io.github.jaeseongs95.portal.domain.error.ArtifactErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.domain.id.ArtifactId;
import io.github.jaeseongs95.portal.domain.id.Digest;
import io.github.jaeseongs95.portal.domain.id.Digester;

/**
 * Imports a stream into a new artifact: the staged file that is copied for an action, the spool of an output (2.x {@code importFile}, cases RC-A-182 and RC-A-183). The
 * order is the contract of those cases:
 * <ol>
 * <li>the capacity is reserved <em>before</em> the source is opened, so an import that cannot be stored never touches its source;</li>
 * <li>the bytes are written in chunks and hashed as they pass;</li>
 * <li>at the end the declared size is checked (neither more nor fewer), and the digest is compared with the expected one when the caller knows it;</li>
 * <li>only then is the artifact {@code READY}.</li>
 * </ol>
 * A failure at any step deletes what was written and gives the reservation back before the answer is returned, so nothing stays behind: not a byte, not a row, not a
 * charge. The source is closed in every case.
 */
@Component
public class ArtifactImporter {

    private static final Logger LOG = LoggerFactory.getLogger(ArtifactImporter.class);

    private final ArtifactRegistry registry;
    private final ArtifactPayloadStore payloads;
    private final Digester digester;
    private final Clock clock;
    private final TransactionOperations writeTx;

    @Autowired
    public ArtifactImporter(ArtifactRegistry registry, ArtifactPayloadStore payloads, Digester digester, Clock clock,
                            PlatformTransactionManager transactionManager) {
        this(registry, payloads, digester, clock, new TransactionTemplate(transactionManager));
    }

    public ArtifactImporter(ArtifactRegistry registry, ArtifactPayloadStore payloads, Digester digester, Clock clock) {
        this(registry, payloads, digester, clock, TransactionOperations.withoutTransaction());
    }

    private ArtifactImporter(ArtifactRegistry registry, ArtifactPayloadStore payloads, Digester digester, Clock clock, TransactionOperations writeTx) {
        this.registry = registry;
        this.payloads = payloads;
        this.digester = digester;
        this.clock = clock;
        this.writeTx = writeTx;
    }

    /** Imports {@code source} as an artifact of {@code spec}; the digest is whatever the bytes hash to. */
    public ImportResult importStream(ArtifactSpec spec, PayloadSource source) {
        return run(spec, source, null);
    }

    /** Imports {@code source} and requires the bytes to hash to {@code expected}; another digest fails the import with {@link ImportResult.Reason#DIGEST_MISMATCH}. */
    public ImportResult importStream(ArtifactSpec spec, PayloadSource source, Digest expected) {
        Objects.requireNonNull(expected, "expected");
        return run(spec, source, expected);
    }

    /** {@code expected} is {@code null} only inside this class, where "no digest is expected" is a branch of one method and not a value that travels. */
    private ImportResult run(ArtifactSpec spec, PayloadSource source, Digest expected) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(source, "source");
        DiskCheck disk = DiskCheck.measure(payloads::usable);
        ArtifactReservation reservation = Objects.requireNonNull(writeTx.execute(status -> registry.reserve(spec, clock.instant(), disk)), "reservation");
        if (!(reservation instanceof ArtifactReservation.Reserved reserved)) {
            return new ImportResult.Refused(ArtifactRefusal.of(reservation));
        }
        ArtifactId id = reserved.id();
        try {
            ImportResult result = copy(id, spec, source, expected);
            if (!(result instanceof ImportResult.Imported)) {
                discard(id);
            }
            return result;
        } catch (RuntimeException e) {
            discard(id);
            throw e;
        }
    }

    private ImportResult copy(ArtifactId id, ArtifactSpec spec, PayloadSource source, Digest expected) {
        ArtifactSink sink = ArtifactSink.declared(id, payloads, registry, digester, writeTx);
        PayloadRead read;
        try {
            read = source.open();
        } catch (RuntimeException e) {
            LOG.warn("The source of the import {} could not be opened", id.value(), e);
            return new ImportResult.Failed(ImportResult.Reason.SOURCE_FAILED);
        }
        try (read) {
            ByteBuffer buffer = ByteBuffer.allocate(ChunkDigest.MAX_CHUNK_BYTES);
            while (read.channel().read(buffer) >= 0) {
                buffer.flip();
                byte[] piece = new byte[buffer.remaining()];
                buffer.get(piece);
                buffer.clear();
                if (sink.append(piece) instanceof SinkResult.Failed failed) {
                    return new ImportResult.Failed(reasonOf(failed.cause()));
                }
            }
        } catch (IOException e) {
            LOG.warn("The source of the import {} broke before its end", id.value(), e);
            return new ImportResult.Failed(ImportResult.Reason.SOURCE_FAILED);
        }
        if (sink.writtenBytes() != spec.sizeBytes()) {
            return new ImportResult.Failed(ImportResult.Reason.SIZE_MISMATCH);
        }
        Digest actual = sink.digest();
        if (expected != null && !expected.equals(actual)) {
            return new ImportResult.Failed(ImportResult.Reason.DIGEST_MISMATCH);
        }
        ArtifactRecord ready = Objects.requireNonNull(writeTx.execute(status -> registry.markReady(id, actual)), "ready");
        return new ImportResult.Imported(ready);
    }

    /** More bytes than declared are refused by the registry as {@code ARTIFACT_SEQUENCE_OR_SIZE}: that is a size failure, any other failure of the store is local. */
    private ImportResult.Reason reasonOf(Throwable cause) {
        if (cause instanceof RequestRefusedException refused && refused.code() == ArtifactErrorCode.ARTIFACT_SEQUENCE_OR_SIZE) {
            return ImportResult.Reason.SIZE_MISMATCH;
        }
        return ImportResult.Reason.LOCAL_IO_FAILED;
    }

    /** Removes the bytes and the row, and so the charge. A failure is logged: the row is {@code DELETING} and the start-up check finishes the removal. */
    private void discard(ArtifactId id) {
        try {
            writeTx.executeWithoutResult(status -> registry.beginDelete(id));
            payloads.delete(id);
            writeTx.executeWithoutResult(status -> registry.release(id, ReleaseReason.REFUSED));
        } catch (RuntimeException e) {
            LOG.warn("The imported artifact {} could not be discarded; the start-up check removes it", id.value(), e);
        }
    }
}
