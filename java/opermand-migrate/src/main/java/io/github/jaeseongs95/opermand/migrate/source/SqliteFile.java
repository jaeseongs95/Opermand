package io.github.jaeseongs95.opermand.migrate.source;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import io.github.jaeseongs95.opermand.migrate.source.SourceUnusableException.Problem;

/**
 * A 2.x SQLite file opened for reading and nothing else. The file must be a regular file, not a link, and must have no journal or write-ahead file next to it: a journal
 * means a 2.x service wrote and stopped without finishing or is still writing, and such a file is never read (2.x refused it too, {@code LEGACY_NOT_QUIESCENT}). The connection
 * is read only at the driver level, so even a mistake in this module cannot change the file.
 */
final class SqliteFile implements AutoCloseable {

    private static final Set<String> SIDECARS = Set.of("-journal", "-wal", "-shm");

    private final Connection connection;
    private final String name;

    private SqliteFile(Connection connection, String name) {
        this.connection = connection;
        this.name = name;
    }

    static SqliteFile openReadOnly(Path file) {
        String name = file.getFileName().toString();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new SourceUnusableException(Problem.UNSAFE_FILE, name + " is not a regular file (a link or something else); it is not read");
        }
        for (String suffix : SIDECARS) {
            if (Files.exists(file.resolveSibling(name + suffix), LinkOption.NOFOLLOW_LINKS)) {
                throw new SourceUnusableException(Problem.NOT_QUIESCENT, name + " has a " + suffix + " file next to it: stop the 2.x service cleanly first, then run again");
            }
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + file.toAbsolutePath().toString().replace('\\', '/'));
        try {
            return new SqliteFile(dataSource.getConnection(), name);
        } catch (SQLException e) {
            throw new SourceUnusableException(Problem.UNREADABLE, name + " cannot be opened for reading (is the 2.x service still running?): " + e.getMessage(), e);
        }
    }

    Connection connection() {
        return connection;
    }

    String name() {
        return name;
    }

    /** The column names of the table, or an empty set when the table does not exist. */
    Set<String> columnsOf(String table) {
        Set<String> columns = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT name FROM pragma_table_info(?)")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new SourceUnusableException(Problem.UNREADABLE, name + ": the schema of " + table + " cannot be read: " + e.getMessage(), e);
        }
        return columns;
    }

    /** Stops the migration unless the table has the columns; a table with other columns is a schema this module does not know. */
    void requireColumns(String table, Set<String> required) {
        Set<String> columns = columnsOf(table);
        if (columns.isEmpty() || !columns.containsAll(required)) {
            throw new SourceUnusableException(Problem.UNSUPPORTED_SCHEMA, name + ": table " + table + " is missing or lacks one of " + required + "; this is not a 2.x journal this tool knows");
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new SourceUnusableException(Problem.UNREADABLE, name + " could not be closed: " + e.getMessage(), e);
        }
    }
}
