package io.github.jaeseongs95.portal.migrate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * A 2.x data folder made by the test, with the CREATE TABLE statements of 2.4.2 ({@code operation-store.mjs}, {@code command-state.mjs}, {@code agent-cli-state.mjs},
 * {@code maintenance-state.mjs}, {@code runtime-capacity-state.mjs}, {@code command-output.mjs}) and rows in the shape 2.4.2 wrote. The test decides every value; nothing is
 * computed by the code under test.
 */
final class LegacyFixture {

    private static final String OPERATIONS_DDL = """
        CREATE TABLE operation_schema (version INTEGER NOT NULL);
        INSERT INTO operation_schema VALUES (1);
        CREATE TABLE operations (id TEXT PRIMARY KEY, request_key TEXT UNIQUE NOT NULL,
          digest TEXT NOT NULL, contract TEXT NOT NULL, version INTEGER NOT NULL, state TEXT NOT NULL,
          target_id TEXT NOT NULL, target_revision TEXT NOT NULL, conflict_key TEXT, ready_sequence INTEGER, body TEXT NOT NULL);
        CREATE INDEX operations_ready ON operations(state,ready_sequence);
        CREATE INDEX operations_state_identity ON operations(state,id);
        CREATE TABLE operation_events (sequence INTEGER PRIMARY KEY, operation_id TEXT NOT NULL,
          version INTEGER NOT NULL, state TEXT NOT NULL, at INTEGER NOT NULL, UNIQUE(operation_id,version));
        CREATE TABLE operation_legacy_budget (operation_id TEXT PRIMARY KEY, source TEXT NOT NULL,
          original_bytes INTEGER NOT NULL, reserved_bytes INTEGER NOT NULL);
        """;
    private static final String COMMAND_JOURNAL_DDL = """
        CREATE TABLE policy (id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL, body TEXT NOT NULL);
        INSERT INTO policy VALUES (1, 4, '{"targets":[]}');
        CREATE TABLE requests (id TEXT PRIMARY KEY, request_key TEXT UNIQUE, digest TEXT NOT NULL, body TEXT NOT NULL);
        """;
    private static final String AGENT_CLI_DDL = """
        CREATE TABLE requests (id TEXT PRIMARY KEY, request_key TEXT UNIQUE NOT NULL, digest TEXT NOT NULL, body TEXT NOT NULL, remote_status TEXT);
        """;

    private final Path folder;

    LegacyFixture(Path folder) {
        this.folder = folder;
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    Path folder() {
        return folder;
    }

    /** {@code operations.sqlite} with the jobs as rows of the formal journal. */
    LegacyFixture operations(List<LegacyJob> jobs) {
        try (Connection connection = open("operations.sqlite")) {
            execute(connection, OPERATIONS_DDL);
            connection.setAutoCommit(false);
            for (LegacyJob job : jobs) {
                try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO operations (id, request_key, digest, contract, version, state, target_id, target_revision, body) VALUES (?,?,?,?,?,?,?,?,?)")) {
                    insert.setString(1, job.id());
                    insert.setString(2, job.requestKey());
                    insert.setString(3, "d".repeat(64));
                    insert.setString(4, "{}");
                    insert.setInt(5, 2);
                    insert.setString(6, job.state());
                    insert.setString(7, job.targetId());
                    insert.setString(8, job.targetRevision());
                    insert.setString(9, job.operationBody());
                    insert.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return this;
    }

    /** {@code command-automation.sqlite}: the policy table and the jobs as rows of the request journal. */
    LegacyFixture commandJournal(List<LegacyJob> jobs) {
        try (Connection connection = open("command-automation.sqlite")) {
            execute(connection, COMMAND_JOURNAL_DDL);
            insertRequests(connection, jobs, true);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return this;
    }

    /** {@code agent-cli.sqlite}: the jobs as rows of the agent CLI journal. */
    LegacyFixture agentCliJournal(List<LegacyJob> jobs) {
        try (Connection connection = open("agent-cli.sqlite")) {
            execute(connection, AGENT_CLI_DDL);
            insertRequests(connection, jobs, true);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return this;
    }

    /** The files 2.x kept next to the journals and that are not moved. */
    LegacyFixture otherFiles() {
        try (Connection connection = open("maintenance.sqlite")) {
            execute(connection, "CREATE TABLE reservation (id INTEGER PRIMARY KEY CHECK(id=1), owner_hash TEXT NOT NULL, baseline TEXT);"
                + "CREATE TABLE last_release (id INTEGER PRIMARY KEY CHECK(id=1), owner_hash TEXT NOT NULL);");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        try (Connection connection = open("capacity.sqlite")) {
            execute(connection, "CREATE TABLE runtime_capacity (id INTEGER PRIMARY KEY CHECK(id=1), body TEXT NOT NULL);");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        try {
            Files.createDirectories(folder.resolve("command-outputs"));
            Files.write(folder.resolve("command-outputs").resolve("a".repeat(48) + ".out"), "output".getBytes(StandardCharsets.UTF_8));
            Files.write(folder.resolve("b".repeat(48) + ".part"), new byte[] {1, 2, 3});
            Files.write(folder.resolve("surprise.txt"), "unknown".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return this;
    }

    /** A request journal row whose body bytes are given as they are (for a body that is not well-formed UTF-8). */
    LegacyFixture rawRequestRow(String file, String id, byte[] body) {
        try (Connection connection = open(file)) {
            execute(connection, AGENT_CLI_DDL);
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO requests (id, request_key, digest, body) VALUES (?,?,?,?)")) {
                insert.setString(1, id);
                insert.setString(2, "agent-cli:" + id);
                insert.setString(3, "d".repeat(64));
                insert.setBytes(4, body);
                insert.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return this;
    }

    private void insertRequests(Connection connection, List<LegacyJob> jobs, boolean withKey) throws SQLException {
        for (LegacyJob job : jobs) {
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO requests (id, request_key, digest, body) VALUES (?,?,?,?)")) {
                insert.setString(1, job.id());
                insert.setString(2, withKey ? job.explicitKey() : null);
                insert.setString(3, "d".repeat(64));
                insert.setString(4, job.journalBody());
                insert.executeUpdate();
            }
        }
    }

    private Connection open(String name) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + folder.resolve(name).toAbsolutePath().toString().replace('\\', '/'));
    }

    private void execute(Connection connection, String script) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : script.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
    }
}
