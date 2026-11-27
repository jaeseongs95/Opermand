package io.github.jaeseongs95.opermand.app.attachment;

import java.io.Serial;
import java.util.Objects;

import io.github.jaeseongs95.opermand.domain.attachment.AttachmentId;
import io.github.jaeseongs95.opermand.domain.attachment.AttachmentRole;

/**
 * Raised when an encrypted attachment body cannot be read or verified (missing metadata, missing file, unknown key, tampered ciphertext/AAD, size or SHA-256 mismatch).
 * State-mutating paths fail closed on this exception; read-only history views catch it per row and substitute a masked placeholder (stage 4 design B-15).
 */
public final class AttachmentUnreadableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient AttachmentId id;
    private final AttachmentRole role;
    private final Reason reason;

    public AttachmentUnreadableException(AttachmentId id, AttachmentRole role, Reason reason, String message) {
        super(message);
        this.id = Objects.requireNonNull(id, "id");
        this.role = Objects.requireNonNull(role, "role");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public AttachmentUnreadableException(AttachmentId id, AttachmentRole role, Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.id = Objects.requireNonNull(id, "id");
        this.role = Objects.requireNonNull(role, "role");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public AttachmentId id() {
        return id;
    }

    public AttachmentRole role() {
        return role;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        MISSING_METADATA,
        MISSING_FILE,
        ROLE_MISMATCH,
        CORRUPTED_ENVELOPE,
        UNKNOWN_KEY,
        AUTH_FAILED,
        SIZE_MISMATCH,
        DIGEST_MISMATCH,
        IO_ERROR
    }
}
