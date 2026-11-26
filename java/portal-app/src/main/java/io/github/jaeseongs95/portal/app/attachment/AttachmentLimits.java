package io.github.jaeseongs95.portal.app.attachment;

/**
 * Size limits for encrypted operation-body attachments (stage 4 design B-15, S4-AT-06).
 *
 * @param maxBytes maximum plaintext size in UTF-8 bytes accepted for a single attachment body
 */
public record AttachmentLimits(long maxBytes) {

    /** 1 MiB ceiling per attachment body: matches the V1 {@code attachment.size_bytes} CHECK constraint. */
    public static final long DEFAULT_MAX_BYTES = 1_048_576L;

    public AttachmentLimits {
        if (maxBytes <= 0 || maxBytes > DEFAULT_MAX_BYTES) {
            throw new IllegalArgumentException("maxBytes must be between 1 and " + DEFAULT_MAX_BYTES);
        }
    }

    public static AttachmentLimits defaults() {
        return new AttachmentLimits(DEFAULT_MAX_BYTES);
    }
}
