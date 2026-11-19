package io.github.jaeseongs95.portal.store.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import io.github.jaeseongs95.portal.domain.error.RequestErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.store.Savepoints;
import io.github.jaeseongs95.portal.store.StoreTest;

@StoreTest
@Import(JdbcMaintenanceStore.class)
class JdbcMaintenanceStoreTest {

    private static final Instant T1 = Instant.parse("2026-11-19T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-19T00:05:00Z");

    private final JdbcMaintenanceStore store;
    private final Savepoints savepoints;

    JdbcMaintenanceStoreTest(JdbcMaintenanceStore store, Savepoints savepoints) {
        this.store = store;
        this.savepoints = savepoints;
    }

    private void refusedReservation(Instant at) {
        assertThatThrownBy(() -> savepoints.run(() -> store.reserve(at))).isInstanceOfSatisfying(RequestRefusedException.class,
            e -> assertThat(e.code()).isEqualTo(RequestErrorCode.MAINTENANCE_ACTIVE));
    }

    @Test
    @DisplayName("TC-D0124-01: a single reservation excludes duplicates with MAINTENANCE_ACTIVE, and release frees it for reuse")
    void reservationExcludesDuplicatesAndReleasesCleanly() {
        assertThat(store.occupied()).isFalse();
        assertThat(store.reservedAt()).isEmpty();
        assertThatThrownBy(() -> savepoints.run(() -> store.release())).isInstanceOfSatisfying(RequestRefusedException.class,
            e -> assertThat(e.code()).isEqualTo(RequestErrorCode.REQUEST_REFUSED));

        store.reserve(T1);
        assertThat(store.occupied()).isTrue();
        assertThat(store.reservedAt()).contains(T1);
        refusedReservation(T2);

        store.release();
        assertThat(store.occupied()).isFalse();
        assertThat(store.reservedAt()).isEmpty();

        store.reserve(T2);
        assertThat(store.occupied()).isTrue();
        assertThat(store.reservedAt()).contains(T2);
    }

    @Test
    @DisplayName("TC-D0124-02 / S2-DB-17: a refused reservation leaves the same transaction usable: it reads, releases and reserves again")
    void aRefusedReservationLeavesTheTransactionUsable() {
        store.reserve(T1);
        refusedReservation(T2);
        refusedReservation(T2);

        assertThat(store.occupied()).isTrue();
        store.release();
        assertThat(store.occupied()).isFalse();
        store.reserve(T2);
        assertThat(store.occupied()).isTrue();
    }
}
