package io.github.jaeseongs95.opermand.store.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaxxer.hikari.HikariDataSource;

import io.github.jaeseongs95.opermand.store.sql.DatabaseProductException.Reason;

/**
 * S-SQLB-03 to S-SQLB-06 (docs/migration/sqlbuilder-cases.md). Ported from AGS {@code SqlBuilderConnectionTest} (AGS, Agent Governance Suite, as of commit 1ad1b8e7), plus the
 * failed probe (S-SQLB-06). The data source delegates to a real in-memory H2 pool and only observes or, for the two refusal cases, disturbs the metadata read.
 */
class SqlBuilderConnectionTest {

    @Test
    @DisplayName("S-SQLB-03: eight threads at once resolve the actual H2 metadata once")
    void concurrentCallsResolveActualH2MetadataOnce() throws Exception {
        try (HikariDataSource pool = pool(8); var executor = Executors.newFixedThreadPool(8)) {
            CountingDataSource source = new CountingDataSource(pool, Probe.REAL);
            SqlBuilder builder = new SqlBuilder(source);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return builder.generateLimit("?");
                }));
            }
            start.countDown();
            for (Future<String> result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo("FETCH FIRST ? ROWS ONLY");
            }
            assertThat(source.metadataCalls.get()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("S-SQLB-04: a transaction that holds the only pool connection resolves the product while another thread waits")
    void transactionHoldingOnlyConnectionCanResolveWhileAnotherThreadWaits() throws Exception {
        try (HikariDataSource pool = pool(1); var executor = Executors.newSingleThreadExecutor()) {
            CountingDataSource source = new CountingDataSource(pool, Probe.REAL);
            SqlBuilder builder = new SqlBuilder(source);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            var waitingRef = new AtomicReference<Future<String>>();
            tx.executeWithoutResult(status -> {
                var waiting = executor.submit(() -> builder.generateLimit("?"));
                waitingRef.set(waiting);
                try {
                    assertThat(source.secondAcquisition.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(builder.generateLimit("?")).isEqualTo("FETCH FIRST ? ROWS ONLY");
                    assertThat(waiting.isDone()).isFalse();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            });
            assertThat(waitingRef.get().get(10, TimeUnit.SECONDS)).isEqualTo("FETCH FIRST ? ROWS ONLY");
            assertThat(source.metadataCalls.get()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("S-SQLB-05: a product with no SQL is refused with UNSUPPORTED_DATABASE_PRODUCT and the refusal is not cached")
    void unsupportedProductDoesNotCacheAResult() {
        try (HikariDataSource pool = pool(1)) {
            CountingDataSource source = new CountingDataSource(pool, Probe.UNSUPPORTED_PRODUCT);
            SqlBuilder builder = new SqlBuilder(source);
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> builder.generateLimit("?")).isInstanceOfSatisfying(DatabaseProductException.class,
                        failure -> {
                            assertThat(failure.reason()).isEqualTo(Reason.UNSUPPORTED_DATABASE_PRODUCT);
                            assertThat(failure.getMessage()).startsWith("UNSUPPORTED_DATABASE_PRODUCT: ");
                        });
            }
            assertThat(source.metadataCalls.get()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("S-SQLB-06: a metadata read that fails is refused with STORE_UNREACHABLE and the SQLException as cause, and the next call probes again")
    void failedProbeIsRefusedAndNotCached() {
        try (HikariDataSource pool = pool(1)) {
            CountingDataSource source = new CountingDataSource(pool, Probe.FAILS_ONCE);
            SqlBuilder builder = new SqlBuilder(source);
            assertThatThrownBy(() -> builder.generateLimit("?")).isInstanceOfSatisfying(DatabaseProductException.class, failure -> {
                assertThat(failure.reason()).isEqualTo(Reason.STORE_UNREACHABLE);
                assertThat(failure.getMessage()).startsWith("STORE_UNREACHABLE: ");
                assertThat(failure).hasCauseInstanceOf(SQLException.class);
            });
            assertThat(builder.generateLimit("?")).isEqualTo("FETCH FIRST ? ROWS ONLY");
            assertThat(source.metadataCalls.get()).isEqualTo(2);
        }
    }

    private static HikariDataSource pool(int size) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl("jdbc:h2:mem:sqlBuilderConnections");
        pool.setUsername("sa");
        pool.setMaximumPoolSize(size);
        return pool;
    }

    /** What the data source does to the metadata read of the real H2 connection. */
    private enum Probe {
        /** Nothing: it is observed only. */
        REAL,
        /** Only the product name is replaced. */
        UNSUPPORTED_PRODUCT,
        /** The first read throws an SQLException; later reads are real. */
        FAILS_ONCE
    }

    /** Delegates to real H2 and observes metadata; the refusal cases disturb only the metadata read. */
    private static final class CountingDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final Probe probe;
        private final AtomicInteger acquisitions = new AtomicInteger();
        private final AtomicInteger metadataCalls = new AtomicInteger();
        private final CountDownLatch secondAcquisition = new CountDownLatch(1);

        CountingDataSource(DataSource delegate, Probe probe) {
            this.delegate = delegate;
            this.probe = probe;
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (acquisitions.incrementAndGet() == 2) {
                secondAcquisition.countDown();
            }
            Connection connection = delegate.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                try {
                    if (method.getName().equals("getMetaData")) {
                        int call = metadataCalls.incrementAndGet();
                        if (probe == Probe.FAILS_ONCE && call == 1) {
                            throw new SQLException("metadata unavailable");
                        }
                        DatabaseMetaData metadata = (DatabaseMetaData) method.invoke(connection, args);
                        if (probe == Probe.UNSUPPORTED_PRODUCT) {
                            return Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(), new Class<?>[] {DatabaseMetaData.class},
                                    (p, m, a) -> {
                                        if (m.getName().equals("getDatabaseProductName")) {
                                            return "Unsupported product";
                                        }
                                        try {
                                            return m.invoke(metadata, a);
                                        } catch (InvocationTargetException e) {
                                            throw e.getCause();
                                        }
                                    });
                        }
                        return metadata;
                    }
                    return method.invoke(connection, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return delegate.getConnection(username, password);
        }
    }
}
