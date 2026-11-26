package io.github.jaeseongs95.portal.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.jaeseongs95.portal.store.migration.Migrations;

/**
 * S2-DB-01: the real migration of the product (the runner of {@code ...store.migration}, the scripts of {@code db/expand}) on an empty embedded H2 database (no compatibility
 * mode): the 28 tables of design 8.2 and 13.2 exist, the history holds the seven scripts as attempt 1, and a second run changes nothing.
 */
@StoreTest
class SchemaMigrationTest {

    /** The 24 tables after removing dead 2.x model-evaluation tables (D-0122), operation_legacy (D-0127), and maintenance_release (D-0124), with the budget gate of V8 (D-0058): V1 10, V2 3, V3 3, V4 3, V5 1, V6 3, V8 1. */
    static final Set<String> TABLES = Set.of("operation", "operation_event", "operation_command", "operation_source", "operation_upload", "operation_download",
        "operation_decision", "operation_result", "operation_slot", "operation_fence",
        "artifact", "artifact_chunk", "staged_action", "command_output", "command_output_format", "command_output_page", "command_policy", "command_policy_rule",
        "command_policy_name", "agent_cli_request", "maintenance_reservation", "runtime_capacity",
        "runtime_capacity_target", "storage_budget");

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    SchemaMigrationTest(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    @Test
    @DisplayName("S2-DB-01: the migration of an empty database creates exactly the 24 tables")
    void createsTheTwentyFourTables() {
        List<String> names = jdbc.queryForList(
            "SELECT LOWER(TABLE_NAME) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = CURRENT_SCHEMA AND TABLE_TYPE = 'BASE TABLE'", String.class);
        Set<String> product = new TreeSet<>(names);

        assertThat(TABLES).hasSize(24);
        assertThat(product).isEqualTo(new TreeSet<>(TABLES));
    }

    @Test
    @DisplayName("S2-DB-01 / D3-04: the applied scripts are V1 to V8 (V7 is the execution timeline, V8 the budget gate), each once as attempt 1, and a second migration runs nothing")
    void aSecondRunChangesNothing() {
        List<String> applied = jdbc.query("SELECT version, attempt, outcome FROM portal_meta.expand_history ORDER BY version, attempt",
            (rows, number) -> rows.getLong("version") + "/" + rows.getInt("attempt") + "/" + rows.getString("outcome"));
        assertThat(applied).containsExactly("1/1/SUCCEEDED", "2/1/SUCCEEDED", "3/1/SUCCEEDED", "4/1/SUCCEEDED", "5/1/SUCCEEDED", "6/1/SUCCEEDED", "7/1/SUCCEEDED", "8/1/SUCCEEDED");

        assertThat(new Migrations(Clock.systemUTC(), dataSource).expand()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portal_meta.expand_history", Long.class)).isEqualTo(8L);
    }

    @Test
    @DisplayName("S2-DB-01: the command policy exists after the migration at revision 0")
    void thePolicySeedRowExists() {
        assertThat(jdbc.queryForObject("SELECT revision FROM command_policy WHERE id = 1", Long.class)).isZero();
    }

    @Test
    @DisplayName("S2-DB-01: every table of the schema is empty except the policy seed and the two rows of the budget gate (V8, D-0058)")
    void theTablesAreEmpty() {
        for (String table : TABLES) {
            long expected = switch (table) {
                case "command_policy" -> 1;
                case "storage_budget" -> 2;
                default -> 0;
            };
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).as(table).isEqualTo(expected);
        }
    }
}
