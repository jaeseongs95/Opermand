package io.github.jaeseongs95.opermand.ssh.profile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.opermand.app.storage.PrivateStorage;
import io.github.jaeseongs95.opermand.app.storage.PrivateStorages;
import io.github.jaeseongs95.opermand.app.storage.UnsafeStorageException;

/**
 * SSH adapter facade over the shared {@link PrivateStorages} in {@code opermand-app} (SSH_PORTAL-D-0128). Creates missing parent folders and an empty owner-only file,
 * turning {@link UnsafeStorageException} into {@link IOException} for SSH host-key/profile callers. Existing paths are left as they are (decision D-0117).
 */
@Component
public class PrivateFiles {

    private final PrivateStorages storages;

    @Autowired
    public PrivateFiles(PrivateStorages storages) {
        this.storages = Objects.requireNonNull(storages, "storages");
    }

    public PrivateFiles(List<PrivateStorage> rules) {
        this(new PrivateStorages(rules));
    }

    /** Creates the folder of the file, if it is missing, and the empty file, if it is missing; what is created is private to its owner. What exists is left as it is. */
    public void createPrivateFile(Path file) throws IOException {
        try {
            storages.createPrivateFile(file);
        } catch (UnsafeStorageException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}
