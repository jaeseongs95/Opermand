package io.github.jaeseongs95.portal.store;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Configuration;

/**
 * Test-only {@link DataSource} proxy that enforces PostgreSQL's aborted-transaction semantics (SQLState {@code 25P02}) on embedded H2: once any SQL statement fails inside
 * a non-autocommit transaction, every subsequent SQL statement on that connection is rejected with {@code 25P02} until {@code rollback()}, {@code rollback(Savepoint)},
 * {@code setAutoCommit(true)}, or {@code close()} clears the aborted state.
 */
@Configuration(proxyBeanMethods = false)
public class PgAbortedTransactionDataSource implements BeanPostProcessor {

    private static final String ABORTED_SQL_STATE = "25P02";
    private static final Set<String> STATEMENT_CREATORS = Set.of("createStatement", "prepareStatement", "prepareCall");
    private static final Set<String> STATEMENT_EXECUTORS = Set.of("execute", "executeQuery", "executeUpdate", "executeBatch", "executeLargeUpdate", "executeLargeBatch");

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource dataSource) {
            return wrap(dataSource);
        }
        return bean;
    }

    public static DataSource wrap(DataSource delegate) {
        if (Proxy.isProxyClass(delegate.getClass()) && Proxy.getInvocationHandler(delegate) instanceof DataSourceHandler) {
            return delegate;
        }
        return (DataSource) Proxy.newProxyInstance(PgAbortedTransactionDataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
            new DataSourceHandler(delegate));
    }

    private static final class DataSourceHandler implements InvocationHandler {

        private final DataSource delegate;

        private DataSourceHandler(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = invokeUnwrapped(delegate, method, args);
            if ("getConnection".equals(method.getName()) && result instanceof Connection connection) {
                return proxyConnection(connection);
            }
            return result;
        }
    }

    private static Connection proxyConnection(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(PgAbortedTransactionDataSource.class.getClassLoader(), new Class<?>[] {Connection.class},
            new ConnectionHandler(delegate));
    }

    private static final class ConnectionHandler implements InvocationHandler {

        private final Connection delegate;
        private boolean aborted;

        private ConnectionHandler(Connection delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("rollback".equals(name)) {
                aborted = false;
                return invokeUnwrapped(delegate, method, args);
            }
            if ("close".equals(name)) {
                aborted = false;
                return invokeUnwrapped(delegate, method, args);
            }
            if ("setAutoCommit".equals(name) && args != null && args.length == 1 && Boolean.TRUE.equals(args[0])) {
                aborted = false;
                return invokeUnwrapped(delegate, method, args);
            }
            if ("commit".equals(name) && aborted) {
                aborted = false;
                delegate.rollback();
                throw abortedException();
            }
            if (STATEMENT_CREATORS.contains(name)) {
                if (aborted) {
                    throw abortedException();
                }
                Object created = invokeStatementCreator(method, args);
                if (created instanceof CallableStatement callable) {
                    return proxyStatement(callable, CallableStatement.class);
                }
                if (created instanceof PreparedStatement prepared) {
                    return proxyStatement(prepared, PreparedStatement.class);
                }
                if (created instanceof Statement statement) {
                    return proxyStatement(statement, Statement.class);
                }
                return created;
            }
            if ("releaseSavepoint".equals(name) && aborted) {
                throw abortedException();
            }
            if ("setSavepoint".equals(name) && aborted) {
                throw abortedException();
            }
            return invokeUnwrapped(delegate, method, args);
        }

        private Object invokeStatementCreator(Method method, Object[] args) throws Throwable {
            try {
                return invokeUnwrapped(delegate, method, args);
            } catch (SQLException e) {
                markAbortedIfInTransaction();
                throw e;
            }
        }

        private void markAbortedIfInTransaction() {
            try {
                if (!delegate.getAutoCommit()) {
                    aborted = true;
                }
            } catch (SQLException ignored) {
                // Connection already closed or broken; nothing further to track.
            }
        }

        private <T extends Statement> T proxyStatement(T statement, Class<T> iface) {
            @SuppressWarnings("unchecked")
            T proxied = (T) Proxy.newProxyInstance(PgAbortedTransactionDataSource.class.getClassLoader(), new Class<?>[] {iface}, (proxy, method, args) -> {
                if (STATEMENT_EXECUTORS.contains(method.getName())) {
                    if (aborted) {
                        throw abortedException();
                    }
                    try {
                        return invokeUnwrapped(statement, method, args);
                    } catch (SQLException e) {
                        markAbortedIfInTransaction();
                        throw e;
                    }
                }
                return invokeUnwrapped(statement, method, args);
            });
            return proxied;
        }

        private static SQLException abortedException() {
            return new SQLException("ERROR: current transaction is aborted, commands ignored until end of transaction block", ABORTED_SQL_STATE);
        }
    }

    private static Object invokeUnwrapped(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }
}
