package io.github.jaeseongs95.portal.store.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.portal.app.capacity.CapacityState;
import io.github.jaeseongs95.portal.domain.capacity.Capacity;
import io.github.jaeseongs95.portal.domain.error.CapacityErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.domain.id.TargetId;
import io.github.jaeseongs95.portal.domain.target.CapacityOverride;
import io.github.jaeseongs95.portal.store.StoreTest;
import io.github.jaeseongs95.portal.store.TestSavepoints;

/**
 * TC-D0124-03/04 & RC-B-405..409: single optimistic lock via Spring Data JDBC {@code @Version} on {@code runtime_capacity}.
 */
@StoreTest
@Import({JdbcRuntimeCapacityStore.class, CapacityMapper.class})
class JdbcRuntimeCapacityStoreTest {

    private static final long WAIT_SECONDS = 30;

    private final JdbcRuntimeCapacityStore store;
    private final JdbcTemplate jdbc;
    private final TestSavepoints savepoints;
    private final TransactionTemplate tx;

    JdbcRuntimeCapacityStoreTest(JdbcRuntimeCapacityStore store, JdbcTemplate jdbc, TestSavepoints savepoints, PlatformTransactionManager transactionManager) {
        this.store = store;
        this.jdbc = jdbc;
        this.savepoints = savepoints;
        this.tx = new TransactionTemplate(transactionManager);
    }

    private void refused(String sql, Object... arguments) {
        assertThatThrownBy(() -> savepoints.run(() -> jdbc.update(sql, arguments))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("TC-D0124-03 / RC-B-405: nothing is stored on a fresh installation; the first initialize stores version 1 and a later initialize returns the stored state")
    void theStoredStateWinsOverALaterInitialize() {
        assertThat(store.read()).isEmpty();
        CapacityState first = state(1L, 40, 4, explicit("alpha", 7, null));

        assertThat(store.initialize(first)).isEqualTo(first);
        assertThat(store.read()).contains(first);
        CapacityState other = state(1L, 20, 2, new CapacityState.TargetCapacity(new TargetId("windows"), new CapacityOverride.Inherited(),
            new CapacityOverride.Explicit(new Capacity(1))));

        assertThat(store.initialize(other)).isEqualTo(first);
        assertThat(store.read()).contains(first);
    }

    @Test
    @DisplayName("TC-D0124-03 / RC-B-405: a reduction advances @Version by 1 and a stale version changes nothing")
    void aReductionIsStoredAndAStaleVersionChangesNothing() {
        CapacityState first = state(1L, 20, 2, explicit("alpha", 7, null), explicit("windows", null, 1));
        store.initialize(first);
        CapacityState reducedProposal = state(1L, 1, 1, explicit("alpha", null, null), explicit("windows", null, 1));

        CapacityState saved = store.compareAndSet(1L, reducedProposal);

        assertThat(saved.version()).isEqualTo(2L);
        assertThat(store.read()).contains(saved);
        CapacityState stale = state(1L, 3, 1, explicit("windows", null, null));
        assertThatThrownBy(() -> store.compareAndSet(1L, stale)).isInstanceOfSatisfying(RequestRefusedException.class,
            e -> assertThat(e.code()).isEqualTo(CapacityErrorCode.CAPACITY_REVISION_CONFLICT));
        assertThat(store.read()).contains(saved);
    }

    @Test
    @DisplayName("TC-D0124-04 / RC-B-406: of two writers from the same @Version, the second is refused with CAPACITY_REVISION_CONFLICT and its state is not stored")
    void theSecondWriterOfTheSameVersionIsRefused() {
        CapacityState first = state(1L, 10, 2);
        store.initialize(first);
        CapacityState winner = store.compareAndSet(1L, state(1L, 5, 2));
        CapacityState loser = state(1L, 6, 3, explicit("alpha", 9, 9));

        assertThatThrownBy(() -> store.compareAndSet(1L, loser)).isInstanceOfSatisfying(RequestRefusedException.class,
            e -> assertThat(e.code()).isEqualTo(CapacityErrorCode.CAPACITY_REVISION_CONFLICT));
        assertThat(store.read()).contains(winner);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM runtime_capacity_target", Integer.class)).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("TC-D0124-04 / RC-B-406: two threads writing from the same @Version at once leave exactly one winner (version 2) and refuse the other")
    void concurrentWritersOfTheSameVersionLeaveOneState() throws Exception {
        CapacityState first = state(1L, 10, 2);
        CapacityState stateB = state(1L, 5, 2, explicit("alpha", 3, null));
        CapacityState stateC = state(1L, 6, 3, explicit("beta", null, 2));
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            tx.execute(status -> store.initialize(first));
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Throwable> resultB = pool.submit(attempt(barrier, stateB));
            Future<Throwable> resultC = pool.submit(attempt(barrier, stateC));
            Throwable failureB = resultB.get(WAIT_SECONDS, TimeUnit.SECONDS);
            Throwable failureC = resultC.get(WAIT_SECONDS, TimeUnit.SECONDS);

            assertThat(List.of(failureB == null, failureC == null)).containsExactlyInAnyOrder(true, false);
            assertThat(failureB == null ? failureC : failureB).isInstanceOfSatisfying(RequestRefusedException.class,
                e -> assertThat(e.code()).isEqualTo(CapacityErrorCode.CAPACITY_REVISION_CONFLICT));
            CapacityState stored = tx.execute(status -> store.read().orElseThrow());
            assertThat(stored.version()).isEqualTo(2L);
            CapacityState expectedWinner = failureB == null ? state(2L, 5, 2, explicit("alpha", 3, null)) : state(2L, 6, 3, explicit("beta", null, 2));
            assertThat(stored).isEqualTo(expectedWinner);
        } finally {
            clean();
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("RC-B-405: two threads that initialize an empty store at the same time both get the same state, the winner's")
    void concurrentFirstWritersGetOneState() throws Exception {
        CapacityState stateB = state(1L, 5, 2, explicit("alpha", 3, null));
        CapacityState stateC = state(1L, 6, 3, explicit("beta", null, 2));
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<CapacityState> resultB = pool.submit(initialization(barrier, stateB));
            Future<CapacityState> resultC = pool.submit(initialization(barrier, stateC));
            CapacityState seenByB = resultB.get(WAIT_SECONDS, TimeUnit.SECONDS);
            CapacityState seenByC = resultC.get(WAIT_SECONDS, TimeUnit.SECONDS);

            assertThat(seenByB).isEqualTo(seenByC);
            assertThat(List.of(stateB, stateC)).contains(seenByB);
            assertThat(store.read()).contains(seenByB);
        } finally {
            clean();
        }
    }

    @Test
    @DisplayName("RC-B-407: a value that breaks a CHECK or NOT NULL of the schema cannot be stored")
    void aValueThatBreaksTheSchemaCannotBeStored() {
        String insert = "INSERT INTO runtime_capacity (id, queue_capacity, max_concurrency, version) VALUES (?, ?, ?, ?)";

        refused(insert, 1, 0, 1, 1);
        refused(insert, 1, 1, 0, 1);
        refused(insert, 1, null, 1, 1);
        refused(insert, 1, 1, 1, 0);
        refused(insert, 2, 1, 1, 1);
        refused("INSERT INTO runtime_capacity_target (target_id, queue_capacity, max_concurrency) VALUES ('alpha', 0, NULL)");
        refused("INSERT INTO runtime_capacity_target (target_id, queue_capacity, max_concurrency) VALUES (NULL, 1, NULL)");
        assertThat(store.read()).isEmpty();
    }

    @Test
    @DisplayName("RC-B-409: the override of a target that is not in the configuration is stored and read back; a state that names a target twice is refused")
    void theOverrideOfAnUnknownTargetIsKept() {
        CapacityState first = state(1L, 10, 2, explicit("windows", 7, null));
        store.initialize(first);
        CapacityState next = store.compareAndSet(1L, state(1L, 10, 2, explicit("linux", null, 3), explicit("windows", 7, null)));

        assertThat(next.version()).isEqualTo(2L);
        assertThat(store.read()).contains(next);
        CapacityState twice = state(2L, 10, 2, explicit("linux", 1, null), explicit("linux", 2, null));
        assertThatThrownBy(() -> store.compareAndSet(2L, twice)).isInstanceOfSatisfying(RequestRefusedException.class,
            e -> assertThat(e.code()).isEqualTo(CapacityErrorCode.INVALID_CAPACITY_TARGETS));
        assertThatThrownBy(() -> store.initialize(twice)).isInstanceOfSatisfying(RequestRefusedException.class,
            e -> assertThat(e.code()).isEqualTo(CapacityErrorCode.INVALID_CAPACITY_TARGETS));
        assertThat(store.read()).contains(next);
    }

    private Callable<Throwable> attempt(CyclicBarrier barrier, CapacityState next) {
        return () -> {
            barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
            return catchThrowable(() -> tx.execute(status -> store.compareAndSet(1L, next)));
        };
    }

    private Callable<CapacityState> initialization(CyclicBarrier barrier, CapacityState initial) {
        return () -> {
            barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
            try {
                return tx.execute(status -> store.initialize(initial));
            } catch (DuplicateKeyException e) {
                return tx.execute(status -> store.read().orElseThrow());
            }
        };
    }

    private void clean() {
        jdbc.update("DELETE FROM runtime_capacity_target");
        jdbc.update("DELETE FROM runtime_capacity");
    }

    private CapacityState state(long version, int queue, int concurrency, CapacityState.TargetCapacity... overrides) {
        return new CapacityState(version, new Capacity(queue), new Capacity(concurrency), List.of(overrides));
    }

    private CapacityState.TargetCapacity explicit(String target, Integer queue, Integer concurrency) {
        return new CapacityState.TargetCapacity(new TargetId(target), override(queue), override(concurrency));
    }

    private CapacityOverride override(Integer value) {
        if (value == null) {
            return new CapacityOverride.Inherited();
        }
        return new CapacityOverride.Explicit(new Capacity(value));
    }
}
