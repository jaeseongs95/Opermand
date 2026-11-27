package io.github.jaeseongs95.opermand.store.migration;

import static io.github.jaeseongs95.opermand.store.migration.MigrationTestSupport.CLOCK;
import static io.github.jaeseongs95.opermand.store.migration.MigrationTestSupport.freshDatabase;
import static io.github.jaeseongs95.opermand.store.migration.MigrationTestSupport.script;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import io.github.jaeseongs95.opermand.store.migration.MigrationRunner.Phase;

/**
 * S-D0109-29: a run uses the one connection it opened. An error translator made from the data source ({@code new SQLErrorCodeSQLExceptionTranslator(dataSource)}) opens a connection
 * of its own to read the product name, which the runner has already read from its connection; the count of {@code getConnection()} shows whether that is so.
 */
class MigrationConnectionTest {

    @Test
    @DisplayName("S-D0109-29: a run that applies two scripts calls getConnection() once")
    void aRunOpensOneConnection() {
        AtomicInteger opened = new AtomicInteger();
        DataSource counting = new DelegatingDataSource(freshDatabase()) {
            @Override
            public Connection getConnection() throws SQLException {
                opened.incrementAndGet();
                return super.getConnection();
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                opened.incrementAndGet();
                return super.getConnection(username, password);
            }
        };

        int applied = new MigrationRunner(CLOCK, counting).run(Phase.EXPAND, List.of(script(1, "CREATE TABLE t1 (id INTEGER NOT NULL)"), script(2, "INSERT INTO t1 (id) VALUES (1)")));

        assertThat(applied).isEqualTo(2);
        assertThat(opened.get()).as("connections opened by the run").isEqualTo(1);
    }
}
