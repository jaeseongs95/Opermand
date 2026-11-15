package io.github.jaeseongs95.portal.store.sql;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.portal.store.sql.DatabaseProductException.Reason;

/**
 * Builds SQL for the actual business database, resolving its product on first use. Ported from AGS {@code SqlBuilder} (repository jaeseongs95/sennard-source, branch
 * int-6d37-m3, last changed in commit 1ad1b8e7, path {@code java/ags-api/src/main/java/io/github/jaeseongs95/ags/service/database/SqlBuilder.java}; same owner,
 * AGPL-3.0-or-later) with the same structure, API, products and limit syntax: the only difference is the exception type, {@link DatabaseProductException} in place of
 * AGS {@code StoreException}. It opens no transaction: it joins the connection of the caller's transaction or borrows one for the single product read.
 */
@Component
public class SqlBuilder {
    /** The business database products the portal builds SQL for. */
    public enum Database { H2, POSTGRESQL, ORACLE, DB2, SQLSERVER, MARIADB, MYSQL }

    private final DataSource dataSource;
    private volatile Database resolved;

    public SqlBuilder(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private Database database() {
        Database result = resolved;
        if (result != null) {
            return result;
        }
        // Acquire before the lock: another thread may hold a transaction's only pool connection.
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            synchronized (this) {
                result = resolved;
                if (result == null) {
                    result = database(DatabaseDriver.fromProductName(connection.getMetaData().getDatabaseProductName()));
                    resolved = result;
                }
                return result;
            }
        } catch (SQLException failure) {
            throw new DatabaseProductException(Reason.STORE_UNREACHABLE, "Could not inspect business database product", failure);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    static Database database(DatabaseDriver driver) {
        return switch (driver) {
            case H2 -> Database.H2;
            case POSTGRESQL -> Database.POSTGRESQL;
            case ORACLE -> Database.ORACLE;
            case DB2 -> Database.DB2;
            case SQLSERVER -> Database.SQLSERVER;
            case MARIADB -> Database.MARIADB;
            case MYSQL -> Database.MYSQL;
            default -> throw new DatabaseProductException(Reason.UNSUPPORTED_DATABASE_PRODUCT, "Unsupported business database: " + driver);
        };
    }

    /** The parameter is a JDBC placeholder, not the value. */
    public String generateLimit(String parameter) {
        return generateLimit(database(), parameter);
    }

    static String generateLimit(Database database, String parameter) {
        // PostgreSQL: https://www.postgresql.org/docs/current/sql-select.html
        // H2: https://h2database.com/html/commands.html#select
        // Oracle: https://docs.oracle.com/en/database/oracle/oracle-database/23/sqlrf/SELECT.html
        // Db2: https://www.ibm.com/docs/en/db2/12.1.x?topic=design-guidelines-restricting-select-statements
        // SQL Server: https://learn.microsoft.com/en-us/sql/t-sql/queries/select-order-by-clause-transact-sql
        // MariaDB 10.6+: https://mariadb.com/docs/server/reference/sql-statements/data-manipulation/selecting-data/select-offset-fetch
        // MySQL: https://dev.mysql.com/doc/refman/8.4/en/select.html
        return switch (database) {
            case H2, POSTGRESQL, ORACLE, DB2 -> "FETCH FIRST " + parameter + " ROWS ONLY";
            case SQLSERVER, MARIADB -> "OFFSET 0 ROWS FETCH NEXT " + parameter + " ROWS ONLY";
            case MYSQL -> "LIMIT " + parameter;
        };
    }

    /** Appends the dialect's row limit to an otherwise complete query. */
    public String applyLimit(String statement, String parameter) {
        return statement + " " + generateLimit(parameter);
    }

    /** Starts a short-lived statement from fixed SQL. */
    public Statement query(String sql) {
        return new Statement(sql);
    }

    public final class Statement {
        private final StringBuilder sql;

        private Statement(String base) {
            sql = new StringBuilder(base);
        }

        /** The fixed pattern may use %1$d for the zero-based parameter suffix. */
        public Statement orGroup(String pattern, int count) {
            sql.append('(');
            if (count == 0) {
                sql.append("1 = 0");
            }
            for (int index = 0; index < count; index++) {
                if (index > 0) {
                    sql.append(" OR ");
                }
                sql.append(pattern.formatted(index));
            }
            sql.append(')');
            return this;
        }

        public Statement and(String condition) {
            sql.append(" AND ").append(condition);
            return this;
        }

        public Statement orderBy(String columns) {
            sql.append(" ORDER BY ").append(columns);
            return this;
        }

        public Statement append(String fixedSql) {
            sql.append(fixedSql);
            return this;
        }

        public String build() {
            database();
            return sql.toString();
        }

        public String limit(String parameter) {
            return applyLimit(build(), parameter);
        }
    }

    /** Refuses a product different from the actual DataSource product. */
    public void requireSame(String productName) {
        requireSame(database(), database(DatabaseDriver.fromProductName(productName)));
    }

    /** The product a JDBC URL names (no connection is made); Reason.UNSUPPORTED_DATABASE_PRODUCT for one the portal has no SQL for. */
    public static Database ofJdbcUrl(String jdbcUrl) {
        return database(DatabaseDriver.fromJdbcUrl(jdbcUrl));
    }

    /** The product a connection reports ({@code DatabaseMetaData.getDatabaseProductName()}); Reason.UNSUPPORTED_DATABASE_PRODUCT for one the portal has no SQL for. */
    public static Database ofProductName(String productName) {
        return database(DatabaseDriver.fromProductName(productName));
    }

    /** Checks the configured URL against the pool product before the pool is made ready. */
    public static void requireSame(String jdbcUrl, String productName) {
        requireSame(ofJdbcUrl(jdbcUrl), database(DatabaseDriver.fromProductName(productName)));
    }

    private static void requireSame(Database configured, Database actual) {
        if (configured != actual) {
            throw new DatabaseProductException(Reason.DATABASE_PRODUCT_MISMATCH,
                    "Configured business database " + configured + " does not match connected product " + actual);
        }
    }
}
