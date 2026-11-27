package io.github.jaeseongs95.opermand.store;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The folders of the file stores: where artifact bytes, command output bytes, encrypted operation-body attachments, and the attachment keyring live. The product's
 * assembly (opermand-server) creates one bean from its {@code @ConfigurationProperties}; this module has no settings of its own (the property prefix belongs to the server).
 */
public record StorageRoots(Path artifacts, Path outputs, Path attachments, Path keyring) {

    public StorageRoots {
        Objects.requireNonNull(artifacts, "artifacts");
        Objects.requireNonNull(outputs, "outputs");
        Objects.requireNonNull(attachments, "attachments");
        Objects.requireNonNull(keyring, "keyring");
    }

    /**
     * Convenience constructor deriving {@code attachments} and {@code keyring} next to {@code artifacts} (as siblings when {@code artifacts} is named {@code artifacts},
     * otherwise as children of {@code artifacts} so temporary-directory tests stay contained).
     */
    public StorageRoots(Path artifacts, Path outputs) {
        this(artifacts, outputs, deriveFolder(artifacts, "attachments"), deriveFolder(artifacts, "keyring"));
    }

    private static Path deriveFolder(Path artifacts, String name) {
        Objects.requireNonNull(artifacts, "artifacts");
        Path fileName = artifacts.getFileName();
        Path parent = artifacts.toAbsolutePath().getParent();
        if (fileName != null && "artifacts".equals(fileName.toString()) && parent != null) {
            return parent.resolve(name);
        }
        return artifacts.resolve(name);
    }
}
