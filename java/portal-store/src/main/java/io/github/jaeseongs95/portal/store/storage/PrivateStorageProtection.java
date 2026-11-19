package io.github.jaeseongs95.portal.store.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.portal.app.storage.PrivateStorages;
import io.github.jaeseongs95.portal.app.storage.ProtectionCheck;
import io.github.jaeseongs95.portal.app.storage.StorageArea;
import io.github.jaeseongs95.portal.app.storage.StorageProtection;
import io.github.jaeseongs95.portal.app.storage.UnsafeStorageException;
import io.github.jaeseongs95.portal.store.StorageRoots;

/**
 * {@link StorageProtection} over {@link PrivateStorages}: the artifact folder and the output folder must be folders the stores can use. A folder that does not exist yet is
 * created private to its owner (the stores do the same on first use); a folder that exists is used as it is, whoever else may use it and however it is reached (a link is
 * followed). The answer is {@link ProtectionCheck.Unprotected}, with the cause, when the path is not a folder or when the file store cannot make a new folder private to its
 * owner (it offers neither permission bits nor an access control list): the folder is then not created with wide rights. Nothing is thrown: a guard that fails is an answer.
 */
@Component
public class PrivateStorageProtection implements StorageProtection {

    private final StorageRoots roots;
    private final PrivateStorages storages;

    public PrivateStorageProtection(StorageRoots roots, PrivateStorages storages) {
        this.roots = roots;
        this.storages = storages;
    }

    @Override
    public ProtectionCheck check() {
        for (StorageArea area : StorageArea.values()) {
            try {
                requireUsable(folderOf(area));
            } catch (IOException | RuntimeException e) {
                return new ProtectionCheck.Unprotected(area, e);
            }
        }
        return new ProtectionCheck.Protected();
    }

    private Path folderOf(StorageArea area) {
        return switch (area) {
            case ARTIFACTS -> roots.artifacts();
            case OUTPUTS -> roots.outputs();
        };
    }

    private void requireUsable(Path folder) throws IOException {
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            storages.createFolder(folder);
        }
        storages.requireFolder(folder);
    }
}
