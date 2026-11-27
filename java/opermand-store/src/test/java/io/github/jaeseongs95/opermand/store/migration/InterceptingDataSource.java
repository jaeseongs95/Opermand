package io.github.jaeseongs95.opermand.store.migration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * A real data source whose connections report every call to an {@link Interceptor} before it reaches the real connection. The test decides there what happens: a call that blocks
 * on a latch (so that a second runner can be held at a known place of its run), or a call that fails with the {@link SQLException} of a database that lost the statement. Nothing
 * is answered by the interceptor in place of the database: every call that is let through is the real connection's.
 */
final class InterceptingDataSource extends DelegatingDataSource {

    /** Called with the JDBC method name and its arguments before the real connection runs it; it may block, or throw to fail the call. */
    @FunctionalInterface
    interface Interceptor {
        void before(String method, Object[] arguments) throws SQLException;
    }

    private final Interceptor interceptor;

    InterceptingDataSource(DataSource target, Interceptor interceptor) {
        super(target);
        this.interceptor = interceptor;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return intercepted(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return intercepted(super.getConnection(username, password));
    }

    private Connection intercepted(Connection real) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
            interceptor.before(method.getName(), arguments == null ? new Object[0] : arguments);
            try {
                return method.invoke(real, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
    }
}
