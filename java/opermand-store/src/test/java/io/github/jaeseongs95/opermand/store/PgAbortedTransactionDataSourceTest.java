package io.github.jaeseongs95.opermand.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Verifies that {@link PgAbortedTransactionDataSource} rejects follow-up SQL inside an aborted transaction with PostgreSQL SQLState {@code 25P02} until rollback.
 */
class PgAbortedTransactionDataSourceTest {

    private static DataSource freshProxyDataSource(String name) {
        DriverManagerDataSource raw = new DriverManagerDataSource("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
        return PgAbortedTransactionDataSource.wrap(raw);
    }

    @Test
    @DisplayName("Any SQL statement executed after a SQLException in a non-autocommit transaction fails with SQLState 25P02 until rollback")
    void rejectsFollowUpSqlUntilRollback() throws SQLException {
        DataSource ds = freshProxyDataSource("pg_25p02_rollback");
        try (Connection conn = ds.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE pg_probe (id INT PRIMARY KEY)");
            }
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("INSERT INTO pg_probe (id) VALUES (1)");
                assertThatThrownBy(() -> st.executeUpdate("INSERT INTO pg_probe (id) VALUES (1)")).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> st.executeQuery("SELECT COUNT(*) FROM pg_probe"))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("25P02"));
            }
            assertThatThrownBy(() -> conn.prepareStatement("SELECT 1"))
                .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("25P02"));

            conn.rollback();

            try (Statement st = conn.createStatement()) {
                assertThat(st.executeUpdate("INSERT INTO pg_probe (id) VALUES (2)")).isEqualTo(1);
            }
            conn.commit();
        }
    }

    @Test
    @DisplayName("Rolling back to a savepoint clears the aborted state for the surrounding test transaction")
    void savepointRollbackClearsAbortedState() throws SQLException {
        DataSource ds = freshProxyDataSource("pg_25p02_savepoint");
        try (Connection conn = ds.getConnection()) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE pg_sp_probe (id INT PRIMARY KEY)");
            }
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("INSERT INTO pg_sp_probe (id) VALUES (1)");
                Savepoint sp = conn.setSavepoint("sp1");
                assertThatThrownBy(() -> st.executeUpdate("INSERT INTO pg_sp_probe (id) VALUES (1)")).isInstanceOf(SQLException.class);
                conn.rollback(sp);
                assertThat(st.executeUpdate("INSERT INTO pg_sp_probe (id) VALUES (2)")).isEqualTo(1);
            }
            conn.commit();
        }
    }
}
