package io.github.jaeseongs95.opermand.store.migration;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import io.github.jaeseongs95.opermand.store.migration.MigrationRunner.Phase;

/**
 * What the migration tests share: a fresh embedded H2 database without a compatibility mode (every call makes its own named in-memory database, so no test sees another's
 * tables), a fixed {@link Clock} that stamps the history, and small readers of the history table. {@code LOCK_TIMEOUT} is the deadline of a lock wait of the concurrency tests
 * (H2 setting, https://h2database.com/html/commands.html#set_lock_timeout): a wait that is not released within it fails with an error and so fails the test.
 */
final class MigrationTestSupport {

    static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private MigrationTestSupport() {
    }

    static String freshUrl() {
        return "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000";
    }

    static DataSource freshDatabase() {
        return new DriverManagerDataSource(freshUrl());
    }

    static MigrationScript script(long version, String sql) {
        return MigrationScript.of(version, "V" + version + "__test.sql", sql.getBytes(StandardCharsets.UTF_8));
    }

    /** The history rows as {@code version/attempt/OUTCOME}, in key order. */
    static List<String> history(JdbcTemplate jdbc, Phase phase) {
        return jdbc.query("SELECT version, attempt, outcome FROM " + phase.table() + " ORDER BY version, attempt",
            (rows, number) -> rows.getLong("version") + "/" + rows.getInt("attempt") + "/" + rows.getString("outcome"));
    }

    static long count(JdbcTemplate jdbc, String sql, Object... parameters) {
        Long value = jdbc.queryForObject(sql, Long.class, parameters);
        return value == null ? -1 : value;
    }

    /** Whether the table exists in the schema (names as H2 folds them). */
    static boolean tableExists(JdbcTemplate jdbc, String schema, String table) {
        return count(jdbc, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?", schema.toUpperCase(Locale.ROOT),
            table.toUpperCase(Locale.ROOT)) > 0;
    }

    static boolean schemaExists(JdbcTemplate jdbc, String schema) {
        return count(jdbc, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?", schema.toUpperCase(Locale.ROOT)) > 0;
    }
}
