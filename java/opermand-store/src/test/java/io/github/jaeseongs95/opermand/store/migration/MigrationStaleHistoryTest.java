package io.github.jaeseongs95.opermand.store.migration;

import static io.github.jaeseongs95.opermand.store.migration.MigrationTestSupport.CLOCK;
import static io.github.jaeseongs95.opermand.store.migration.MigrationTestSupport.count;
import static io.github.jaeseongs95.opermand.store.migration.MigrationTestSupport.freshDatabase;
import static io.github.jaeseongs95.opermand.store.migration.MigrationTestSupport.history;
import static io.github.jaeseongs95.opermand.store.migration.MigrationTestSupport.script;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.jaeseongs95.opermand.store.migration.MigrationException.Reason;
import io.github.jaeseongs95.opermand.store.migration.MigrationRunner.Phase;

/**
 * S-D0109-25 and S-D0109-26: a runner whose history is out of date when it starts to apply. Runner B reads the history (empty) and decides which scripts are pending; runner A then
 * applies number 1 completely; only then B goes on. The order is not left to the scheduler: B is stopped by the test at its first {@code commit()} (the commit that follows the one
 * read of the history in {@link MigrationRunner#run}) with a latch, the test runs A on its own thread to the end, and then releases B. Both waits have a deadline and there is no sleep.
 * B must claim the attempt of its own history ((1, 1)), meet A's row as a duplicate key, read the history again and skip (or stop), not take attempt 2 and apply the script a second time.
 */
class MigrationStaleHistoryTest {

    private static final Duration DEADLINE = Duration.ofSeconds(30);
    private static final String MARKER_ROW = "INSERT INTO marker (n) VALUES (1)";

    private DataSource database;
    private JdbcTemplate jdbc;
    private ExecutorService executor;
    private final CountDownLatch historyIsRead = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicBoolean stopped = new AtomicBoolean();

    @BeforeEach
    void historyExistsAndIsEmpty() {
        database = freshDatabase();
        jdbc = new JdbcTemplate(database);
        executor = Executors.newSingleThreadExecutor();
        // An empty list of scripts makes the schema and the history table and applies nothing: the race is the claim of number 1 alone.
        assertThat(new MigrationRunner(CLOCK, database).run(Phase.EXPAND, List.of())).isZero();
        jdbc.execute("CREATE TABLE marker (n INTEGER NOT NULL)");
    }

    @AfterEach
    void releaseAndStop() {
        release.countDown();
        executor.shutdownNow();
    }

    /** A data source of the same database whose first {@code commit()} waits for the test: the commit right after the history was read. */
    private DataSource stoppedAfterReadingTheHistory() {
        return new InterceptingDataSource(database, (method, arguments) -> {
            if (method.equals("commit") && stopped.compareAndSet(false, true)) {
                historyIsRead.countDown();
                try {
                    if (!release.await(DEADLINE.toSeconds(), TimeUnit.SECONDS)) {
                        throw new SQLException("the test did not release the runner within its deadline");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("the runner was interrupted while it waited for the test", interrupted);
                }
            }
        });
    }

    private Future<Integer> startRunnerB(MigrationScript numberOne) throws InterruptedException {
        Future<Integer> runnerB = executor.submit(() -> new MigrationRunner(CLOCK, stoppedAfterReadingTheHistory()).run(Phase.EXPAND, List.of(numberOne)));
        assertThat(historyIsRead.await(DEADLINE.toSeconds(), TimeUnit.SECONDS)).as("runner B has read the history").isTrue();
        assertThat(runnerB.isDone()).as("runner B is held before it claims the number").isFalse();
        return runnerB;
    }

    @Test
    @DisplayName("S-D0109-25: runner B read an empty history, runner A applied number 1, then B goes on: B skips (duplicate key, history read again), no attempt 2, the script ran once")
    void aRunnerWithAnOldHistorySkipsWhatAPeerApplied() throws Exception {
        MigrationScript numberOne = script(1, MARKER_ROW);
        Future<Integer> runnerB = startRunnerB(numberOne);

        assertThat(new MigrationRunner(CLOCK, database).run(Phase.EXPAND, List.of(numberOne))).as("runner A applied number 1").isEqualTo(1);
        release.countDown();

        assertThat(runnerB.get(DEADLINE.toSeconds(), TimeUnit.SECONDS)).as("runner B applied nothing").isZero();
        assertThat(history(jdbc, Phase.EXPAND)).containsExactly("1/1/SUCCEEDED");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM marker")).as("the script ran once").isEqualTo(1);
    }

    @Test
    @DisplayName("S-D0109-26: the same order, but runner A's script failed: B stops with MIGRATION_FAILED, does not run the script and adds no row; the message does not say the peer failed for sure")
    void aRunnerWithAnOldHistoryStopsWhenThePeerFailed() throws Exception {
        MigrationScript failing = script(1, MARKER_ROW + "; INSERT INTO no_such_table (n) VALUES (1)");
        Future<Integer> runnerB = startRunnerB(failing);

        assertThatThrownBy(() -> new MigrationRunner(CLOCK, database).run(Phase.EXPAND, List.of(failing))).isInstanceOfSatisfying(MigrationException.class,
            failure -> assertThat(failure.reason()).isEqualTo(Reason.MIGRATION_FAILED));
        release.countDown();

        assertThat(waitingFailure(runnerB)).isInstanceOfSatisfying(MigrationException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(Reason.MIGRATION_FAILED);
            assertThat(failure.getMessage()).as("true when the peer failed and when it is still applying").contains("failed, or is still applying it").doesNotContain("did not apply it");
        });
        assertThat(history(jdbc, Phase.EXPAND)).containsExactly("1/1/FAILED");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM marker")).as("neither runner left the first statement of the script").isZero();
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
