package io.github.jaeseongs95.portal.migrate.source;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.jaeseongs95.portal.migrate.source.SourceUnusableException.Problem;

/**
 * The 2.x {@code operations.sqlite}: table {@code operations (id, request_key, body, ...)}, schema version 1 in {@code operation_schema}. It is the formal journal of every
 * operation of 2.4; the body is the whole job. The events table of 2.x is not read: 3.0 writes its own event rows for the transitions it stores.
 */
public final class OperationsTableSource implements LegacySource {

    private final Path file;

    public OperationsTableSource(Path file) {
        this.file = file;
    }

    @Override
    public String fileName() {
        return file.getFileName().toString();
    }

    @Override
    public void forEachRow(RowSink sink) {
        try (SqliteFile database = SqliteFile.openReadOnly(file)) {
            database.requireColumns("operation_schema", Set.of("version"));
            database.requireColumns("operations", Set.of("id", "request_key", "body"));
            requireVersionOne(database);
            try (PreparedStatement statement = database.connection().prepareStatement("SELECT id, request_key, body FROM operations ORDER BY id");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    sink.accept(LegacyRow.read(fileName(), rows));
                }
            }
        } catch (SQLException e) {
            throw new SourceUnusableException(Problem.UNREADABLE, fileName() + " cannot be read: " + e.getMessage(), e);
        }
    }

    private void requireVersionOne(SqliteFile database) throws SQLException {
        List<Long> versions = new ArrayList<>();
        try (PreparedStatement statement = database.connection().prepareStatement("SELECT version FROM operation_schema");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                versions.add(rows.getLong(1));
            }
        }
        if (versions.size() != 1 || versions.getFirst() != 1L) {
            throw new SourceUnusableException(Problem.UNSUPPORTED_SCHEMA, fileName() + ": operation_schema is " + versions + ", this tool reads version 1 only");
        }
    }
}
