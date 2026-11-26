package io.github.jaeseongs95.portal.store.attachment;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.stereotype.Repository;

import io.github.jaeseongs95.portal.app.attachment.AttachmentLimits;
import io.github.jaeseongs95.portal.app.attachment.AttachmentMetadata;
import io.github.jaeseongs95.portal.app.attachment.AttachmentStore;
import io.github.jaeseongs95.portal.app.attachment.AttachmentUnreadableException;
import io.github.jaeseongs95.portal.app.attachment.PreparedAttachment;
import io.github.jaeseongs95.portal.domain.attachment.AttachmentId;
import io.github.jaeseongs95.portal.domain.attachment.AttachmentIdGenerator;
import io.github.jaeseongs95.portal.domain.attachment.AttachmentRole;
import io.github.jaeseongs95.portal.domain.id.Digest;
import io.github.jaeseongs95.portal.domain.id.Digester;

/**
 * {@link AttachmentStore} over the {@code attachment} metadata table and encrypted {@code PAT1} files. Declares no transactions (Principles 4.4): file and crypto
 * methods touch no database, and metadata methods join the caller's transaction.
 */
@Repository
public class JdbcAttachmentStore implements AttachmentStore {

    private final AttachmentRowRepository rows;
    private final JdbcAggregateOperations aggregates;
    private final AttachmentFiles files;
    private final Pat1Envelope envelope;
    private final AttachmentIdGenerator ids;
    private final Digester digester;
    private final Clock clock;
    private final AttachmentLimits limits;

    @Autowired
    public JdbcAttachmentStore(AttachmentRowRepository rows, JdbcAggregateOperations aggregates, AttachmentFiles files, Pat1Envelope envelope,
                               AttachmentIdGenerator ids, Digester digester, Clock clock, ObjectProvider<AttachmentLimits> limits) {
        this(rows, aggregates, files, envelope, ids, digester, clock, limits.getIfAvailable(AttachmentLimits::defaults));
    }

    public JdbcAttachmentStore(AttachmentRowRepository rows, JdbcAggregateOperations aggregates, AttachmentFiles files, Pat1Envelope envelope,
                               AttachmentIdGenerator ids, Digester digester, Clock clock, AttachmentLimits limits) {
        this.rows = Objects.requireNonNull(rows, "rows");
        this.aggregates = Objects.requireNonNull(aggregates, "aggregates");
        this.files = Objects.requireNonNull(files, "files");
        this.envelope = Objects.requireNonNull(envelope, "envelope");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.digester = Objects.requireNonNull(digester, "digester");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    @Override
    public PreparedAttachment prepare(AttachmentRole role, byte[] plaintext) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(plaintext, "plaintext");
        if (plaintext.length > limits.maxBytes()) {
            throw new IllegalArgumentException("Attachment body for " + role + " exceeds max size " + limits.maxBytes() + " bytes (" + plaintext.length + ")");
        }
        AttachmentId id = ids.next();
        Digest sha256 = digester.digest(plaintext);
        byte[] ciphertext = envelope.encrypt(id, role, plaintext);
        files.write(id, ciphertext);
        return new PreparedAttachment(new AttachmentMetadata(id, role, plaintext.length, sha256, clock.instant()));
    }

    @Override
    public void recordMetadata(AttachmentMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        aggregates.insert(new AttachmentRow(metadata.id().value(), metadata.role(), metadata.sizeBytes(), metadata.sha256().hex(), metadata.createdAt()));
    }

    @Override
    public void discardPrepared(AttachmentId id) {
        files.deleteIfExists(id);
    }

    @Override
    public Optional<AttachmentMetadata> findMetadata(AttachmentId id) {
        Objects.requireNonNull(id, "id");
        return rows.findById(id.value()).map(JdbcAttachmentStore::toMetadata);
    }

    @Override
    public Map<AttachmentId, AttachmentMetadata> findMetadata(Collection<AttachmentId> ids) {
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<String> keys = ids.stream().map(AttachmentId::value).distinct().toList();
        Map<AttachmentId, AttachmentMetadata> found = new HashMap<>();
        for (AttachmentRow row : rows.findAllById(keys)) {
            AttachmentMetadata metadata = toMetadata(row);
            found.put(metadata.id(), metadata);
        }
        return Map.copyOf(found);
    }

    @Override
    public byte[] decrypt(AttachmentMetadata metadata, AttachmentRole expectedRole) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(expectedRole, "expectedRole");
        AttachmentId id = metadata.id();
        if (metadata.role() != expectedRole) {
            throw new AttachmentUnreadableException(id, expectedRole, AttachmentUnreadableException.Reason.ROLE_MISMATCH,
                "Attachment " + id.value() + " has role " + metadata.role() + ", expected " + expectedRole);
        }
        byte[] ciphertext = files.read(id, expectedRole);
        byte[] plaintext = envelope.decrypt(id, expectedRole, ciphertext);
        if (plaintext.length != metadata.sizeBytes()) {
            throw new AttachmentUnreadableException(id, expectedRole, AttachmentUnreadableException.Reason.SIZE_MISMATCH,
                "Attachment " + id.value() + " decrypted size " + plaintext.length + " does not match metadata " + metadata.sizeBytes());
        }
        Digest actualDigest = digester.digest(plaintext);
        if (!actualDigest.equals(metadata.sha256())) {
            throw new AttachmentUnreadableException(id, expectedRole, AttachmentUnreadableException.Reason.DIGEST_MISMATCH,
                "Attachment " + id.value() + " plaintext SHA-256 does not match metadata");
        }
        return plaintext;
    }

    @Override
    public Set<AttachmentId> storedFilesOlderThan(Instant cutoff) {
        return files.storedFilesOlderThan(cutoff);
    }

    @Override
    public Set<AttachmentId> existingMetadataIds(Collection<AttachmentId> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty()) {
            return Set.of();
        }
        List<String> keys = candidates.stream().map(AttachmentId::value).distinct().toList();
        Set<AttachmentId> existing = new HashSet<>();
        for (AttachmentRow row : rows.findAllById(keys)) {
            existing.add(new AttachmentId(row.id()));
        }
        return Set.copyOf(existing);
    }

    @Override
    public void quarantine(AttachmentId id) {
        files.quarantine(id);
    }

    private static AttachmentMetadata toMetadata(AttachmentRow row) {
        return new AttachmentMetadata(new AttachmentId(row.id()), row.role(), row.sizeBytes(), new Digest(row.sha256()), row.createdAt());
    }
}
