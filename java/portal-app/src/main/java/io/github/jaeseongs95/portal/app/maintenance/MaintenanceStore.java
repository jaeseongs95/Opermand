package io.github.jaeseongs95.portal.app.maintenance;

import java.time.Instant;

/**
 * The maintenance reservation, one row for the single-owner installation (SSH_PORTAL-D-0124). It is stored with its reservation timestamp so it survives a restart.
 */
public interface MaintenanceStore {

    /** True while a maintenance reservation is active. */
    boolean occupied();

    /**
     * Takes the maintenance reservation at {@code reservedAt}. If a reservation is already active, throws {@code RequestRefusedException(MAINTENANCE_ACTIVE)}.
     */
    void reserve(Instant reservedAt);

    /** Releases the active maintenance reservation. If no reservation is active, throws {@code RequestRefusedException(REQUEST_REFUSED)}. */
    void release();
}
