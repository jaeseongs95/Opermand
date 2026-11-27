package io.github.jaeseongs95.opermand.store.migration;

import static io.github.jaeseongs95.opermand.store.migration.MigrationTestSupport.CLOCK;
import static io.github.jaeseongs95.opermand.store.migration.MigrationTestSupport.count;
import static io.github.jaeseongs95.opermand.store.migration.MigrationTestSupport.freshDatabase;
import static io.github.jaeseongs95.opermand.store.migration.MigrationTestSupport.history;
import static io.github.jaeseongs95.opermand.store.migration.MigrationTestSupport.script;
import static io.github.jaeseongs95.opermand.store.migration.MigrationTestSupport.tableExists;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.jaeseongs95.opermand.store.migration.MigrationException.Reason;
import io.github.jaeseongs95.opermand.store.migration.MigrationRunner.Phase;

/**
 * S-D0109-27 and S-D0109-28: how a failed attempt is recorded. The first case fails the driver's call at the last statement of an attempt (the {@code UPDATE} that turns the claim to
 * SUCCEEDED) and at the {@code commit()} after it, through {@link InterceptingDataSource}, which injects the error of a lost connection and lets every other call reach the real H2
 * database. The second runs on the real H2 behaviour that a DDL statement commits the transaction before it, the claim row included.
 */
class MigrationFailureRecordTest {

    private static final String MARKER_ROW = "INSERT INTO marker (n) VALUES (1)";

    private DataSource database;
    private JdbcTemplate jdbc;

    @BeforeEach
    void historyExists() {
        database = freshDatabase();
        jdbc = new JdbcTemplate(database);
        assertThat(new MigrationRunner(CLOCK, database).run(Phase.EXPAND, List.of())).isZero();
        jdbc.execute("CREATE TABLE marker (n INTEGER NOT NULL)");
    }

    /** The data source whose connection fails the call that {@code failing} names, once, after the {@code UPDATE} of the attempt was prepared (for commit) or at it (for prepareStatement). */
    private DataSource failingOnce(String failingMethod) {
        AtomicBoolean updatePrepared = new AtomicBoolean();
        AtomicBoolean failed = new AtomicBoolean();
        return new InterceptingDataSource(database, (method, arguments) -> {
            boolean isUpdate = method.equals("prepareStatement") && arguments[0] instanceof String sql && sql.startsWith("UPDATE ");
            if (isUpdate) {
                updatePrepared.set(true);
            }
            boolean atTheFailingCall = failingMethod.equals("prepareStatement") ? isUpdate : method.equals(failingMethod) && updatePrepared.get();
            if (atTheFailingCall && failed.compareAndSet(false, true)) {
                throw new SQLException("the connection was lost at " + failingMethod);
            }
        });
    }

    private void assertTheFailedAttemptIsRecordedAndTheNextSucceeds(String failingMethod) {
        MigrationScript numberOne = script(1, MARKER_ROW);

        assertThatThrownBy(() -> new MigrationRunner(CLOCK, failingOnce(failingMethod)).run(Phase.EXPAND, List.of(numberOne))).isInstanceOfSatisfying(MigrationException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(Reason.MIGRATION_FAILED);
            assertThat(failure.getCause()).isInstanceOf(SQLException.class).hasMessage("the connection was lost at " + failingMethod);
        });

        assertThat(history(jdbc, Phase.EXPAND)).as("the attempt is in the history as FAILED").containsExactly("1/1/FAILED");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM marker")).as("the script's effect was rolled back").isZero();
        assertThat(new MigrationRunner(CLOCK, database).run(Phase.EXPAND, List.of(numberOne))).isEqualTo(1);
        assertThat(history(jdbc, Phase.EXPAND)).containsExactly("1/1/FAILED", "1/2/SUCCEEDED");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM marker")).isEqualTo(1);
    }

    @Test
    @DisplayName("S-D0109-27: a SQLException at the last statement (the UPDATE of the claim) stops with MIGRATION_FAILED, rolls the script back and leaves the attempt as FAILED; the next start is attempt 2")
    void aFailureOfTheLastStatementIsRecorded() {
        assertTheFailedAttemptIsRecordedAndTheNextSucceeds("prepareStatement");
    }

    @Test
    @DisplayName("S-D0109-27: a SQLException at the commit of the attempt stops with MIGRATION_FAILED, rolls the script back and leaves the attempt as FAILED; the next start is attempt 2")
    void aFailureOfTheCommitIsRecorded() {
        assertTheFailedAttemptIsRecordedAndTheNextSucceeds("commit");
    }

    @Test
    @DisplayName("S-D0109-28 / D-0141: a non-transactional DDL failure compensates created objects in reverse order so the next attempt with a fixed script succeeds automatically")
    void aHalfAppliedDdlScriptIsCompensatedAutomatically() {
        String halfApplied = "CREATE TABLE half (n INTEGER NOT NULL); CREATE INDEX ix_half_n ON half (n); INSERT INTO no_such_table (n) VALUES (1)";
        ch.qos.logback.classic.Logger runnerLog = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MigrationRunner.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        runnerLog.addAppender(logged);
        try {
            assertThatThrownBy(() -> new MigrationRunner(CLOCK, database).run(Phase.EXPAND, List.of(script(1, halfApplied)))).isInstanceOfSatisfying(MigrationException.class,
                failure -> assertThat(failure.reason()).isEqualTo(Reason.MIGRATION_FAILED));
        } finally {
            runnerLog.detachAppender(logged);
        }
        assertThat(history(jdbc, Phase.EXPAND)).as("the claim was committed by the DDL; the failure adds no second row").containsExactly("1/1/FAILED");
        assertThat(tableExists(jdbc, "PUBLIC", "half")).as("compensating DROP TABLE cleaned up the partial DDL automatically (D-0141)").isFalse();
        assertThat(logged.list).as("the duplicate key of the failure record is logged, not swallowed").anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("its attempt is in the history already");
        });

        String fixed = "CREATE TABLE half (n INTEGER NOT NULL); CREATE INDEX ix_half_n ON half (n); INSERT INTO half (n) VALUES (1)";
        assertThat(new MigrationRunner(CLOCK, database).run(Phase.EXPAND, List.of(script(1, fixed)))).isEqualTo(1);
        assertThat(history(jdbc, Phase.EXPAND)).containsExactly("1/1/FAILED", "1/2/SUCCEEDED");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM half")).isEqualTo(1);
    }

    @Test
    @DisplayName("D-0141 crash-resume: if a prior crashed attempt left a FAILED history row and an already-created table, the next attempt resumes cleanly")
    void aCrashedNonTransactionalDdlAttemptResumesCleanly() {
        MigrationScript fixed = script(1, "CREATE TABLE crashed_half (n INTEGER NOT NULL); INSERT INTO crashed_half (n) VALUES (42)");
        jdbc.execute("CREATE TABLE crashed_half (n INTEGER NOT NULL)");
        jdbc.update("INSERT INTO portal_meta.expand_history (version, attempt, script, digest, applied_at, outcome) VALUES (1, 1, ?, ?, TIMESTAMP '2026-11-12 00:00:00', 'FAILED')",
            fixed.file(), fixed.digest());

        assertThat(new MigrationRunner(CLOCK, database).run(Phase.EXPAND, List.of(fixed))).isEqualTo(1);
        assertThat(history(jdbc, Phase.EXPAND)).containsExactly("1/1/FAILED", "1/2/SUCCEEDED");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM crashed_half")).isEqualTo(1);
    }
}
