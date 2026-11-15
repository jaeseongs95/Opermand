package io.github.jaeseongs95.portal.ssh.profile;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@link PrivateFileRule} for a file store with the {@code posix} view: a new folder is {@code rwx------} (its missing parents too, because the attribute is given to every
 * folder that {@link Files#createDirectories} makes) and a new file {@code rw-------}. The process umask can only narrow these modes. What exists is not changed and not
 * judged: 2.x refused a file or a folder that the group or others could use with the mask {@code 0o077} (PF-04, vendor config/loader.js:67-74), and 3.0 does not (D-0117).
 *
 * <p>Based on the Java SE API: {@code PosixFilePermissions}
 * (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/attribute/PosixFilePermissions.html).
 */
@Component
@Order(1)
public final class PosixPrivateFileRule implements PrivateFileRule {

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
    public void createFile(Path file) throws IOException {
        if (!Files.exists(file)) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
    }
}
