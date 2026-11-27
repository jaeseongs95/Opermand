package io.github.jaeseongs95.opermand.app.output;

import java.time.Instant;
import java.util.List;

import io.github.jaeseongs95.opermand.app.storage.DiskSpace;

/**
 * The metadata and the budget of the command outputs (2.x {@code manifest.sqlite}, table {@code outputs}). The payload bytes are {@link OutputPayloadStore}'s and are
 * measured/written outside the transaction (Principles 4.4); transaction boundaries belong to the calling service.
 */
public interface OutputManifestStore {

    OutputLookup find(OutputId id, Instant now);

    /** Reserves {@code expectedBytes} of the budget and creates a {@code PENDING} row using pre-measured {@code disk} space (measured outside the transaction). */
    OutputReservation reserve(OutputBinding binding, long expectedBytes, Instant at, DiskSpace disk);

    /** Records the stream state of a pending output (open, then complete). */
    OutputManifest recordStream(OutputId id, StreamInfo stream);

    /** {@code PENDING} to {@code READY}: the renderings are known; the charge becomes the real size. */
    OutputManifest markReady(OutputId id, OutputFormats formats, long sizeBytes);

    /** {@code PENDING} to {@code UNAVAILABLE} using pre-measured {@code retained} bytes (measured outside the transaction); the row is kept until {@code expiresAt}. */
    void markUnavailable(OutputId id, Instant expiresAt, RetainedBytes retained);

    OutputBudget budget();

    /** Outputs whose expiry is not after {@code now}. */
    List<OutputManifest> expired(Instant now);

    /** Every output in one state; the start-up check reads the pending and the unavailable ones. */
    List<OutputManifest> inState(OutputState state);

    void delete(OutputId id);

    /** Reconciles the {@code storage_budget} quota row for outputs against live rows (called during startup recovery). */
    default void reconcileBudget() {
    }
}
