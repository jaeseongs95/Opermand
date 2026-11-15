package io.github.jaeseongs95.portal.store.storage;

import java.io.Serial;

/**
 * A folder or a file that holds artifact bytes, output bytes or the audit lines cannot be used for that: what is at the path is not a regular file or not a folder, or the
 * file store cannot make a new path private to its owner (it offers neither permission bits nor an access control list, or the owner-only rights could not be set). Who
 * may use an existing path, who owns it and whether it is a link are not reasons for it (decision D-0117). It is the one signal of the {@link PrivateStorage} rules; each
 * store turns it into the refusal of its own contract ({@code ARTIFACT_STORAGE_UNSAFE} for the artifacts, the output code for the outputs). The text names the path and
 * the reason, never the content.
 */
public final class UnsafeStorageException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String path;

    public UnsafeStorageException(String path, String reason) {
        super("The storage " + path + " cannot be used: " + reason);
        this.path = path;
    }

    public UnsafeStorageException(String path, String reason, Throwable cause) {
        super("The storage " + path + " cannot be used: " + reason, cause);
        this.path = path;
    }

    /** The path as text. */
    public String path() {
        return path;
    }
}
