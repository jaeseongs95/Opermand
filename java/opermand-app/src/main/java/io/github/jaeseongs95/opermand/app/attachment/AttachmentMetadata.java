package io.github.jaeseongs95.opermand.app.attachment;

import java.time.Instant;
import java.util.Objects;

import io.github.jaeseongs95.opermand.domain.attachment.AttachmentId;
import io.github.jaeseongs95.opermand.domain.attachment.AttachmentRole;
import io.github.jaeseongs95.opermand.domain.id.Digest;

/**
 * Metadata row of one encrypted attachment stored in the {@code attachment} table (no path column, no foreign keys).
 */
public record AttachmentMetadata(AttachmentId id, AttachmentRole role, long sizeBytes, Digest sha256, Instant createdAt) {

    public AttachmentMetadata {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
