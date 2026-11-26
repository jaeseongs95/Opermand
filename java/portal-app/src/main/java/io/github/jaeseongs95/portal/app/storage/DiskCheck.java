package io.github.jaeseongs95.portal.app.storage;

import java.util.Objects;
import java.util.function.Supplier;

import io.github.jaeseongs95.portal.domain.error.ArtifactErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;

/**
 * Result of measuring free disk space outside a database transaction (Principles 4.4). Evaluated inside the reservation transaction only after file-size and quota checks
 * accept the request, so an unreadable volume never overrides a budget or size refusal and throws {@code ARTIFACT_STORAGE_IO_ERROR} with its cause preserved.
 */
public sealed interface DiskCheck permits DiskCheck.Available, DiskCheck.Unreadable {

    /** Returns the usable bytes or throws {@link RequestRefusedException} ({@code ARTIFACT_STORAGE_IO_ERROR}) when measurement failed. */
    long usableBytes();

    /** Measures free disk space by invoking {@code probe} outside any transaction. */
    static DiskCheck measure(Supplier<DiskSpace> probe) {
        Objects.requireNonNull(probe, "probe");
        try {
            return new Available(Objects.requireNonNull(probe.get(), "space"));
        } catch (RuntimeException e) {
            return new Unreadable(e);
        }
    }

    record Available(DiskSpace space) implements DiskCheck {

        public Available {
            Objects.requireNonNull(space, "space");
        }

        @Override
        public long usableBytes() {
            return space.usableBytes();
        }
    }

    record Unreadable(RuntimeException cause) implements DiskCheck {

        public Unreadable {
            Objects.requireNonNull(cause, "cause");
        }

        @Override
        public long usableBytes() {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_STORAGE_IO_ERROR, cause);
        }
    }
}
