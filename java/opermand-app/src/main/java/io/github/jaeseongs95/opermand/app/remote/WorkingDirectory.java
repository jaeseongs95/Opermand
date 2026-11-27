package io.github.jaeseongs95.opermand.app.remote;

import java.util.Objects;

import io.github.jaeseongs95.opermand.domain.path.RemotePath;
import io.github.jaeseongs95.opermand.domain.target.WindowsDirectory;

/** Where a remote command starts: in the directory the login gives it, in a directory path, or at a Windows drive root. It stands where a nullable path would. */
public sealed interface WorkingDirectory permits WorkingDirectory.Default, WorkingDirectory.At, WorkingDirectory.DriveRoot {

    /** The directory the login starts in. */
    record Default() implements WorkingDirectory {
    }

    /** A directory on the target, written in the target's own path syntax. */
    record At(RemotePath directory) implements WorkingDirectory {

        public At {
            Objects.requireNonNull(directory, "directory");
        }
    }

    /** A Windows drive root directory such as {@code C:\} or {@code C:/}, which is a valid working directory but not a regular-file {@link RemotePath}. */
    record DriveRoot(WindowsDirectory directory) implements WorkingDirectory {

        public DriveRoot {
            Objects.requireNonNull(directory, "directory");
            if (!directory.isDriveRoot()) {
                throw new IllegalArgumentException("Not a Windows drive root: " + directory.value());
            }
        }
    }
}
