package io.github.jaeseongs95.opermand.store.migration;

import static io.github.jaeseongs95.opermand.store.migration.MigrationTestSupport.CLOCK;
import static io.github.jaeseongs95.opermand.store.migration.MigrationTestSupport.NOW;
import static io.github.jaeseongs95.opermand.store.migration.MigrationTestSupport.count;
import static io.github.jaeseongs95.opermand.store.migration.MigrationTestSupport.freshDatabase;
import static io.github.jaeseongs95.opermand.store.migration.MigrationTestSupport.history;
import static io.github.jaeseongs95.opermand.store.migration.MigrationTestSupport.script;
import static io.github.jaeseongs95.opermand.store.migration.MigrationTestSupport.tableExists;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.jaeseongs95.opermand.store.migration.MigrationException.Reason;
import io.github.jaeseongs95.opermand.store.migration.MigrationRunner.Phase;

/**
 * S-D0109-32: the history table has no CHECK on {@code outcome} (common format edition 4, section 2: only the runner writes it, and it is checked against the enum when it is read).
 * A value that is neither SUCCEEDED nor FAILED, put there by someone else, stops the run before anything is applied.
 */
class MigrationHistoryOutcomeTest {

    @Test
    @DisplayName("S-D0109-32: the history table has no CHECK constraint, and a row whose outcome is neither SUCCEEDED nor FAILED stops the run with MIGRATION_FAILED before anything is applied")
    void theOutcomeIsCheckedWhenItIsRead() {
        DataSource database = freshDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        MigrationRunner runner = new MigrationRunner(CLOCK, database);
        assertThat(runner.run(Phase.EXPAND, List.of(script(1, "CREATE TABLE t1 (id INTEGER NOT NULL)")))).isEqualTo(1);

        assertThat(count(jdbc, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = 'PORTAL_META' AND TABLE_NAME = 'EXPAND_HISTORY' AND CONSTRAINT_TYPE = 'CHECK'"))
            .as("check constraints of the history table").isZero();
        assertThat(jdbc.update("INSERT INTO " + Phase.EXPAND.table() + " (version, attempt, script, digest, applied_at, outcome) VALUES (2, 1, 'x', ?, ?, 'BOGUS')",
            "0".repeat(64), LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))).as("the database takes any text in outcome").isEqualTo(1);

        assertThatThrownBy(() -> runner.run(Phase.EXPAND, List.of(script(1, "CREATE TABLE t1 (id INTEGER NOT NULL)"), script(2, "CREATE TABLE t2 (id INTEGER NOT NULL)"))))
            .isInstanceOfSatisfying(MigrationException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.MIGRATION_FAILED);
                assertThat(failure.getMessage()).contains("'BOGUS'", "number 2", "attempt 1");
            });
        assertThat(tableExists(jdbc, "PUBLIC", "t2")).as("nothing was applied").isFalse();
        assertThat(history(jdbc, Phase.EXPAND)).containsExactly("1/1/SUCCEEDED", "2/1/BOGUS");
    }
}
