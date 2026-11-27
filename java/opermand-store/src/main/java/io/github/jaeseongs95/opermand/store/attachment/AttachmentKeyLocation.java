package io.github.jaeseongs95.opermand.store.attachment;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Location of the attachment encryption keyring file. Defaults to {@code <keyring>/attachment.keys} under {@code StorageRoots}, or an explicit path configured by
 * {@code portal.storage.key-file} in {@code opermand-server}.
 */
public record AttachmentKeyLocation(Path file) {

    public AttachmentKeyLocation {
        Objects.requireNonNull(file, "file");
    }
}
