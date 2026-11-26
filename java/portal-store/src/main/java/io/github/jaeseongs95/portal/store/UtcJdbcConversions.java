package io.github.jaeseongs95.portal.store;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import javax.sql.DataSource;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Registers {@link UtcTimestamps} as the conversion of every {@link Instant} that a Spring Data JDBC repository writes and reads, and ensures {@code TIMESTAMP} columns
 * are always extracted as {@link LocalDateTime} via {@code ResultSet.getObject(col, LocalDateTime.class)} rather than {@code java.sql.Timestamp} (defect D-0161).
 *
 * <p>H2's {@code ResultSet.getObject(int)} returns a {@code java.sql.Timestamp} constructed in the JVM default zone, which shifts spring-forward DST gap instants (such as
 * {@code 2026-03-08T02:30:00Z} under {@code America/New_York} or {@code America/Los_Angeles}) by one hour before any {@link ReadingConverter} runs. Wrapping the
 * {@link DataSource} to route untyped {@code ResultSet.getObject(col)} on {@link Types#TIMESTAMP} columns to {@code ResultSet.getObject(col, LocalDateTime.class)} avoids
 * {@code java.sql.Timestamp} completely across every Spring Data JDBC and {@code JdbcTemplate} read.
 */
@Configuration(proxyBeanMethods = false)
class UtcJdbcConversions {

    @Bean
    @Primary
    JdbcCustomConversions utcJdbcCustomConversions(JdbcDialect dialect, UtcTimestamps timestamps) {
        return JdbcCustomConversions.of(dialect, List.of(new InstantToColumn(timestamps), new ColumnToInstant(timestamps)));
    }

    /** Wraps every {@link DataSource} bean once so untyped {@code ResultSet.getObject} reads {@code TIMESTAMP} columns as {@link LocalDateTime}. */
    @Bean
    static BeanPostProcessor utcTimestampDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof DataSource dataSource && !(bean instanceof UtcTimestampDataSource)) {
                    return new UtcTimestampDataSource(dataSource);
                }
                return bean;
            }
        };
    }

    @WritingConverter
    private static final class InstantToColumn implements Converter<Instant, LocalDateTime> {

        private final UtcTimestamps timestamps;

        InstantToColumn(UtcTimestamps timestamps) {
            this.timestamps = timestamps;
        }

        @Override
        public LocalDateTime convert(Instant source) {
            return timestamps.write(source);
        }
    }

    /** Reads a {@code TIMESTAMP} column extracted as {@link LocalDateTime} directly as a UTC {@link Instant}. */
    @ReadingConverter
    private static final class ColumnToInstant implements Converter<LocalDateTime, Instant> {

        private final UtcTimestamps timestamps;

        ColumnToInstant(UtcTimestamps timestamps) {
            this.timestamps = timestamps;
        }

        @Override
        public Instant convert(LocalDateTime source) {
            return timestamps.read(source);
        }
    }

    /** {@link DelegatingDataSource} that wraps returned JDBC connections so {@code TIMESTAMP} columns are read via {@code ResultSet.getObject(col, LocalDateTime.class)}. */
    static final class UtcTimestampDataSource extends DelegatingDataSource implements AutoCloseable {

        UtcTimestampDataSource(DataSource delegate) {
            super(delegate);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrapConnection(obtainTargetDataSource().getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrapConnection(obtainTargetDataSource().getConnection(username, password));
        }

        @Override
        public void close() {
            if (getTargetDataSource() instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (RuntimeException e) {
                    throw e;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while closing delegated DataSource", e);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to close delegated DataSource", e);
                }
            }
        }

        private static Connection wrapConnection(Connection target) {
            return (Connection) Proxy.newProxyInstance(UtcTimestampDataSource.class.getClassLoader(), new Class<?>[] {Connection.class},
                new ConnectionHandler(target));
        }
    }

    private static final class ConnectionHandler implements InvocationHandler {

        private final Connection target;

        ConnectionHandler(Connection target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = invokeTarget(target, method, args);
            if (result instanceof CallableStatement callable) {
                return wrapStatement(callable, CallableStatement.class);
            }
            if (result instanceof PreparedStatement prepared) {
                return wrapStatement(prepared, PreparedStatement.class);
            }
            if (result instanceof Statement statement) {
                return wrapStatement(statement, Statement.class);
            }
            return result;
        }

        private static <T extends Statement> T wrapStatement(T statement, Class<T> iface) {
            return iface.cast(Proxy.newProxyInstance(UtcTimestampDataSource.class.getClassLoader(), new Class<?>[] {iface},
                new StatementHandler(statement)));
        }
    }

    private static final class StatementHandler implements InvocationHandler {

        private final Statement target;

        StatementHandler(Statement target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = invokeTarget(target, method, args);
            if (result instanceof ResultSet rs) {
                return Proxy.newProxyInstance(UtcTimestampDataSource.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                    new ResultSetHandler(rs));
            }
            return result;
        }
    }

    private static final class ResultSetHandler implements InvocationHandler {

        private final ResultSet target;
        private boolean[] timestampColumns;
        private String[] columnLabels;

        ResultSetHandler(ResultSet target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("getObject".equals(method.getName()) && args != null && args.length == 1) {
                if (args[0] instanceof Integer index && isTimestampColumn(index)) {
                    return target.getObject(index, LocalDateTime.class);
                }
                if (args[0] instanceof String label && isTimestampLabel(label)) {
                    return target.getObject(label, LocalDateTime.class);
                }
            }
            return invokeTarget(target, method, args);
        }

        private boolean isTimestampColumn(int oneBasedIndex) throws SQLException {
            initMetadata();
            return oneBasedIndex >= 1 && oneBasedIndex <= timestampColumns.length && timestampColumns[oneBasedIndex - 1];
        }

        private boolean isTimestampLabel(String label) throws SQLException {
            initMetadata();
            String wanted = label.toLowerCase(Locale.ROOT);
            for (int i = 0; i < columnLabels.length; i++) {
                if (columnLabels[i].equals(wanted)) {
                    return timestampColumns[i];
                }
            }
            return false;
        }

        private void initMetadata() throws SQLException {
            if (timestampColumns != null) {
                return;
            }
            ResultSetMetaData meta = target.getMetaData();
            int count = meta.getColumnCount();
            boolean[] flags = new boolean[count];
            String[] labels = new String[count];
            for (int i = 0; i < count; i++) {
                flags[i] = meta.getColumnType(i + 1) == Types.TIMESTAMP;
                String label = meta.getColumnLabel(i + 1);
                if (label == null || label.isEmpty()) {
                    label = meta.getColumnName(i + 1);
                }
                labels[i] = label == null ? "" : label.toLowerCase(Locale.ROOT);
            }
            timestampColumns = flags;
            columnLabels = labels;
        }
    }

    private static Object invokeTarget(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }
}
