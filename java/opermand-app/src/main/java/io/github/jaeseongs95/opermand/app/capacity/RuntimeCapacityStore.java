package io.github.jaeseongs95.opermand.app.capacity;

import java.util.Optional;

/** The runtime capacity override, one row for the installation, guarded by a single optimistic-locking version (SSH_PORTAL-D-0124). */
public interface RuntimeCapacityStore {

    /** {@code Optional} here has the one meaning "nothing was stored yet". */
    Optional<CapacityState> read();

    /** Stores {@code initial} (version 1) when nothing is stored; an existing value is kept and returned (two first writers: one wins, the other reads). */
    CapacityState initialize(CapacityState initial);

    /**
     * Replaces the value when the stored version is {@code expectedVersion} and returns the saved state with the incremented version; another version throws
     * {@code RequestRefusedException(CAPACITY_REVISION_CONFLICT)}.
     */
    CapacityState compareAndSet(long expectedVersion, CapacityState next);
}
