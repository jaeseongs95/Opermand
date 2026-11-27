package io.github.jaeseongs95.opermand.store.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

import io.github.jaeseongs95.opermand.store.StoreTest;

/**
 * S-SQLB-10 (docs/migration/sqlbuilder-cases.md): the statement the builder writes runs on the actual DBMS of the storage slice, the embedded H2. The product does not test other
 * DBMSs (Principles 7.3: the driver is the user's part), so the dialect text of the other products is checked as text in {@code SqlBuilderTest}.
 */
@StoreTest
@Import(SqlBuilder.class)
class SqlBuilderStoreTest {

    private final SqlBuilder sql;
    private final NamedParameterJdbcOperations named;
    private final JdbcTemplate jdbc;

    SqlBuilderStoreTest(SqlBuilder sql, NamedParameterJdbcOperations named, JdbcTemplate jdbc) {
        this.sql = sql;
        this.named = named;
        this.jdbc = jdbc;
    }

    @Test
    @DisplayName("S-SQLB-10: the limit of the builder reads only the first rows of the ordering on the actual DBMS")
    void theLimitRunsOnTheActualDatabase() {
        for (String target : List.of("e", "b", "a", "d", "c")) {
            jdbc.update("INSERT INTO runtime_capacity_target (target_id) VALUES (?)", target);
        }

        String statement = sql.query("SELECT target_id FROM runtime_capacity_target WHERE target_id > :after").orderBy("target_id").limit(":limit");
        List<Map<String, Object>> rows = named.queryForList(statement, new MapSqlParameterSource().addValue("after", "a").addValue("limit", 3));

        assertThat(rows).extracting(row -> row.get("target_id")).containsExactly("b", "c", "d");
    }

    @Test
    @DisplayName("S-SQLB-10: the configured URL and the connected product name the same database")
    void theConfiguredUrlMatchesTheConnectedProduct() {
        String url = jdbc.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getURL());
        String product = jdbc.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName());

        SqlBuilder.requireSame(url, product);
        sql.requireSame(product);
    }
}
