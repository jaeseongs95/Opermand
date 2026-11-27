package io.github.jaeseongs95.opermand.app.output;

/**
 * Bytes retained on disk by an abandoned output, measured outside any database transaction before {@link OutputManifestStore#markUnavailable} updates the manifest row
 * (Principles 4.4). {@link Unmeasured} indicates the files could not be measured safely (link, owner, mode), so the store retains a conservative charge.
 */
public sealed interface RetainedBytes permits RetainedBytes.Measured, RetainedBytes.Unmeasured {

    record Measured(long bytes) implements RetainedBytes {
        public Measured {
            if (bytes < 0) {
                throw new IllegalArgumentException("bytes must not be negative: " + bytes);
            }
        }
    }

    record Unmeasured() implements RetainedBytes {
    }
}
