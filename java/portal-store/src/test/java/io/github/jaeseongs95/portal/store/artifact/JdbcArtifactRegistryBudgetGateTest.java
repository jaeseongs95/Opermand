package io.github.jaeseongs95.portal.store.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.portal.app.artifact.ArtifactRegistry;
import io.github.jaeseongs95.portal.app.artifact.ArtifactReservation;
import io.github.jaeseongs95.portal.store.StoreTest;

/**
 * S-D0058-01: two reservations of the artifact budget that run at the same moment are judged one after the other. The overlap is not assumed from a start barrier: the first
 * reservation is held open in a transaction of the test (its row written, not committed), the second one is started, and the test waits until the database itself reports a session
 * that is blocked by another one (H2: {@code INFORMATION_SCHEMA.SESSIONS}, column {@code BLOCKER_ID}). Only then is the first transaction committed, and the result is judged by the
 * invariant: the budget of 100 bytes holds one file of 60 bytes, so the second reservation must be refused. Without the gate the second one reads the sum before the first one's row is
 * committed, is not blocked, and is accepted (the test then fails for want of a blocked session).
 */
@StoreTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JdbcArtifactRegistry.class, ArtifactMapper.class, FileArtifactPayloadStore.class, ArtifactTestStorage.class})
class JdbcArtifactRegistryBudgetGateTest {

    private static final Duration DEADLINE = Duration.ofSeconds(30);
    private static final long BUDGET = 100L;
    private static final long FILE = 60L;

    @TestConfiguration(proxyBeanMethods = false)
    static class Limits {
        @Bean
        ArtifactLimits artifactLimits() {
            return new ArtifactLimits(BUDGET, FILE, ArtifactLimits.CHUNK_BYTES, ArtifactLimits.UPLOAD_TTL, 0L);
        }
    }

    private final ArtifactFixtures fixtures = new ArtifactFixtures();
    private final ArtifactRegistry registry;
    private final PlatformTransactionManager transactions;
    private final ArtifactRowRepository artifacts;
    private final JdbcTemplate jdbc;

    JdbcArtifactRegistryBudgetGateTest(ArtifactRegistry registry, PlatformTransactionManager transactions, ArtifactRowRepository artifacts, JdbcTemplate jdbc) {
        this.registry = registry;
        this.transactions = transactions;
        this.artifacts = artifacts;
        this.jdbc = jdbc;
    }

    @AfterEach
    void removeRows() {
        artifacts.deleteAll();
        new TransactionTemplate(transactions).executeWithoutResult(status -> registry.reconcileBudget());
    }

    @Test
    @DisplayName("S-D0058-01 (D-0058): a second reservation waits for the first one's transaction on the database's lock, then reads the sum that includes it and is refused; the budget is not passed")
    void twoReservationsAreJudgedOneAfterTheOther() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<ArtifactReservation> first = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                ArtifactReservation reservation = registry.reserve(fixtures.upload(FILE), fixtures.at(), ArtifactFixtures.UNLIMITED_DISK);
                held.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("The first reservation was interrupted while it was held open", e);
                }
                return reservation;
            }));
            assertThat(held.await(DEADLINE.toSeconds(), TimeUnit.SECONDS)).as("the first reservation holds its row, uncommitted").isTrue();
            Future<ArtifactReservation> second = pool.submit(() -> new TransactionTemplate(transactions).execute(status ->
                registry.reserve(fixtures.upload(FILE), fixtures.at(), ArtifactFixtures.UNLIMITED_DISK)));

            await().atMost(DEADLINE).untilAsserted(() -> assertThat(blockedSessions()).as("a session that the database shows as blocked by the first reservation").isPositive());
            assertThat(second.isDone()).as("the second reservation is still waiting for the first one").isFalse();
            release.countDown();

            assertThat(first.get(DEADLINE.toSeconds(), TimeUnit.SECONDS)).isInstanceOf(ArtifactReservation.Reserved.class);
            assertThat(second.get(DEADLINE.toSeconds(), TimeUnit.SECONDS)).isEqualTo(new ArtifactReservation.BudgetExceeded(FILE, FILE, BUDGET));
        }
        assertThat(registry.budget().chargedBytes()).as("only the first file is charged").isEqualTo(FILE);
    }

    private long blockedSessions() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL", Long.class);
        return count == null ? 0L : count;
    }
}
