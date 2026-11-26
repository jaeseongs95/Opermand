package io.github.jaeseongs95.portal.migrate.source;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

import io.github.jaeseongs95.portal.migrate.source.SourceUnusableException.Problem;

/**
 * A 2.x request journal: {@code command-automation.sqlite} or {@code agent-cli.sqlite}, table {@code requests (id, request_key, digest, body, ...)}. The older shape of the
 * command journal has no {@code request_key} column; its rows then have no key and the planner gives them the key 2.x gave them at import, {@code legacy:<file>:<id>}.
 * The policy table of the command journal and the remote status column of the agent CLI journal are not read (they are not operations; see the scope table of the guide).
 */
public final class RequestJournalSource implements LegacySource {

    private final Path file;

    public RequestJournalSource(Path file) {
        this.file = file;
    }

    @Override
    public String fileName() {
        return file.getFileName().toString();
    }

    @Override
    public void forEachRow(RowSink sink) {
        try (SqliteFile database = SqliteFile.openReadOnly(file)) {
            database.requireColumns("requests", Set.of("id", "body", "digest"));
            String keyColumn = database.columnsOf("requests").contains("request_key") ? "request_key" : "NULL";
            try (PreparedStatement statement = database.connection().prepareStatement("SELECT id, " + keyColumn + ", body FROM requests ORDER BY id");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    sink.accept(LegacyRow.read(fileName(), rows));
                }
            }
        } catch (SQLException e) {
            throw new SourceUnusableException(Problem.UNREADABLE, fileName() + " cannot be read: " + e.getMessage(), e);
        }
    }
}
