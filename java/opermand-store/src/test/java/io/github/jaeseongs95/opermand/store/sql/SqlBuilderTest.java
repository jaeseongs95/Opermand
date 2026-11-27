package io.github.jaeseongs95.opermand.store.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.boot.jdbc.DatabaseDriver;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import com.zaxxer.hikari.HikariDataSource;

import io.github.jaeseongs95.opermand.store.sql.DatabaseProductException.Reason;

/**
 * S-SQLB-01, 02, 05, 07, 08, 09, 21 (docs/migration/sqlbuilder-cases.md). Ported from AGS {@code SqlBuilderTest} (AGS, Agent Governance Suite, as of commit 1ad1b8e7): the seven
 * dialects render a bound limit; a complete statement keeps its parameter; an unknown database is refused; the default embedded database is H2.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlBuilderTest {
    private AnnotationConfigApplicationContext context;

    /** The DataSource and the SqlBuilder are beans of one context, so the builder is injected with the DataSource the way the application's is. */
    @BeforeAll
    void startContext() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> {
            HikariDataSource pool = new HikariDataSource();
            pool.setJdbcUrl("jdbc:h2:mem:sqlLimitTest");
            pool.setUsername("sa");
            pool.setMaximumPoolSize(2);
            return pool;
        }, definition -> definition.setDestroyMethodName("close"));
        context.register(SqlBuilder.class);
        context.refresh();
    }

    @AfterAll
    void closeContext() {
        context.close();
    }

    /** One or three session pairs use distinct bound names; the optional text condition only appears when requested. */
    @Test
    @DisplayName("S-SQLB-02: orGroup, and, orderBy and limit assemble the session conditions and the optional search with bound names")
    void assemblesSessionConditionsAndOptionalSearch() {
        SqlBuilder builder = h2Builder();
        assertThat(builder.query("SELECT * FROM prompt WHERE ").orGroup("(host = :host%1$d AND session_id = :session%1$d)", 1)
                .orderBy("received_at DESC").limit(":limit"))
                .isEqualTo("SELECT * FROM prompt WHERE ((host = :host0 AND session_id = :session0)) ORDER BY received_at DESC FETCH FIRST :limit ROWS ONLY");
        assertThat(builder.query("SELECT * FROM prompt WHERE ").orGroup("(host = :host%1$d AND session_id = :session%1$d)", 3)
                .and("text LIKE :pattern").orderBy("received_at DESC").limit(":limit"))
                .isEqualTo("SELECT * FROM prompt WHERE ((host = :host0 AND session_id = :session0) OR (host = :host1 AND session_id = :session1) OR (host = :host2 AND session_id = :session2)) AND text LIKE :pattern ORDER BY received_at DESC FETCH FIRST :limit ROWS ONLY");
    }

    /** D-0165: the suffix is a number in a statement, not text for the reader: a locale with other digits (Devanagari here) must not change the bound names. */
    @Test
    @ResourceLock(Resources.LOCALE)
    @DisplayName("S-D0165-01: orGroup numbers its bound names with ASCII digits whatever the default format locale is")
    void theBoundNamesDoNotDependOnTheLocale() {
        Locale before = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("hi-IN-u-nu-deva"));
            assertThat(String.format("%d", 1)).as("the premise: this locale writes the digit 1 as another character").isNotEqualTo("1");

            assertThat(h2Builder().query("SELECT 1 WHERE ").orGroup("host = :host%1$d", 2).build()).isEqualTo("SELECT 1 WHERE (host = :host0 OR host = :host1)");
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, before);
        }
    }

    /** Closing-event alternatives are positional; an empty group cannot match any event. */
    @Test
    @DisplayName("S-SQLB-02: positional alternatives; an empty group matches nothing; append adds fixed SQL")
    void assemblesClosingEventConditions() {
        SqlBuilder builder = h2Builder();
        assertThat(builder.query("SELECT ").orGroup("event = ?", 0).build()).isEqualTo("SELECT (1 = 0)");
        assertThat(builder.query("SELECT ").orGroup("event = ?", 2).build()).isEqualTo("SELECT (event = ? OR event = ?)");
        assertThat(builder.query("SELECT id FROM item").append(" WHERE id > ?").build()).isEqualTo("SELECT id FROM item WHERE id > ?");
    }

    @Test
    @DisplayName("S-SQLB-02: applyLimit adds the dialect limit to a complete statement and keeps its parameter")
    void addsTheDialectLimitToAStatement() {
        assertThat(h2Builder().applyLimit("SELECT id FROM item ORDER BY id", ":count")).isEqualTo("SELECT id FROM item ORDER BY id FETCH FIRST :count ROWS ONLY");
        assertThat("SELECT id FROM item ORDER BY id " + SqlBuilder.generateLimit(SqlBuilder.Database.MYSQL, "?")).isEqualTo("SELECT id FROM item ORDER BY id LIMIT ?");
    }

    @Test
    @DisplayName("S-SQLB-01: each of the seven products renders its own row limit")
    void rendersEachDialect() {
        for (SqlBuilder.Database db : new SqlBuilder.Database[] {SqlBuilder.Database.H2, SqlBuilder.Database.POSTGRESQL, SqlBuilder.Database.ORACLE, SqlBuilder.Database.DB2}) {
            assertThat(SqlBuilder.generateLimit(db, ":count")).as(db.name()).isEqualTo("FETCH FIRST :count ROWS ONLY");
        }
        for (SqlBuilder.Database db : new SqlBuilder.Database[] {SqlBuilder.Database.SQLSERVER, SqlBuilder.Database.MARIADB}) {
            assertThat(SqlBuilder.generateLimit(db, ":count")).as(db.name()).isEqualTo("OFFSET 0 ROWS FETCH NEXT :count ROWS ONLY");
        }
        assertThat(SqlBuilder.generateLimit(SqlBuilder.Database.MYSQL, ":count")).isEqualTo("LIMIT :count");
    }

    @Test
    @DisplayName("S-SQLB-05: a database the builder has no SQL for is refused with UNSUPPORTED_DATABASE_PRODUCT")
    void rejectsAnUnknownDatabase() {
        assertThatThrownBy(() -> SqlBuilder.database(DatabaseDriver.UNKNOWN)).isInstanceOfSatisfying(DatabaseProductException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(Reason.UNSUPPORTED_DATABASE_PRODUCT);
            assertThat(failure.getMessage()).startsWith("UNSUPPORTED_DATABASE_PRODUCT: ");
        });
    }

    /** A matching product is accepted; a mismatch identifies both the configured and actual products. */
    @Test
    @DisplayName("S-SQLB-07: a matching product is accepted; a mismatch names both products; a URL of an unsupported product is refused")
    void checksTheConnectedProduct() {
        SqlBuilder.requireSame("jdbc:postgresql://localhost/db", "PostgreSQL");
        assertThatThrownBy(() -> SqlBuilder.requireSame("jdbc:postgresql://localhost/db", "H2")).isInstanceOfSatisfying(DatabaseProductException.class, mismatch -> {
            assertThat(mismatch.reason()).isEqualTo(Reason.DATABASE_PRODUCT_MISMATCH);
            assertThat(mismatch.getMessage()).startsWith("DATABASE_PRODUCT_MISMATCH: ");
            assertThat(mismatch.getMessage()).contains("POSTGRESQL").contains("H2");
        });
        assertThatThrownBy(() -> SqlBuilder.requireSame("jdbc:derby:memory:db", "H2")).isInstanceOfSatisfying(DatabaseProductException.class,
                failure -> assertThat(failure.reason()).isEqualTo(Reason.UNSUPPORTED_DATABASE_PRODUCT));
    }

    @Test
    @DisplayName("S-SQLB-09: the product is selected from the configured JDBC URL")
    void selectsTheDialectFromTheConfiguredUrl() {
        assertThat(SqlBuilder.ofJdbcUrl("jdbc:h2:mem:db")).isEqualTo(SqlBuilder.Database.H2);
        assertThat(SqlBuilder.ofJdbcUrl("jdbc:postgresql://localhost/db")).isEqualTo(SqlBuilder.Database.POSTGRESQL);
        assertThat(SqlBuilder.ofJdbcUrl("jdbc:oracle:thin:@localhost:1521/db")).isEqualTo(SqlBuilder.Database.ORACLE);
        assertThat(SqlBuilder.ofJdbcUrl("jdbc:db2://localhost/db")).isEqualTo(SqlBuilder.Database.DB2);
        assertThat(SqlBuilder.ofJdbcUrl("jdbc:sqlserver://localhost")).isEqualTo(SqlBuilder.Database.SQLSERVER);
        assertThat(SqlBuilder.ofJdbcUrl("jdbc:mariadb://localhost/db")).isEqualTo(SqlBuilder.Database.MARIADB);
        assertThat(SqlBuilder.ofJdbcUrl("jdbc:mysql://localhost/db")).isEqualTo(SqlBuilder.Database.MYSQL);
        assertThat(SqlBuilder.ofProductName("PostgreSQL")).isEqualTo(SqlBuilder.Database.POSTGRESQL);
    }

    @Test
    @DisplayName("S-SQLB-22: each of the seven products declares its long text column with its own type, and a type whose length may be left out (Db2 CLOB: 1M) is written with the length")
    void generatesTheLongTextDeclarationOfEveryProduct() {
        assertThat(SqlBuilder.generateLongText(SqlBuilder.Database.H2)).isEqualTo("CHARACTER LARGE OBJECT(2147483647)");
        assertThat(SqlBuilder.generateLongText(SqlBuilder.Database.POSTGRESQL)).isEqualTo("TEXT");
        assertThat(SqlBuilder.generateLongText(SqlBuilder.Database.ORACLE)).isEqualTo("CLOB");
        assertThat(SqlBuilder.generateLongText(SqlBuilder.Database.DB2)).isEqualTo("CLOB(2147483647)");
        assertThat(SqlBuilder.generateLongText(SqlBuilder.Database.SQLSERVER)).isEqualTo("NVARCHAR(MAX)");
        assertThat(SqlBuilder.generateLongText(SqlBuilder.Database.MARIADB)).isEqualTo("LONGTEXT");
        assertThat(SqlBuilder.generateLongText(SqlBuilder.Database.MYSQL)).isEqualTo("LONGTEXT");
        assertThat(SqlBuilder.Database.values()).as("every product above is a case of this test").hasSize(7);
    }

    @Test
    @DisplayName("S-SQLB-23: each of the seven products checks UTF-8 with a query that returns the character count and the value of the text cast to the schema's character type")
    void probesTheEncodingWithTheCharacterCountAndTheValueOfEachDialect() {
        assertThat(SqlBuilder.generateEncodingProbe(SqlBuilder.Database.POSTGRESQL)).isEqualTo("SELECT CHAR_LENGTH(CAST(? AS VARCHAR(10))), CAST(? AS VARCHAR(10))");
        assertThat(SqlBuilder.generateEncodingProbe(SqlBuilder.Database.H2)).isEqualTo("SELECT CHAR_LENGTH(CAST(? AS VARCHAR(10))), CAST(? AS VARCHAR(10))");
        assertThat(SqlBuilder.generateEncodingProbe(SqlBuilder.Database.ORACLE)).isEqualTo("SELECT LENGTH(CAST(? AS VARCHAR2(10))), CAST(? AS VARCHAR2(10)) FROM DUAL");
        assertThat(SqlBuilder.generateEncodingProbe(SqlBuilder.Database.DB2))
            .isEqualTo("SELECT CHARACTER_LENGTH(CAST(? AS VARCHAR(10)) USING CODEUNITS32), CAST(? AS VARCHAR(10)) FROM SYSIBM.SYSDUMMY1");
        assertThat(SqlBuilder.generateEncodingProbe(SqlBuilder.Database.SQLSERVER)).isEqualTo("SELECT LEN(CAST(? AS VARCHAR(10))), CAST(? AS VARCHAR(10))");
        assertThat(SqlBuilder.generateEncodingProbe(SqlBuilder.Database.MARIADB)).isEqualTo("SELECT CHAR_LENGTH(CAST(? AS CHAR(10))), CAST(? AS CHAR(10))");
        assertThat(SqlBuilder.generateEncodingProbe(SqlBuilder.Database.MYSQL)).isEqualTo("SELECT CHAR_LENGTH(CAST(? AS CHAR(10))), CAST(? AS CHAR(10))");
        for (SqlBuilder.Database db : SqlBuilder.Database.values()) {
            assertThat(SqlBuilder.generateEncodingProbe(db).chars().filter(c -> c == '?').count()).as(db + " takes the text twice: for the count and for the value").isEqualTo(2);
        }
    }

    @Test
    @DisplayName("S-SQLB-21: each of the seven product names a connection reports selects its product, by name alone and without a connection")
    void selectsTheDialectFromEachProductName() {
        assertThat(SqlBuilder.ofProductName("H2")).isEqualTo(SqlBuilder.Database.H2);
        assertThat(SqlBuilder.ofProductName("PostgreSQL")).isEqualTo(SqlBuilder.Database.POSTGRESQL);
        assertThat(SqlBuilder.ofProductName("Oracle")).isEqualTo(SqlBuilder.Database.ORACLE);
        assertThat(SqlBuilder.ofProductName("DB2/NT64")).isEqualTo(SqlBuilder.Database.DB2);
        assertThat(SqlBuilder.ofProductName("Microsoft SQL Server")).isEqualTo(SqlBuilder.Database.SQLSERVER);
        assertThat(SqlBuilder.ofProductName("MariaDB")).isEqualTo(SqlBuilder.Database.MARIADB);
        assertThat(SqlBuilder.ofProductName("MySQL")).isEqualTo(SqlBuilder.Database.MYSQL);
        SqlBuilder.requireSame("jdbc:oracle:thin:@localhost:1521/db", "Oracle");
        SqlBuilder.requireSame("jdbc:db2://localhost/db", "DB2/NT64");
        SqlBuilder.requireSame("jdbc:sqlserver://localhost", "Microsoft SQL Server");
        SqlBuilder.requireSame("jdbc:mariadb://localhost/db", "MariaDB");
        SqlBuilder.requireSame("jdbc:mysql://localhost/db", "MySQL");
    }

    @Test
    @DisplayName("S-SQLB-08: the actual DataSource of the context is the embedded H2; the same product is accepted and another is refused")
    void recognizesTheEmbeddedH2Database() {
        assertThat(h2Builder().generateLimit("?")).isEqualTo("FETCH FIRST ? ROWS ONLY");
        h2Builder().requireSame("H2");
        assertThatThrownBy(() -> h2Builder().requireSame("PostgreSQL")).isInstanceOfSatisfying(DatabaseProductException.class,
                failure -> assertThat(failure.reason()).isEqualTo(Reason.DATABASE_PRODUCT_MISMATCH));
    }

    @Test
    @DisplayName("S-D0109-29: each of the seven products renders its catalog existence queries, migration history timestamp type, and transactional DDL capability")
    void rendersCatalogQueriesHistoryTimestampAndDdlCapabilityForAllSevenProducts() {
        assertThat(SqlBuilder.generateSchemaExistsQuery(SqlBuilder.Database.ORACLE)).isEqualTo("SELECT COUNT(*) FROM all_users WHERE LOWER(username) = ?");
        assertThat(SqlBuilder.generateSchemaExistsQuery(SqlBuilder.Database.DB2)).isEqualTo("SELECT COUNT(*) FROM syscat.schemata WHERE LOWER(schemaname) = ?");
        for (SqlBuilder.Database db : new SqlBuilder.Database[] {SqlBuilder.Database.H2, SqlBuilder.Database.POSTGRESQL, SqlBuilder.Database.MARIADB,
                SqlBuilder.Database.MYSQL, SqlBuilder.Database.SQLSERVER}) {
            assertThat(SqlBuilder.generateSchemaExistsQuery(db)).as(db.name())
                .isEqualTo("SELECT COUNT(*) FROM information_schema.schemata WHERE LOWER(schema_name) = ?");
        }

        assertThat(SqlBuilder.generateTableExistsQuery(SqlBuilder.Database.ORACLE)).isEqualTo("SELECT COUNT(*) FROM all_tables WHERE LOWER(owner) = ? AND LOWER(table_name) = ?");
        assertThat(SqlBuilder.generateTableExistsQuery(SqlBuilder.Database.DB2)).isEqualTo("SELECT COUNT(*) FROM syscat.tables WHERE LOWER(tabschema) = ? AND LOWER(tabname) = ?");
        for (SqlBuilder.Database db : new SqlBuilder.Database[] {SqlBuilder.Database.H2, SqlBuilder.Database.POSTGRESQL, SqlBuilder.Database.MARIADB,
                SqlBuilder.Database.MYSQL, SqlBuilder.Database.SQLSERVER}) {
            assertThat(SqlBuilder.generateTableExistsQuery(db)).as(db.name())
                .isEqualTo("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_schema) = ? AND LOWER(table_name) = ?");
        }

        assertThat(SqlBuilder.generateCurrentSchemaTableExistsQuery(SqlBuilder.Database.ORACLE)).contains("user_tables");
        assertThat(SqlBuilder.generateCurrentSchemaTableExistsQuery(SqlBuilder.Database.DB2)).contains("syscat.tables").contains("CURRENT SCHEMA");
        assertThat(SqlBuilder.generateCurrentSchemaTableExistsQuery(SqlBuilder.Database.H2)).contains("CURRENT_SCHEMA");
        assertThat(SqlBuilder.generateCurrentSchemaTableExistsQuery(SqlBuilder.Database.POSTGRESQL)).contains("CURRENT_SCHEMA()");
        assertThat(SqlBuilder.generateCurrentSchemaTableExistsQuery(SqlBuilder.Database.MARIADB)).contains("DATABASE()");
        assertThat(SqlBuilder.generateCurrentSchemaTableExistsQuery(SqlBuilder.Database.MYSQL)).contains("DATABASE()");
        assertThat(SqlBuilder.generateCurrentSchemaTableExistsQuery(SqlBuilder.Database.SQLSERVER)).contains("SCHEMA_NAME()");

        assertThat(SqlBuilder.generateHistoryTimestampType(SqlBuilder.Database.MARIADB)).isEqualTo("DATETIME(6)");
        assertThat(SqlBuilder.generateHistoryTimestampType(SqlBuilder.Database.MYSQL)).isEqualTo("DATETIME(6)");
        for (SqlBuilder.Database db : new SqlBuilder.Database[] {SqlBuilder.Database.H2, SqlBuilder.Database.POSTGRESQL, SqlBuilder.Database.ORACLE,
                SqlBuilder.Database.DB2, SqlBuilder.Database.SQLSERVER}) {
            assertThat(SqlBuilder.generateHistoryTimestampType(db)).as(db.name()).isEqualTo("TIMESTAMP");
        }

        for (SqlBuilder.Database db : new SqlBuilder.Database[] {SqlBuilder.Database.POSTGRESQL, SqlBuilder.Database.DB2, SqlBuilder.Database.SQLSERVER}) {
            assertThat(SqlBuilder.supportsTransactionalDdl(db)).as(db.name()).isTrue();
        }
        for (SqlBuilder.Database db : new SqlBuilder.Database[] {SqlBuilder.Database.H2, SqlBuilder.Database.ORACLE, SqlBuilder.Database.MARIADB, SqlBuilder.Database.MYSQL}) {
            assertThat(SqlBuilder.supportsTransactionalDdl(db)).as(db.name()).isFalse();
        }
    }

    /** The builder the application has: the bean of the context, over the context's one DataSource (a pool of an in-memory H2), as the product wires it. */
    private SqlBuilder h2Builder() {
        return context.getBean(SqlBuilder.class);
    }
}
