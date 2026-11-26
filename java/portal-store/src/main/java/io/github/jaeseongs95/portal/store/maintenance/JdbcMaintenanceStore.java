package io.github.jaeseongs95.portal.store.maintenance;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.stereotype.Repository;

import io.github.jaeseongs95.portal.app.maintenance.MaintenanceStore;
import io.github.jaeseongs95.portal.domain.error.RequestErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;

/**
 * {@link MaintenanceStore} over the single-row table {@code maintenance_reservation} (SSH_PORTAL-D-0124).
 * The row id is always 1: the primary key and CHECK constraint guarantee at most one active reservation.
 */
@Repository
public class JdbcMaintenanceStore implements MaintenanceStore {

    private static final int THE_ROW = 1;

    private final MaintenanceReservationRowRepository reservations;
    private final JdbcAggregateOperations aggregates;

    public JdbcMaintenanceStore(MaintenanceReservationRowRepository reservations, JdbcAggregateOperations aggregates) {
        this.reservations = reservations;
        this.aggregates = aggregates;
    }

    @Override
    public boolean occupied() {
        return reservations.existsById(THE_ROW);
    }

    @Override
    public Optional<Instant> reservedAt() {
        return reservations.findById(THE_ROW).map(MaintenanceReservationRow::reservedAt);
    }

    @Override
    public void reserve(Instant reservedAt) {
        Objects.requireNonNull(reservedAt, "reservedAt");
        try {
            aggregates.insert(new MaintenanceReservationRow(THE_ROW, reservedAt));
        } catch (DuplicateKeyException e) {
            throw new RequestRefusedException(RequestErrorCode.MAINTENANCE_ACTIVE, e);
        }
    }

    @Override
    public void release() {
        if (!reservations.existsById(THE_ROW)) {
            throw new RequestRefusedException(RequestErrorCode.REQUEST_REFUSED);
        }
        reservations.deleteById(THE_ROW);
    }
}
