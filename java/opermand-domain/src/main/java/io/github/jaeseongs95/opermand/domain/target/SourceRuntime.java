package io.github.jaeseongs95.opermand.domain.target;

import java.util.Objects;

/**
 * The configured Python runtime that executes a {@code SOURCE} operation on a target (stage 4 design B-3, defects SSH_PORTAL-D-0170 and D-0172).
 * Carrying this sealed type through {@code TargetPlatform.prepareSource} removes the Windows stub refusal while preserving platform separation without OS branches.
 */
public sealed interface SourceRuntime permits SourceRuntime.Posix, SourceRuntime.WindowsPython {

    /** A POSIX target's Python interpreter (configured explicitly or {@link PosixPythonExecutable#DEFAULT}). */
    record Posix(PosixPythonExecutable python) implements SourceRuntime {

        public Posix {
            Objects.requireNonNull(python, "python");
        }
    }

    /** A Windows target's configured Python executable. */
    record WindowsPython(WindowsPythonExecutable python) implements SourceRuntime {

        public WindowsPython {
            Objects.requireNonNull(python, "python");
        }
    }
}
