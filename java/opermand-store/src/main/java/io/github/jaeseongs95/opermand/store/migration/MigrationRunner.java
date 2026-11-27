package io.github.jaeseongs95.opermand.store.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;

import io.github.jaeseongs95.opermand.store.migration.MigrationException.Reason;
import io.github.jaeseongs95.opermand.store.sql.SqlBuilder;

/**
 * The migration runner: the scripts of the jar ({@code V<number>__<description>.sql}) applied in number order, once each, with plain JDBC and Spring's script reader
 * ({@link ResourceDatabasePopulator}, which splits a script at its semicolons and knows quoted text and comments). What it needs from the database is the SQL standard: a history
 * table of ANSI types, {@code CREATE SCHEMA}, and the declaration of a long text column, which the dialect of {@link SqlBuilder} gives for the product of the connection
 * ({@link SqlBuilder#generateLongText}). The runner has no branch on the DBMS of its own: the history table is plain ANSI SQL and a script is changed in one place only,
 * {@code ${long_text}}.
 *
 * <p><b>Why Flyway is not used.</b> The product runs on H2 by default and on the user's own JDBC driver (PostgreSQL, MariaDB, MySQL, Oracle, Db2, SQL Server); the differences of the
 * databases are absorbed by ANSI SQL and {@link SqlBuilder}. Flyway keeps a list of the databases it knows and, from version 10, ships the support of each database as a module of
 * its own ({@code flyway-database-postgresql}, {@code flyway-mysql} ...), so the product would carry a module for every database a user may choose, and a database without one
 * cannot be migrated: that ties the product to the vendors it lists. A runner of this size over the standard needs no such list. The same runner, with the same history table and
 * the same rules, is used by the other two products of the owner (common format, edition 4); this class follows the structure and the names of the AGS (Agent Governance Suite)
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

    /** What a history row says. The column holds the constant's name; it has no CHECK (only the runner writes it) and the value is checked against this enum when it is read. */
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

    private static final Pattern CREATE_TABLE = Pattern.compile("^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([A-Za-z0-9_.\"]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_INDEX = Pattern.compile("^\\s*CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([A-Za-z0-9_.\"]+)", Pattern.CASE_INSENSITIVE);

    private enum ObjectKind { TABLE, INDEX }

    private record CreatedObject(ObjectKind kind, String name) {
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
            // The product name is read once, from this connection: it picks the dialect of SqlBuilder (the encoding probe, the long text declaration), and it builds the error translator
            // (a translator made from the data source would open a connection of its own for the same name).
            String productName = connection.getMetaData().getDatabaseProductName();
            SqlBuilder.Database database = SqlBuilder.ofProductName(productName);
            // Text is UTF-8 from end to end, and a script may hold Korean text: a database in another encoding is refused here, before a script fails half way with an encoding error.
            verifyEncoding(connection, database);
            ensureHistory(connection, database, phase);
            // The history is read once: it decides which scripts are pending and also the attempt of each, so that a peer that committed a number after this read is met as a duplicate key.
            List<HistoryRow> history = readHistory(connection, phase);
            List<MigrationScript> pending = pending(scripts, history, phase);
            connection.commit();
            String longText = SqlBuilder.generateLongText(database);
            SQLExceptionTranslator translator = new SQLErrorCodeSQLExceptionTranslator(productName);
            int applied = 0;
            for (MigrationScript script : pending) {
                boolean hadPriorFailure = history.stream().anyMatch(row -> row.version() == script.version() && row.outcome() == Outcome.FAILED);
                if (apply(connection, database, translator, phase, script, nextAttempt(history, script), hadPriorFailure, longText)) {
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
     * Text is UTF-8 from end to end; a database in another encoding cannot hold it, and is refused before anything else. The check is a query, not a setting of one DBMS (the
     * dialect's spelling of it, {@link SqlBuilder#generateEncodingProbe}): the database is asked how many characters the probe has and what the probe is after it was cast to the
     * schema's character type. A database of another single-byte encoding cannot take the text at all (the statement fails); one that does not read it as UTF-8 (PostgreSQL's
     * SQL_ASCII) counts its bytes; a type whose code page has no such text gives back another value.
     */
    static void verifyEncoding(Connection connection, SqlBuilder.Database database) throws SQLException {
        EncodingProbe answer;
        try (PreparedStatement statement = connection.prepareStatement(SqlBuilder.generateEncodingProbe(database))) {
            statement.setString(1, ENCODING_PROBE);
            statement.setString(2, ENCODING_PROBE);
            try (ResultSet found = statement.executeQuery()) {
                found.next();
                answer = new EncodingProbe(found.getInt(1), found.getString(2));
            }
        } catch (SQLException unstorable) {
            connection.rollback();
            throw new MigrationException(Reason.DB_ENCODING_MISMATCH, "The database cannot take UTF-8 text.", unstorable);
        }
        connection.rollback();
        judgeEncoding(answer);
    }

    /** What the database answered to the probe: the characters it counts in the text and the text it gives back. */
    record EncodingProbe(int characters, String value) {
    }

    /** Both must be the text's own: a count of bytes is a database that does not read UTF-8, and another value (such as {@code ??}) is a type whose code page has no such text. */
    static void judgeEncoding(EncodingProbe answer) {
        if (answer.characters() != ENCODING_PROBE.length()) {
            throw new MigrationException(Reason.DB_ENCODING_MISMATCH, "The database does not read UTF-8 text: two characters count as " + answer.characters() + ".");
        }
        if (!ENCODING_PROBE.equals(answer.value())) {
            throw new MigrationException(Reason.DB_ENCODING_MISMATCH, "The database's character type does not keep UTF-8 text: it gives back '" + answer.value() + "'.");
        }
    }

    // ------------------------------------------------------------------------------------------------------------ history

    /** Makes the schema and the history table when they are not there. */
    private void ensureHistory(Connection connection, SqlBuilder.Database database, Phase phase) throws SQLException {
        if (!schemaExists(connection, database)) {
            create(connection, "CREATE SCHEMA " + Migrations.SCHEMA, () -> schemaExists(connection, database));
        }
        if (!tableExists(connection, database, phase)) {
            String timestampType = SqlBuilder.generateHistoryTimestampType(database);
            // No CHECK on outcome (common format edition 4): only the runner writes the table, and readHistory checks the value against the enum.
            create(connection, "CREATE TABLE " + phase.table() + " (version BIGINT NOT NULL, attempt INTEGER NOT NULL, script VARCHAR(255) NOT NULL, digest CHAR(64) NOT NULL, "
                    + "applied_at " + timestampType + " NOT NULL, outcome VARCHAR(10) NOT NULL, PRIMARY KEY (version, attempt))",
                    () -> tableExists(connection, database, phase));
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

    private static boolean schemaExists(Connection connection, SqlBuilder.Database database) throws SQLException {
        return queryLong(connection, SqlBuilder.generateSchemaExistsQuery(database), Migrations.SCHEMA.toLowerCase(Locale.ROOT)) > 0;
    }

    private static boolean tableExists(Connection connection, SqlBuilder.Database database, Phase phase) throws SQLException {
        return queryLong(connection, SqlBuilder.generateTableExistsQuery(database),
                Migrations.SCHEMA.toLowerCase(Locale.ROOT), phase.tableName().toLowerCase(Locale.ROOT)) > 0;
    }

    private static boolean currentSchemaTableExists(Connection connection, SqlBuilder.Database database, String tableName) throws SQLException {
        String bare = tableName.replace("\"", "");
        int dot = bare.lastIndexOf('.');
        if (dot >= 0) {
            bare = bare.substring(dot + 1);
        }
        return queryLong(connection, SqlBuilder.generateCurrentSchemaTableExistsQuery(database), bare.toLowerCase(Locale.ROOT)) > 0;
    }

    private static List<HistoryRow> readHistory(Connection connection, Phase phase) throws SQLException {
        List<HistoryRow> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet found = statement.executeQuery("SELECT version, attempt, script, digest, outcome FROM " + phase.table() + " ORDER BY version, attempt")) {
            while (found.next()) {
                rows.add(new HistoryRow(found.getLong("version"), found.getInt("attempt"), found.getString("script"), found.getString("digest"),
                        outcome(phase, found.getLong("version"), found.getInt("attempt"), found.getString("outcome"))));
            }
        }
        return rows;
    }

    /** The outcome of a history row, checked against the enum when it is read (the table has no CHECK). Another value means the history was not made by this runner. */
    private static Outcome outcome(Phase phase, long version, int attempt, String value) {
        for (Outcome outcome : Outcome.values()) {
            if (outcome.name().equals(value)) {
                return outcome;
            }
        }
        throw new MigrationException(Reason.MIGRATION_FAILED, "The history of " + phase.location() + " has the outcome '" + value + "' for number " + version + ", attempt " + attempt
                + ", which is neither SUCCEEDED nor FAILED.");
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

    private boolean apply(Connection connection, SqlBuilder.Database database, SQLExceptionTranslator translator, Phase phase, MigrationScript script, int attempt,
                          boolean hadPriorFailure, String longText) throws SQLException {
        boolean transactionalDdl = SqlBuilder.supportsTransactionalDdl(database);
        List<CreatedObject> createdObjects = new ArrayList<>();
        try {
            record(connection, translator, phase, script, attempt, Outcome.FAILED);
            List<String> statements = splitStatements(script.text(longText));
            for (String statement : statements) {
                String trimmed = statement.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                CreatedObject declared = parseCreatedObject(trimmed);
                if (!transactionalDdl && hadPriorFailure && declared != null && declared.kind() == ObjectKind.TABLE
                        && currentSchemaTableExists(connection, database, declared.name())) {
                    LOG.warn("Resuming migration {} attempt {}: table {} already exists from a prior failed attempt; skipping statement.",
                        script.file(), attempt, declared.name());
                    continue;
                }
                try (Statement sql = connection.createStatement()) {
                    sql.execute(trimmed);
                }
                if (!transactionalDdl && declared != null) {
                    createdObjects.add(declared);
                }
            }
            update(connection, "UPDATE " + phase.table() + " SET outcome = ?, applied_at = applied_at WHERE version = ? AND attempt = ?",
                Outcome.SUCCEEDED.name(), script.version(), attempt);
            connection.commit();
            return true;
        } catch (DuplicateKeyException taken) {
            connection.rollback();
            LOG.debug("Another runner has taken the migration {} ({}), attempt {}.", script.file(), phase, attempt, taken);
            return settleAfterPeer(connection, phase, script);
        } catch (SQLException failure) {
            failAndRecord(connection, translator, phase, script, attempt, createdObjects, failure);
            throw new MigrationException(Reason.MIGRATION_FAILED, "The migration " + script.file() + " did not apply (" + phase.location() + ").", failure);
        }
    }

    /** Splits a SQL script on semicolons while respecting single-quoted literals, double-quoted identifiers, {@code --} line comments, and block comments. */
    private static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int length = script.length();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        int i = 0;
        while (i < length) {
            char ch = script.charAt(i);
            if (inSingleQuote) {
                current.append(ch);
                if (ch == '\'') {
                    if (i + 1 < length && script.charAt(i + 1) == '\'') {
                        current.append('\'');
                        i += 2;
                        continue;
                    }
                    inSingleQuote = false;
                }
                i++;
                continue;
            }
            if (inDoubleQuote) {
                current.append(ch);
                if (ch == '"') {
                    if (i + 1 < length && script.charAt(i + 1) == '"') {
                        current.append('"');
                        i += 2;
                        continue;
                    }
                    inDoubleQuote = false;
                }
                i++;
                continue;
            }
            if (ch == '-' && i + 1 < length && script.charAt(i + 1) == '-') {
                i += 2;
                while (i < length && script.charAt(i) != '\n') {
                    i++;
                }
                if (i < length) {
                    current.append('\n');
                    i++;
                }
                continue;
            }
            if (ch == '/' && i + 1 < length && script.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < length && !(script.charAt(i) == '*' && script.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(length, i + 2);
                current.append(' ');
                continue;
            }
            if (ch == '\'') {
                inSingleQuote = true;
                current.append(ch);
                i++;
                continue;
            }
            if (ch == '"') {
                inDoubleQuote = true;
                current.append(ch);
                i++;
                continue;
            }
            if (ch == ';') {
                String trimmed = current.toString().trim();
                if (!trimmed.isEmpty()) {
                    statements.add(trimmed);
                }
                current.setLength(0);
                i++;
                continue;
            }
            current.append(ch);
            i++;
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }

    private static CreatedObject parseCreatedObject(String statement) {
        Matcher table = CREATE_TABLE.matcher(statement);
        if (table.find()) {
            return new CreatedObject(ObjectKind.TABLE, table.group(1));
        }
        Matcher index = CREATE_INDEX.matcher(statement);
        if (index.find()) {
            return new CreatedObject(ObjectKind.INDEX, index.group(1));
        }
        return null;
    }

    private void failAndRecord(Connection connection, SQLExceptionTranslator translator, Phase phase, MigrationScript script, int attempt,
                               List<CreatedObject> createdObjects, Exception cause) {
        try {
            connection.rollback();
            compensateNonTransactionalDdl(connection, script, createdObjects, cause);
            recordFailure(connection, translator, phase, script, attempt, cause);
        } catch (SQLException recording) {
            cause.addSuppressed(recording);
        }
    }

    /** Drops objects committed by non-transactional DDL earlier in the same failed attempt, in reverse creation order (defect D-0141). */
    private static void compensateNonTransactionalDdl(Connection connection, MigrationScript script, List<CreatedObject> createdObjects, Exception cause) {
        for (int index = createdObjects.size() - 1; index >= 0; index--) {
            CreatedObject created = createdObjects.get(index);
            String dropSql = created.kind() == ObjectKind.INDEX ? "DROP INDEX " + created.name() : "DROP TABLE " + created.name();
            try (Statement statement = connection.createStatement()) {
                statement.execute(dropSql);
                connection.commit();
            } catch (SQLException cleanupFailure) {
                LOG.warn("Compensating DDL '{}' failed after migration {} error.", dropSql, script.file(), cleanupFailure);
                cause.addSuppressed(cleanupFailure);
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    cause.addSuppressed(rollbackFailure);
                }
            }
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
            update(connection, sql, script.version(), attempt, script.file(), script.digest(), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), outcome.name());
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
    private void recordFailure(Connection connection, SQLExceptionTranslator translator, Phase phase, MigrationScript script, int attempt, Exception cause) throws SQLException {
        try {
            record(connection, translator, phase, script, attempt, Outcome.FAILED);
            connection.commit();
        } catch (DuplicateKeyException present) {
            connection.rollback();
            LOG.warn("The migration {} failed here and its attempt is in the history already (committed by the script's own DDL, or taken by another runner); no row is added.", script.file(), cause);
        }
    }
}
