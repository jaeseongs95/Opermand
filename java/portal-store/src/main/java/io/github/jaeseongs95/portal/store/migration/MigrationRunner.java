package io.github.jaeseongs95.portal.store.migration;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.init.ScriptException;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;

import io.github.jaeseongs95.portal.store.migration.MigrationException.Reason;
import io.github.jaeseongs95.portal.store.sql.SqlBuilder;

/**
 * The migration runner: the scripts of the jar ({@code V<number>__<description>.sql}) applied in number order, once each, with plain JDBC and Spring's script reader
 * ({@link ResourceDatabasePopulator}, which splits a script at its semicolons and knows quoted text and comments). What it needs from the database is the SQL standard: a history
 * table of ANSI types, {@code CREATE SCHEMA}, and the type name the driver reports for a long text ({@link LongTextType}). The only knowledge of a DBMS is {@link ScriptDialect},
 * keyed by {@link SqlBuilder.Database}, and it is two spellings.
 *
 * <p><b>Why Flyway is not used.</b> The product runs on H2 by default and on the user's own JDBC driver (PostgreSQL, MariaDB, MySQL, Oracle, Db2, SQL Server); the differences of the
 * databases are absorbed by ANSI SQL and {@link SqlBuilder}. Flyway keeps a list of the databases it knows and, from version 10, ships the support of each database as a module of
 * its own ({@code flyway-database-postgresql}, {@code flyway-mysql} ...), so the product would carry a module for every database a user may choose, and a database without one
 * cannot be migrated: that ties the product to the vendors it lists. A runner of this size over the standard needs no such list. The same runner, with the same history table and
 * the same rules, is used by the other two products of the owner (common format, edition 2); this class follows the structure and the names of the AGS (Agent Governance Suite)
 * migration runner, so what differs from it is the common-format changes and the replaced AGS-only types, listed in {@code docs/migration/migration-runner.md}. Version 3.0.0 keeps
 * no compatibility with the 2.x data or with a Flyway history: there is no code that reads {@code flyway_schema_history}.
 *
 * <p><b>The history table</b> {@code portal_meta.<phase>_history} has a row for every <em>attempt</em> at a number, with the key {@code (version, attempt)}: its file, the SHA-256
 * of the file, when it was tried (a UTC {@code TIMESTAMP} from the injected clock) and whether it {@link Outcome#SUCCEEDED} or {@link Outcome#FAILED}. A script that was applied is
 * never read again, and a changed file is refused: the run stops with {@code MIGRATION_CHANGED} before it applies anything and adds no row, because the database was made by other
 * text than the jar holds. The numbers are consecutive from 1; a gap, a start above 1 or a number below the highest applied one stops the run before anything is applied. A script
 * that failed leaves its {@code FAILED} row and is tried again at the next start as the next attempt: no row is ever deleted and the only row that is changed is the one the
 * attempt itself wrote, from FAILED to SUCCEEDED, by the last statement of its transaction.
 *
 * <p><b>Two runners at once</b> do not apply a script twice and no runner waits by itself (no sleep, no poll, no retry): the first statement of an attempt writes its history row
 * (the claim, as FAILED) in the transaction that applies the script, and {@code (version, attempt)} is the primary key, so the second runner's insert waits in the database for the
 * first one's commit and then gets a {@link DuplicateKeyException}. The attempt of a script is taken from the same read of the history that decided which scripts are pending (the
 * highest attempt of the number there, plus one), not read again before the script is applied, so a runner whose history is out of date meets the same key and not the next one.
 * It reads the history once more: the number is SUCCEEDED with the same digest (the first runner applied it), so it goes on; otherwise the first runner failed or is still applying
 * it, and it stops with {@code MIGRATION_FAILED} (the next start tries again). Each script is one transaction; a database whose DDL is not
 * transactional (MariaDB, MySQL, and H2 for DDL) keeps what a script did before it failed and commits the claim with the script's first DDL statement, so a half-applied script is never
 * shown as SUCCEEDED: its row says FAILED until the last statement has run.
 *
 * <p><b>Who runs it.</b> The product has one database account ({@code spring.datasource.*}, no separate account for DDL), so the expand scripts ({@code db/expand}) are applied at every
 * start ({@link ExpandOnStart}); the contract scripts ({@code db/contract}, changes that remove) are applied only by the explicit call {@link Migrations#contract()}. The two
 * phases have their own folder and history table, so neither knows the other's applied scripts.
 */
final class MigrationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(MigrationRunner.class);

    /** What a history row says. The column holds the constant's name and a CHECK lists the two. */
    enum Outcome { SUCCEEDED, FAILED }

    /** The two kinds of migration: expand scripts run at every start, contract scripts by an explicit command. Each has its folder in the jar and its history table. */
    enum Phase {
        EXPAND("db/expand", "expand_history"), CONTRACT("db/contract", "contract_history");

        private final String location;
        private final String tableName;

        Phase(String location, String tableName) {
            this.location = location;
            this.tableName = tableName;
        }

        String location() {
            return location;
        }

        String tableName() {
            return tableName;
        }

        /** The history table, qualified by its schema. */
        String table() {
            return Migrations.SCHEMA + "." + tableName;
        }
    }

    private record HistoryRow(long version, int attempt, String script, String digest, Outcome outcome) {
    }

    private final Clock clock;
    private final DataSource dataSource;

    MigrationRunner(Clock clock, DataSource dataSource) {
        this.clock = clock;
        this.dataSource = dataSource;
    }

    /** Applies the pending scripts of the phase from the jar; the number applied by this call. */
    int run(Phase phase) {
        return run(phase, MigrationScript.load(phase.location()));
    }

    /** Applies the pending ones of these scripts (the jar's, or the ones a test gives); the number applied by this call. */
    int run(Phase phase, List<MigrationScript> scripts) {
        // The numbers are checked before the database is touched: nothing is applied, not even the history table made, for scripts that are not consecutive.
        requireConsecutive(scripts, phase);
        // The runner has its own connection of the product's one account, not a connection of a transaction.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // Text is UTF-8 from end to end, and a script may hold Korean text: a database in another encoding is refused here, before a script fails half way with an encoding error.
            verifyEncoding(connection);
            ScriptDialect dialect = ScriptDialect.of(SqlBuilder.ofProductName(connection.getMetaData().getDatabaseProductName()));
            ensureHistory(connection, phase, dialect);
            // The history is read once: it decides which scripts are pending and also the attempt of each, so that a peer that committed a number after this read is met as a duplicate key.
            List<HistoryRow> history = readHistory(connection, phase);
            List<MigrationScript> pending = pending(scripts, history, phase);
            connection.commit();
            String longText = LongTextType.choose(connection.getMetaData()).declaration();
            SQLExceptionTranslator translator = new SQLErrorCodeSQLExceptionTranslator(dataSource);
            int applied = 0;
            for (MigrationScript script : pending) {
                if (apply(connection, translator, phase, script, nextAttempt(history, script), longText, dialect)) {
                    applied++;
                }
            }
            return applied;
        } catch (SQLException failure) {
            throw new MigrationException(Reason.MIGRATION_FAILED, "The migration (" + phase.location() + ") could not run on the database.", failure);
        }
    }

    // ------------------------------------------------------------------------------------------------------------ encoding

    /** Two Korean syllables: two characters, six bytes in UTF-8. */
    private static final String ENCODING_PROBE = "한글";

    /**
     * Text is UTF-8 from end to end; a database in another encoding cannot hold it, and is refused before anything else. The check is standard SQL, not a setting of one DBMS: the
     * database is asked how many characters the probe has (TRIM: an H2 CHAR(10) is padded with blanks to its width, and the probe is the two syllables). A database of another single-byte encoding cannot take the text at all (the statement fails), and one that does not read it as
     * UTF-8 (PostgreSQL's SQL_ASCII) counts its bytes.
     */
    static void verifyEncoding(Connection connection) throws SQLException {
        long characters;
        try {
            characters = queryLong(connection, "SELECT CHAR_LENGTH(TRIM(CAST(? AS CHAR(10))))", ENCODING_PROBE);
        } catch (SQLException unstorable) {
            connection.rollback();
            throw new MigrationException(Reason.DB_ENCODING_MISMATCH, "The database cannot take UTF-8 text.", unstorable);
        }
        connection.rollback();
        if (characters != ENCODING_PROBE.length()) {
            throw new MigrationException(Reason.DB_ENCODING_MISMATCH, "The database does not read UTF-8 text: two characters count as " + characters + ".");
        }
    }

    // ------------------------------------------------------------------------------------------------------------ history

    /** Makes the schema and the history table when they are not there. */
    private void ensureHistory(Connection connection, Phase phase, ScriptDialect dialect) throws SQLException {
        if (!schemaExists(connection)) {
            create(connection, "CREATE SCHEMA " + Migrations.SCHEMA, () -> schemaExists(connection));
        }
        if (!tableExists(connection, phase)) {
            // The CHECK lists the two values with CASE and not with IN: H2 2.4.240 keeps the session that created a constraint with an IN list of constants, so the first insert after the
            // migration connection is closed would fail (see NoInListInChecksTest). CASE is standard SQL and means the same on every database.
            create(connection, dialect.translate("CREATE TABLE " + phase.table() + " (version BIGINT NOT NULL, attempt INTEGER NOT NULL, script VARCHAR(255) NOT NULL, digest CHAR(64) NOT NULL, "
                    + "applied_at TIMESTAMP NOT NULL, outcome VARCHAR(10) NOT NULL, PRIMARY KEY (version, attempt), "
                    + "CONSTRAINT " + phase.tableName() + "_outcome CHECK (CASE WHEN outcome = 'SUCCEEDED' THEN 1 WHEN outcome = 'FAILED' THEN 1 ELSE 0 END = 1))"),
                    () -> tableExists(connection, phase));
        }
    }

    /** Runs one DDL statement. A runner that started at the same moment may have made the object first, which fails this statement: then the object is there, and that is what was wanted. */
    private static void create(Connection connection, String ddl, ExistsCheck exists) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(ddl);
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            if (!exists.test()) {
                throw failure;
            }
            LOG.debug("The runner that came first made the object; this statement is not needed: {}", ddl, failure);
        }
    }

    @FunctionalInterface
    private interface ExistsCheck {
        boolean test() throws SQLException;
    }

    /**
     * Whether the schema is there, asked of the standard catalog ({@code information_schema}) and not of the driver's {@code getSchemas()}: a driver of a database whose schema is its
     * database (MariaDB, MySQL) lists no schema there. The names are compared in lower case, which is how the scripts write them and how a catalog that folds identifiers spells them
     * once the case is not the question.
     */
    private static boolean schemaExists(Connection connection) throws SQLException {
        return queryLong(connection, "SELECT COUNT(*) FROM information_schema.schemata WHERE LOWER(schema_name) = ?", Migrations.SCHEMA.toLowerCase(Locale.ROOT)) > 0;
    }

    private static boolean tableExists(Connection connection, Phase phase) throws SQLException {
        return queryLong(connection, "SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_schema) = ? AND LOWER(table_name) = ?",
                Migrations.SCHEMA.toLowerCase(Locale.ROOT), phase.tableName().toLowerCase(Locale.ROOT)) > 0;
    }

    private static List<HistoryRow> readHistory(Connection connection, Phase phase) throws SQLException {
        List<HistoryRow> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet found = statement.executeQuery("SELECT version, attempt, script, digest, outcome FROM " + phase.table() + " ORDER BY version, attempt")) {
            while (found.next()) {
                rows.add(new HistoryRow(found.getLong("version"), found.getInt("attempt"), found.getString("script"), found.getString("digest"), Outcome.valueOf(found.getString("outcome"))));
            }
        }
        return rows;
    }

    /** One number from a query with its parameters, on the runner's connection (plain JDBC: the runner keeps one connection and its transaction itself). */
    private static long queryLong(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet found = statement.executeQuery()) {
                found.next();
                return found.getLong(1);
            }
        }
    }

    private static int update(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            return statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, Object[] parameters) throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    /** The numbers of the scripts are 1, 2, 3 ... without a gap: a missing number or a start above 1 is a failure of the jar, found before anything is applied. */
    private static void requireConsecutive(List<MigrationScript> scripts, Phase phase) {
        long expected = 1;
        for (MigrationScript script : scripts.stream().sorted(Comparator.comparingLong(MigrationScript::version)).toList()) {
            if (script.version() != expected) {
                throw new MigrationException(Reason.MIGRATION_OUT_OF_ORDER,
                        "The scripts of " + phase.location() + " are not numbered consecutively from 1: " + script.file() + " comes where number " + expected + " is expected.");
            }
            expected++;
        }
    }

    /**
     * The scripts still to apply, after the history was checked against the jar: every script that was applied has to be in the jar with the digest it had, and no script of the jar
     * lies below the highest one applied (a number added behind the database's back). A row that says FAILED holds nothing: its number is tried again as the next attempt.
     */
    private static List<MigrationScript> pending(List<MigrationScript> scripts, List<HistoryRow> history, Phase phase) {
        Map<Long, MigrationScript> byVersion = scripts.stream().collect(Collectors.toMap(MigrationScript::version, script -> script));
        long highest = 0;
        Set<Long> done = history.stream().filter(row -> row.outcome() == Outcome.SUCCEEDED).map(HistoryRow::version).collect(Collectors.toSet());
        for (HistoryRow row : history) {
            if (row.outcome() != Outcome.SUCCEEDED) {
                continue;
            }
            MigrationScript script = byVersion.get(row.version());
            if (script == null) {
                throw new MigrationException(Reason.MIGRATION_MISSING, "The database has applied " + row.script() + " (" + phase.location() + "), which this version has no script for.");
            }
            if (!script.digest().equals(row.digest().strip())) {
                throw new MigrationException(Reason.MIGRATION_CHANGED,
                        "The script " + script.file() + " (" + phase.location() + ") is not the one that was applied: its digest differs from the history.");
            }
            highest = Math.max(highest, row.version());
        }
        List<MigrationScript> pending = scripts.stream().filter(script -> !done.contains(script.version())).sorted(Comparator.comparingLong(MigrationScript::version)).toList();
        for (MigrationScript script : pending) {
            if (script.version() < highest) {
                throw new MigrationException(Reason.MIGRATION_OUT_OF_ORDER,
                        "The script " + script.file() + " (" + phase.location() + ") has a number below the highest one applied, " + highest + ".");
            }
        }
        return pending;
    }

    // ------------------------------------------------------------------------------------------------------------ one script

    /**
     * Applies one script in its own transaction; false when another runner took the number first and applied it. The attempt is claimed first, as FAILED, and turned to SUCCEEDED by the
     * last statement of the transaction (the row of this attempt only). A database whose DDL is not transactional (MariaDB, MySQL, H2: each DDL statement commits what came before it, the
     * claim included) so never shows a half-applied script as SUCCEEDED: what it has committed of it says FAILED until the whole script has run.
     */
    private boolean apply(Connection connection, SQLExceptionTranslator translator, Phase phase, MigrationScript script, int attempt, String longText, ScriptDialect dialect)
            throws SQLException {
        try {
            record(connection, translator, phase, script, attempt, Outcome.FAILED);
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(false, false, StandardCharsets.UTF_8.name(),
                    new ByteArrayResource(script.text(longText, dialect).getBytes(StandardCharsets.UTF_8), script.file()));
            populator.populate(connection);
            update(connection, "UPDATE " + phase.table() + " SET outcome = ? WHERE version = ? AND attempt = ?", Outcome.SUCCEEDED.name(), script.version(), attempt);
            connection.commit();
            return true;
        } catch (DuplicateKeyException taken) {
            connection.rollback();
            LOG.debug("Another runner has taken the migration {} ({}), attempt {}.", script.file(), phase, attempt, taken);
            return settleAfterPeer(connection, phase, script);
        } catch (ScriptException failure) {
            connection.rollback();
            recordFailure(connection, translator, phase, script, attempt, failure);
            throw new MigrationException(Reason.MIGRATION_FAILED, "The migration " + script.file() + " did not apply (" + phase.location() + ").", failure);
        }
    }

    /**
     * The number of the next attempt at this script: one more than the highest attempt of the number in the history that decided which scripts are pending (1 when it has none). It is
     * not read again just before the script is applied: a peer that committed an attempt in between must be met as a duplicate key of the same {@code (version, attempt)}, which
     * {@link #settleAfterPeer} answers, and not be passed by taking the next attempt and applying the script a second time.
     */
    private static int nextAttempt(List<HistoryRow> history, MigrationScript script) {
        return history.stream().filter(row -> row.version() == script.version()).mapToInt(HistoryRow::attempt).max().orElse(0) + 1;
    }

    /**
     * The runner that got a duplicate key reads the history once more. The number is SUCCEEDED (the peer applied it): the digest is the one of this jar's script, and this runner goes on
     * (false: not applied by this call). Otherwise the peer failed, and the run stops; the next start tries again.
     */
    private static boolean settleAfterPeer(Connection connection, Phase phase, MigrationScript script) throws SQLException {
        List<HistoryRow> rows = readHistory(connection, phase);
        connection.rollback();
        for (HistoryRow row : rows) {
            if (row.version() == script.version() && row.outcome() == Outcome.SUCCEEDED) {
                if (!script.digest().equals(row.digest().strip())) {
                    throw new MigrationException(Reason.MIGRATION_CHANGED,
                            "The script " + script.file() + " (" + phase.location() + ") was applied by another runner with another text: its digest differs from the history.");
                }
                return false;
            }
        }
        throw new MigrationException(Reason.MIGRATION_FAILED, "Another runner took the migration " + script.file() + " (" + phase.location()
                + ") and it is not applied: that runner failed, or is still applying it. Start again after it has finished.");
    }

    /** Writes the row of an attempt. A row with that key already there is a {@link DuplicateKeyException} (Spring translates the driver's error by the database's own error codes). */
    private void record(Connection connection, SQLExceptionTranslator translator, Phase phase, MigrationScript script, int attempt, Outcome outcome) throws SQLException {
        String sql = "INSERT INTO " + phase.table() + " (version, attempt, script, digest, applied_at, outcome) VALUES (?, ?, ?, ?, ?, ?)";
        try {
            update(connection, sql, script.version(), attempt, script.file(), script.digest(), Timestamp.valueOf(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)), outcome.name());
        } catch (SQLException failure) {
            DataAccessException translated = translator.translate("record the attempt of " + script.file(), sql, failure);
            if (translated instanceof DuplicateKeyException duplicate) {
                throw duplicate;
            }
            throw failure;
        }
    }

    /**
     * The attempt of a script that failed stays in the history, in a transaction of its own (the script's was rolled back). The row is there already when the database committed
     * the claim with the script's first DDL statement (it says FAILED), or when another runner took the number; either way no row is added.
     */
    private void recordFailure(Connection connection, SQLExceptionTranslator translator, Phase phase, MigrationScript script, int attempt, ScriptException cause) throws SQLException {
        try {
            record(connection, translator, phase, script, attempt, Outcome.FAILED);
            connection.commit();
        } catch (DuplicateKeyException present) {
            connection.rollback();
            LOG.warn("The migration {} failed here and its attempt is in the history already (committed by the script's own DDL, or taken by another runner); no row is added.", script.file(), cause);
        }
    }
}
