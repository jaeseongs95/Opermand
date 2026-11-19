package io.github.jaeseongs95.portal.store.maintenance;

import org.springframework.data.repository.CrudRepository;

/** Spring Data JDBC repository of the single-row {@code maintenance_reservation} table. */
interface MaintenanceReservationRowRepository extends CrudRepository<MaintenanceReservationRow, Integer> {
}
