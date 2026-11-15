package io.github.jaeseongs95.portal.store.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@link PrivateStorage} for a file store with the {@code posix} view: a folder that this rule creates is {@code rwx------} (its missing parents too, because the
 * attribute is given to every folder that {@link Files#createDirectories} makes), a file {@code rw-------} (2.x {@code privateDirectory}/{@code privateFile},
 * {@code artifacts.mjs:11-14}). The process umask can only narrow these modes. What exists is not changed and not judged.
 *
 * <p>Based on the Java SE API: {@code java.nio.file.attribute.PosixFilePermissions}
 * (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/attribute/PosixFilePermissions.html).
 */
@Component
@Order(1)
public final class PosixPrivateStorage implements PrivateStorage {

    private static final String POSIX_VIEW = "posix";

    @Override
    public boolean supports(FileStore store) {
        return store.supportsFileAttributeView(POSIX_VIEW);
    }

    @Override
    public void createFolder(Path folder) throws IOException {
        Files.createDirectories(folder, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    @Override
    public FileChannel open(Path file, Set<? extends OpenOption> options) throws IOException {
        return FileChannel.open(file, options, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }
}
