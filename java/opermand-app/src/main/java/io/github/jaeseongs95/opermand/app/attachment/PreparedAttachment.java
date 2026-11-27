package io.github.jaeseongs95.opermand.app.attachment;

import java.util.Objects;

/**
 * An attachment whose encrypted file has already been written and {@code fsync}ed to disk outside any database transaction, ready for its metadata row to be inserted
 * inside the caller's short write transaction.
 */
public record PreparedAttachment(AttachmentMetadata metadata) {

    public PreparedAttachment {
        Objects.requireNonNull(metadata, "metadata");
    }
}
