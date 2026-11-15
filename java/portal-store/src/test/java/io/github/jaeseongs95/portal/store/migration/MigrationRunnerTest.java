package io.github.jaeseongs95.portal.store.migration;

import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.CLOCK;
import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.NOW;
import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.count;
import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.freshDatabase;
import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.history;
import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.schemaExists;
import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.script;
import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.tableExists;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.jaeseongs95.portal.domain.id.Sha256Digester;
import io.github.jaeseongs95.portal.store.migration.MigrationException.Reason;
import io.github.jaeseongs95.portal.store.migration.MigrationRunner.Phase;
import io.github.jaeseongs95.portal.store.sql.SqlBuilder;

/**
 * S-D0109-01 to S-D0109-05, 09 to 13 and 15: the migration runner on a real embedded H2 database (no compatibility mode), with scripts the test writes. The format is the
 * common one of the three products: one history row per attempt, the first statement claims the number as FAILED, the last turns the own row to SUCCEEDED, numbers are
 * consecutive, a changed file stops before anything is applied.
 */
class MigrationRunnerTest {

    private DataSource database;
    private JdbcTemplate jdbc;
    private MigrationRunner runner;

    @BeforeEach
    void freshDatabaseAndRunner() {
        database = freshDatabase();
        jdbc = new JdbcTemplate(database);
        runner = new MigrationRunner(CLOCK, database);
    }

    private static String sha256(String text) {
        return new Sha256Digester().digest(text.getBytes(StandardCharsets.UTF_8)).hex();
    }

    // ---- S-D0109-01, 02: the history ----

    @Test
    @DisplayName("S-D0109-01: every attempt is one history row with the file name, the SHA-256 of the file, the time of the injected Clock and SUCCEEDED; the key is (version, attempt)")
    void everyAttemptIsOneRow() {
        String first = "CREATE TABLE t1 (id INTEGER NOT NULL)";
        String second = "CREATE TABLE t2 (id INTEGER NOT NULL)";

        int applied = runner.run(Phase.EXPAND, List.of(script(1, first), script(2, second)));

        assertThat(applied).isEqualTo(2);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT version, attempt, script, digest, applied_at, outcome FROM " + Phase.EXPAND.table() + " ORDER BY version");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("VERSION", 1L).containsEntry("ATTEMPT", 1).containsEntry("SCRIPT", "V1__test.sql").containsEntry("OUTCOME", "SUCCEEDED");
        assertThat(((String) rows.get(0).get("DIGEST")).strip()).isEqualTo(sha256(first)).hasSize(64);
        assertThat(((String) rows.get(1).get("DIGEST")).strip()).isEqualTo(sha256(second));
        assertThat(jdbc.queryForObject("SELECT applied_at FROM " + Phase.EXPAND.table() + " WHERE version = 1", LocalDateTime.class))
            .as("the UTC fields of the injected Clock's instant").isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + Phase.EXPAND.table() + " (version, attempt, script, digest, applied_at, outcome) VALUES (3, 1, 'x', ?, ?, 'BOGUS')",
            "0".repeat(64), LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))).as("outcome is SUCCEEDED or FAILED").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + Phase.EXPAND.table() + " (version, attempt, script, digest, applied_at, outcome) VALUES (1, 1, 'x', ?, ?, 'FAILED')",
            "0".repeat(64), LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))).as("the key is (version, attempt)").isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update("INSERT INTO " + Phase.EXPAND.table() + " (version, attempt, script, digest, applied_at, outcome) VALUES (1, 2, 'x', ?, ?, 'FAILED')",
            "0".repeat(64), LocalDateTime.ofInstant(NOW, ZoneOffset.UTC))).as("a second attempt of a number is a row of its own").isEqualTo(1);
    }

    @Test
    @DisplayName("S-D0109-02: a number that was applied is not read again: the second run applies nothing and adds no row")
    void aSecondRunAppliesNothing() {
        List<MigrationScript> scripts = List.of(script(1, "CREATE TABLE t1 (id INTEGER NOT NULL)"), script(2, "CREATE TABLE t2 (id INTEGER NOT NULL)"));
        assertThat(runner.run(Phase.EXPAND, scripts)).isEqualTo(2);

        assertThat(runner.run(Phase.EXPAND, scripts)).isZero();

        assertThat(history(jdbc, Phase.EXPAND)).containsExactly("1/1/SUCCEEDED", "2/1/SUCCEEDED");
    }

    // ---- S-D0109-03, 04, 05: failure, retry, claim ----

    @Test
    @DisplayName("S-D0109-03: a failing script leaves a FAILED row that holds its number, the script's own effects are rolled back, and the rows of the others stay")
    void aFailedScriptLeavesAFailedRow() {
        MigrationScript good = script(1, "CREATE TABLE t1 (id INTEGER NOT NULL)");
        MigrationScript bad = script(2, "INSERT INTO t1 (id) VALUES (1); INSERT INTO no_such_table (id) VALUES (1)");

        assertThatThrownBy(() -> runner.run(Phase.EXPAND, List.of(good, bad))).isInstanceOfSatisfying(MigrationException.class,
            failure -> assertThat(failure.reason()).isEqualTo(Reason.MIGRATION_FAILED));

        assertThat(history(jdbc, Phase.EXPAND)).containsExactly("1/1/SUCCEEDED", "2/1/FAILED");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM t1")).as("the first statement of the failed script was rolled back").isZero();
    }

    @Test
    @DisplayName("S-D0109-04: a retry is the next attempt, a FAILED row is never deleted or changed, and a number that failed twice succeeds as attempt 3")
    void aRetryIsTheNextAttempt() {
        MigrationScript good = script(1, "CREATE TABLE t1 (id INTEGER NOT NULL)");
        MigrationScript bad = script(2, "INSERT INTO no_such_table (id) VALUES (1)");
        MigrationScript otherBad = script(2, "INSERT INTO another_missing_table (id) VALUES (1)");
        MigrationScript fixed = script(2, "INSERT INTO t1 (id) VALUES (2)");

        assertThatThrownBy(() -> runner.run(Phase.EXPAND, List.of(good, bad))).isInstanceOf(MigrationException.class);
        List<Map<String, Object>> failedRowBefore = jdbc.queryForList("SELECT * FROM " + Phase.EXPAND.table() + " WHERE version = 2 AND attempt = 1");
        assertThatThrownBy(() -> runner.run(Phase.EXPAND, List.of(good, otherBad))).isInstanceOf(MigrationException.class);
        assertThat(history(jdbc, Phase.EXPAND)).containsExactly("1/1/SUCCEEDED", "2/1/FAILED", "2/2/FAILED");

        int applied = runner.run(Phase.EXPAND, List.of(good, fixed));

        assertThat(applied).isEqualTo(1);
        assertThat(history(jdbc, Phase.EXPAND)).containsExactly("1/1/SUCCEEDED", "2/1/FAILED", "2/2/FAILED", "2/3/SUCCEEDED");
        assertThat(jdbc.queryForList("SELECT * FROM " + Phase.EXPAND.table() + " WHERE version = 2 AND attempt = 1")).as("the first FAILED row is as it was").isEqualTo(failedRowBefore);
        assertThat(count(jdbc, "SELECT COUNT(*) FROM t1 WHERE id = 2")).isEqualTo(1);
    }

    @Test
    @DisplayName("S-D0109-05: the first statement claims the number as FAILED, the last turns only the own row to SUCCEEDED; a FAILED row of an earlier attempt is not touched")
    void theClaimIsFirstAndOnlyTheOwnRowIsTurned() {
        MigrationScript tables = script(1, "CREATE TABLE seen (version BIGINT NOT NULL, attempt INTEGER NOT NULL, outcome VARCHAR(10) NOT NULL)");
        MigrationScript failing = script(2, "INSERT INTO no_such_table (id) VALUES (1)");
        assertThatThrownBy(() -> runner.run(Phase.EXPAND, List.of(tables, failing))).isInstanceOf(MigrationException.class);

        MigrationScript looking = script(2, "INSERT INTO seen (version, attempt, outcome) SELECT version, attempt, outcome FROM " + Phase.EXPAND.table());
        assertThat(runner.run(Phase.EXPAND, List.of(tables, looking))).isEqualTo(1);

        List<String> seenDuringTheScript = jdbc.query("SELECT version, attempt, outcome FROM seen ORDER BY version, attempt",
            (rows, number) -> rows.getLong("version") + "/" + rows.getInt("attempt") + "/" + rows.getString("outcome"));
        assertThat(seenDuringTheScript).as("while it ran, the own row (2, 2) was already there as FAILED, the others were as they were")
            .containsExactly("1/1/SUCCEEDED", "2/1/FAILED", "2/2/FAILED");
        assertThat(history(jdbc, Phase.EXPAND)).containsExactly("1/1/SUCCEEDED", "2/1/FAILED", "2/2/SUCCEEDED");
    }

    // ---- S-D0109-09, 10, 11: numbers and files ----

    @Test
    @DisplayName("S-D0109-09: numbers are consecutive from 1: a missing number, a start above 1 and a lower number that was never applied are refused with MIGRATION_OUT_OF_ORDER")
    void numbersAreConsecutive() {
        MigrationScript one = script(1, "CREATE TABLE t1 (id INTEGER NOT NULL)");
        MigrationScript two = script(2, "CREATE TABLE t2 (id INTEGER NOT NULL)");
        MigrationScript three = script(3, "CREATE TABLE t3 (id INTEGER NOT NULL)");

        for (List<MigrationScript> scripts : List.of(List.of(one, three), List.of(two))) {
            assertThatThrownBy(() -> runner.run(Phase.EXPAND, scripts)).isInstanceOfSatisfying(MigrationException.class,
                failure -> assertThat(failure.reason()).isEqualTo(Reason.MIGRATION_OUT_OF_ORDER));
            assertThat(schemaExists(jdbc, Migrations.SCHEMA)).as("nothing was applied, not even the history").isFalse();
            assertThat(tableExists(jdbc, "PUBLIC", "t1")).isFalse();
        }

        // A database that has applied 1 and 3 (made by another jar): the jar has 1, 2, 3, and 2 lies below the highest applied number.
        runner.run(Phase.EXPAND, List.of(one));
        jdbc.update("INSERT INTO " + Phase.EXPAND.table() + " (version, attempt, script, digest, applied_at, outcome) VALUES (3, 1, ?, ?, ?, 'SUCCEEDED')", three.file(), three.digest(),
            LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> runner.run(Phase.EXPAND, List.of(one, two, three))).isInstanceOfSatisfying(MigrationException.class,
            failure -> assertThat(failure.reason()).isEqualTo(Reason.MIGRATION_OUT_OF_ORDER));
        assertThat(tableExists(jdbc, "PUBLIC", "t2")).as("the lower number was not applied").isFalse();
    }

    @Test
    @DisplayName("S-D0109-10: a changed file stops the run with MIGRATION_CHANGED before anything is applied, and adds no history row")
    void aChangedFileStopsBeforeAnything() {
        runner.run(Phase.EXPAND, List.of(script(1, "CREATE TABLE t1 (id INTEGER NOT NULL)")));

        assertThatThrownBy(() -> runner.run(Phase.EXPAND, List.of(script(1, "CREATE TABLE t1 (id INTEGER NOT NULL, extra INTEGER)"), script(2, "CREATE TABLE t2 (id INTEGER NOT NULL)"))))
            .isInstanceOfSatisfying(MigrationException.class, failure -> assertThat(failure.reason()).isEqualTo(Reason.MIGRATION_CHANGED));

        assertThat(history(jdbc, Phase.EXPAND)).containsExactly("1/1/SUCCEEDED");
        assertThat(tableExists(jdbc, "PUBLIC", "t2")).isFalse();
    }

    @Test
    @DisplayName("S-D0109-11: a number of the history whose file is not in the jar stops the run with MIGRATION_MISSING and changes nothing")
    void aMissingFileStopsTheRun() {
        MigrationScript one = script(1, "CREATE TABLE t1 (id INTEGER NOT NULL)");
        runner.run(Phase.EXPAND, List.of(one, script(2, "CREATE TABLE t2 (id INTEGER NOT NULL)")));

        assertThatThrownBy(() -> runner.run(Phase.EXPAND, List.of(one))).isInstanceOfSatisfying(MigrationException.class,
            failure -> assertThat(failure.reason()).isEqualTo(Reason.MIGRATION_MISSING));

        assertThat(history(jdbc, Phase.EXPAND)).containsExactly("1/1/SUCCEEDED", "2/1/SUCCEEDED");
    }

    // ---- S-D0109-12: expand and contract ----

    @Test
    @DisplayName("S-D0109-12: expand and contract have their own history table; the contract table does not exist until a contract run")
    void expandAndContractAreSeparate() {
        runner.run(Phase.EXPAND, List.of(script(1, "CREATE TABLE gone_later (id INTEGER NOT NULL)")));
        assertThat(tableExists(jdbc, Migrations.SCHEMA, "expand_history")).isTrue();
        assertThat(tableExists(jdbc, Migrations.SCHEMA, "contract_history")).as("an expand run makes no contract history").isFalse();

        assertThat(runner.run(Phase.CONTRACT, List.of(script(1, "DROP TABLE gone_later")))).isEqualTo(1);

        assertThat(history(jdbc, Phase.EXPAND)).containsExactly("1/1/SUCCEEDED");
        assertThat(history(jdbc, Phase.CONTRACT)).containsExactly("1/1/SUCCEEDED");
        assertThat(tableExists(jdbc, "PUBLIC", "gone_later")).isFalse();
        assertThat(Phase.EXPAND.location()).isEqualTo("db/expand");
        assertThat(Phase.CONTRACT.location()).isEqualTo("db/contract");
    }

    // ---- S-D0109-13: placeholder ----

    @Test
    @DisplayName("S-D0109-13: ${long_text} becomes the long text type the driver reports; a 400,000 character text with Korean survives, and the digest is that of the file before the replacement")
    void theLongTextPlaceholderIsReplaced() {
        String text = "한글 text ".repeat(40_000);
        String sql = "CREATE TABLE long_t (id INTEGER NOT NULL, body ${long_text} NOT NULL)";

        assertThat(runner.run(Phase.EXPAND, List.of(script(1, sql)))).isEqualTo(1);

        Map<String, Object> column = jdbc.queryForMap("SELECT DATA_TYPE, CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = 'LONG_T' AND COLUMN_NAME = 'BODY'");
        assertThat(column).as("the declaration SqlBuilder gives for H2: CHARACTER LARGE OBJECT(2147483647)").containsEntry("DATA_TYPE", "CHARACTER LARGE OBJECT")
            .containsEntry("CHARACTER_MAXIMUM_LENGTH", 2_147_483_647L);
        jdbc.update("INSERT INTO long_t (id, body) VALUES (1, ?)", text);
        assertThat(jdbc.queryForObject("SELECT body FROM long_t WHERE id = 1", String.class)).isEqualTo(text);
        assertThat(jdbc.queryForObject("SELECT digest FROM " + Phase.EXPAND.table() + " WHERE version = 1", String.class).strip()).isEqualTo(sha256(sql));
    }

    // ---- S-D0109-15: encoding ----

    @Test
    @DisplayName("S-D0109-15: the encoding check passes on the embedded H2 database (the negative path needs a database in another encoding and is not tested here)")
    void theEncodingCheckPasses() throws SQLException {
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            SqlBuilder.Database h2 = SqlBuilder.ofProductName(connection.getMetaData().getDatabaseProductName());
            assertThatCode(() -> MigrationRunner.verifyEncoding(connection, h2)).doesNotThrowAnyException();
            // What the check judges, read from the real database: the characters it counts in the cast text, and the text itself.
            try (var statement = connection.prepareStatement(SqlBuilder.generateEncodingProbe(h2))) {
                statement.setString(1, "한글");
                statement.setString(2, "한글");
                try (var answer = statement.executeQuery()) {
                    assertThat(answer.next()).isTrue();
                    assertThat(answer.getInt(1)).as("characters counted").isEqualTo(2);
                    assertThat(answer.getString(2)).as("value given back").isEqualTo("한글");
                }
            }
        }
    }
}
