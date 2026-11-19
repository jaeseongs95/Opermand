package io.github.jaeseongs95.portal.store.maintenance;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** Table {@code maintenance_reservation}: the single active reservation row (id 1) with its reservation timestamp (SSH_PORTAL-D-0124). */
@Table("maintenance_reservation")
public record MaintenanceReservationRow(@Id int id, Instant reservedAt) {
}
