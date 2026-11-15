package io.github.jaeseongs95.portal.store.audit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

import io.github.jaeseongs95.portal.app.audit.AuditFailedException;
import io.github.jaeseongs95.portal.store.storage.PrivateStorages;
import io.github.jaeseongs95.portal.store.storage.UnsafeStorageException;

/**
 * The audit file on a {@link FileChannel} opened with CREATE, APPEND and WRITE: the file is created when absent and every write goes to the end. The lines name the
 * operations and the approvals, so the file is private to its owner like the payloads are: it is created so (the rule is {@link PrivateStorages}, the same one the artifact
 * and the output stores use). An existing file is used as it is, whoever else may use it; one that is not a regular file is refused (decision D-0117).
 */
final class FileChannelAuditFile implements AuditFile {

    private final FileChannel channel;

    /**
     * Opens (and creates) the file; the parent folders are created too, private to the owner when they are new. A failure is raised, the application cannot start
     * without its audit file: an I/O failure as {@link UncheckedIOException}, a path that is not a regular file, or a file store that cannot make the file private to its
     * owner, as {@link AuditFailedException}.
     */
    FileChannelAuditFile(Path file, PrivateStorages storages) {
        try {
            Path absolute = file.toAbsolutePath();
            Path parent = absolute.getParent();
            if (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                storages.createFolder(parent);
            }
            if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
                storages.requireRegularFile(absolute);
            }
            this.channel = storages.open(absolute, Set.of(StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE));
        } catch (UnsafeStorageException e) {
            throw new AuditFailedException("The audit file cannot be used: " + file, e);
        } catch (IOException e) {
            throw new UncheckedIOException("The audit file cannot be opened: " + file, e);
        }
    }

    @Override
    public FileChannel channel() {
        return channel;
    }

    @Override
    public void force() throws IOException {
        channel.force(true);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
