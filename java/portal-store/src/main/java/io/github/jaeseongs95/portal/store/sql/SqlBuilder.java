package io.github.jaeseongs95.portal.store.sql;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.portal.store.sql.DatabaseProductException.Reason;

/**
 * Builds SQL for the actual business database, resolving its product on first use. Ported from the SqlBuilder of AGS (Agent Governance Suite), as of commit 1ad1b8e7
 * (path {@code java/ags-api/src/main/java/io/github/jaeseongs95/ags/service/database/SqlBuilder.java};
 * same owner, AGPL-3.0-or-later), with the same structure, API, products and limit syntax: the only difference is the exception type, {@link DatabaseProductException}
 * in place of AGS {@code StoreException}; {@link #generateLongText} is added in the shape of {@code generateLimit} (the AGS original has no such method at that commit). It opens no transaction: it joins the connection of the caller's transaction or borrows one for the single product read.
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

    /**
     * The declaration of a column that holds a long text (the migration scripts write it as {@code ${long_text}}): the product's own type, with the length written where leaving it out
     * would give a smaller limit. Like {@link #generateLimit(Database, String)} it is a switch over every product without a default, so a product that is added fails the build here
     * until its declaration is written.
     */
    public static String generateLongText(Database database) {
        // PostgreSQL: https://www.postgresql.org/docs/current/datatype-character.html (text: unlimited length)
        // H2: https://h2database.com/html/datatypes.html#character_large_object_type (CHARACTER LARGE OBJECT, up to 2,147,483,647 characters)
        // Oracle: https://docs.oracle.com/en/database/oracle/oracle-database/23/sqlrf/Data-Types.html (CLOB: no length is given)
        // Db2: https://www.ibm.com/docs/en/db2/12.1.x?topic=list-character-strings (CLOB(n), n up to 2,147,483,647; the default without n is 1M, so n is written)
        // SQL Server: https://learn.microsoft.com/en-us/sql/t-sql/data-types/nchar-and-nvarchar-transact-sql (NVARCHAR(MAX), Unicode up to 2 GB; VARCHAR(MAX) depends on the collation's code page)
        // MariaDB: https://mariadb.com/docs/server/reference/data-types/string-data-types/longtext
        // MySQL: https://dev.mysql.com/doc/refman/8.4/en/blob.html (LONGTEXT)
        return switch (database) {
            case H2 -> "CHARACTER LARGE OBJECT(2147483647)";
            case POSTGRESQL -> "TEXT";
            case ORACLE -> "CLOB";
            case DB2 -> "CLOB(2147483647)";
            case SQLSERVER -> "NVARCHAR(MAX)";
            case MARIADB, MYSQL -> "LONGTEXT";
        };
    }

    /**
     * A query with two {@code ?} (the same text twice) for the start-up check that the database takes UTF-8 text in the character type the schema uses (the migration scripts'
     * {@code VARCHAR}; {@code VARCHAR2} on Oracle). The text is cast to that type, and the query returns two columns: the number of characters the database counts in the cast
     * value, and the cast value itself. The count catches a database that counts bytes (PostgreSQL's SQL_ASCII); the value catches a type that loses the text (a {@code VARCHAR}
     * of a code page that has no Korean turns it into {@code ??}, and then the count is still 2). Both must equal the text.
     *
     * <p>It is a fixed table by dialect, not a JDBC scalar function escape ({@code {fn CHAR_LENGTH(...)}}): the escape's translation is each driver's own and a driver's
     * documentation of it could not be confirmed for all seven products, and the products differ in more than the function name (a FROM clause, the type name). Where a cast
     * to {@code CHAR(n)} would pad the value (PostgreSQL's value comes back padded), a variable-length type is used.
     */
    public static String generateEncodingProbe(Database database) {
        // PostgreSQL char_length: https://www.postgresql.org/docs/current/functions-string.html
        // H2 CHAR_LENGTH: https://h2database.com/html/functions.html#char_length
        // MySQL CHAR_LENGTH (code points; CAST has CHAR, not VARCHAR, and CAST ... AS CHAR(n) is not padded): https://dev.mysql.com/doc/refman/8.4/en/string-functions.html
        // MariaDB CHAR_LENGTH: https://mariadb.com/docs/server/reference/sql-functions/string-functions/char_length
        // Oracle LENGTH (characters), VARCHAR2, FROM DUAL as before 23ai: https://docs.oracle.com/en/database/oracle/oracle-database/23/sqlrf/LENGTH.html
        // Db2 CHARACTER_LENGTH(... USING CODEUNITS32), FROM SYSIBM.SYSDUMMY1: https://www.ibm.com/docs/en/db2/12.1.x?topic=functions-character-length
        // SQL Server has no CHAR_LENGTH: LEN counts characters (and drops trailing spaces): https://learn.microsoft.com/en-us/sql/t-sql/functions/len-transact-sql
        //     The cast is to VARCHAR, not NVARCHAR: NVARCHAR is UTF-16 whatever the collation and would always pass, while the schema's VARCHAR holds Korean only with a UTF-8 collation
        //     (SQL Server 2019 or later), https://learn.microsoft.com/en-us/sql/t-sql/data-types/char-and-varchar-transact-sql
        return switch (database) {
            case POSTGRESQL, H2 -> "SELECT CHAR_LENGTH(CAST(? AS VARCHAR(10))), CAST(? AS VARCHAR(10))";
            case MARIADB, MYSQL -> "SELECT CHAR_LENGTH(CAST(? AS CHAR(10))), CAST(? AS CHAR(10))";
            case ORACLE -> "SELECT LENGTH(CAST(? AS VARCHAR2(10))), CAST(? AS VARCHAR2(10)) FROM DUAL";
            case DB2 -> "SELECT CHARACTER_LENGTH(CAST(? AS VARCHAR(10)) USING CODEUNITS32), CAST(? AS VARCHAR(10)) FROM SYSIBM.SYSDUMMY1";
            case SQLSERVER -> "SELECT LEN(CAST(? AS VARCHAR(10))), CAST(? AS VARCHAR(10))";
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
