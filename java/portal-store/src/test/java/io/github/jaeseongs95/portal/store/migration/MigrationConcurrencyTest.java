package io.github.jaeseongs95.portal.store.migration;

import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.CLOCK;
import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.NOW;
import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.count;
import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.freshDatabase;
import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.history;
import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.script;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.jaeseongs95.portal.store.migration.MigrationException.Reason;
import io.github.jaeseongs95.portal.store.migration.MigrationRunner.Phase;

/**
 * S-D0109-06 and S-D0109-07: two runners that apply the same number at the same time (common format, 3 and 6). The overlap is not assumed from a start barrier: a
 * <em>holder</em> connection of the test keeps the claim row of the number uncommitted, so both runners are blocked on that one statement, and the test waits until the
 * database itself reports the two waiting sessions (H2: {@code INFORMATION_SCHEMA.SESSIONS}, columns {@code BLOCKER_ID}, {@code SESSION_STATE} and {@code EXECUTING_STATEMENT},
 * https://h2database.com/html/systemtables.html#sessions; the id of a session is {@code SESSION_ID()}; see {@link #sessionsWaitingFor} for what H2 shows for an INSERT wait).
 * The wait is on that condition with a deadline (Awaitility {@code atMost}), not on time. The runner itself has no sleep and no poll: the database makes it wait.
 */
class MigrationConcurrencyTest {

    private static final Duration DEADLINE = Duration.ofSeconds(30);

    private DataSource database;
    private JdbcTemplate jdbc;
    private ExecutorService executor;
    private Connection holder;

    @BeforeEach
    void preparedDatabase() throws SQLException {
        database = freshDatabase();
        jdbc = new JdbcTemplate(database);
        executor = Executors.newFixedThreadPool(2);
        // Number 1 creates the tables; the history and the two tables exist before the race, so the race is the claim of number 2 alone.
        new MigrationRunner(CLOCK, database).run(Phase.EXPAND, List.of(script(1, "CREATE TABLE marker (n INTEGER NOT NULL)")));
        holder = database.getConnection();
        holder.setAutoCommit(false);
    }

    @AfterEach
    void closeEverything() throws SQLException {
        try {
            if (!holder.isClosed()) {
                holder.rollback();
            }
        } finally {
            holder.close();
            executor.shutdownNow();
        }
    }

    private static MigrationScript numberOne() {
        return script(1, "CREATE TABLE marker (n INTEGER NOT NULL)");
    }

    private static MigrationScript numberTwo() {
        return script(2, "INSERT INTO marker (n) VALUES (2)");
    }

    /** The holder's claim row of number 2, attempt 1, written but not committed; returns the id of the holder's session. */
    private long holdTheClaimOfNumberTwo(String outcome) throws SQLException {
        try (PreparedStatement claim = holder.prepareStatement("INSERT INTO " + Phase.EXPAND.table() + " (version, attempt, script, digest, applied_at, outcome) VALUES (2, 1, ?, ?, ?, ?)")) {
            claim.setString(1, numberTwo().file());
            claim.setString(2, numberTwo().digest());
            claim.setObject(3, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
            claim.setString(4, outcome);
            claim.executeUpdate();
        }
        return holderSessionId();
    }

    private long holderSessionId() throws SQLException {
        try (var statement = holder.createStatement(); var rows = statement.executeQuery("SELECT SESSION_ID()")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    /**
     * The sessions the database shows as waiting for the holder. H2 2.4.240 shows a row-lock wait of an UPDATE or DELETE as {@code SESSION_STATE = BLOCKED} with the holder's id in
     * {@code BLOCKER_ID}; the wait of an INSERT on a key that the holder has written and not committed is shown as a session that is {@code RUNNING} the INSERT, with no blocker (observed
     * on 2.4.240 by reading {@code INFORMATION_SCHEMA.SESSIONS} while the wait lasted; see docs/migration/migration-runner.md). So a session counts as waiting when the database says that it is blocked by the holder, or that
     * it is executing the claim INSERT while the holder has uncommitted changes: that INSERT cannot finish before the holder ends its transaction.
     */
    private long sessionsWaitingFor(long holderSession) {
        return count(jdbc, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_ID <> ? AND (BLOCKER_ID = ? OR (LOWER(EXECUTING_STATEMENT) LIKE 'insert into "
            + Phase.EXPAND.table() + "%' AND EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.SESSIONS holder WHERE holder.SESSION_ID = ? AND holder.CONTAINS_UNCOMMITTED)))",
            holderSession, holderSession, holderSession);
    }

    private Future<Integer> startRunner() {
        return executor.submit(() -> new MigrationRunner(CLOCK, database).run(Phase.EXPAND, List.of(numberOne(), numberTwo())));
    }

    @Test
    @DisplayName("S-D0109-06: two runners on the same number both wait on the database's lock; afterwards there is one SUCCEEDED row, one application, and the other runner skips")
    void twoRunnersApplyTheNumberOnce() throws Exception {
        long holderSession = holdTheClaimOfNumberTwo("FAILED");
        Future<Integer> first = startRunner();
        Future<Integer> second = startRunner();

        await().atMost(DEADLINE).untilAsserted(() -> assertThat(sessionsWaitingFor(holderSession)).as("sessions blocked by the holder's claim").isEqualTo(2));
        assertThat(first.isDone() || second.isDone()).as("both runners are still at the claim").isFalse();
        holder.rollback();

        int total = first.get(DEADLINE.toSeconds(), TimeUnit.SECONDS) + second.get(DEADLINE.toSeconds(), TimeUnit.SECONDS);
        assertThat(total).as("the number was applied by exactly one runner").isEqualTo(1);
        assertThat(history(jdbc, Phase.EXPAND)).containsExactly("1/1/SUCCEEDED", "2/1/SUCCEEDED");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM marker WHERE n = 2")).as("the script ran once").isEqualTo(1);
    }

    @Test
    @DisplayName("S-D0109-07: a runner that waited for a peer whose attempt failed stops with MIGRATION_FAILED, applies nothing and adds no row")
    void aRunnerStopsWhenThePeerFailed() throws Exception {
        long holderSession = holdTheClaimOfNumberTwo("FAILED");
        Future<Integer> waiting = startRunner();

        await().atMost(DEADLINE).untilAsserted(() -> assertThat(sessionsWaitingFor(holderSession)).isEqualTo(1));
        holder.commit();

        assertThat(waitingFailure(waiting)).isInstanceOfSatisfying(MigrationException.class, failure -> assertThat(failure.reason()).isEqualTo(Reason.MIGRATION_FAILED));
        assertThat(history(jdbc, Phase.EXPAND)).containsExactly("1/1/SUCCEEDED", "2/1/FAILED");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM marker")).as("the script of number 2 was not applied").isZero();
    }

    private static Throwable waitingFailure(Future<Integer> future) throws InterruptedException, TimeoutException {
        try {
            future.get(DEADLINE.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            return failure.getCause();
        }
        throw new AssertionError("the runner was expected to stop with a failure");
    }
}
