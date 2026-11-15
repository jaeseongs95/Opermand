package io.github.jaeseongs95.portal.store.migration;

import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.CLOCK;
import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.count;
import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.freshDatabase;
import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.freshUrl;
import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.history;
import static io.github.jaeseongs95.portal.store.migration.MigrationTestSupport.tableExists;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.LongStream;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.jaeseongs95.portal.store.migration.MigrationRunner.Phase;

/**
 * S-D0109-19 to S-D0109-21: the scripts of the product as they are in the jar. They are ANSI SQL in one folder ({@code db/expand}, no folder per DBMS), numbered from 1
 * without a gap, and the whole set applies on an embedded H2 database without a compatibility mode. The long-text columns are written {@code ${long_text}} and nothing else.
 */
class ProductScriptsTest {

    private static final PathMatchingResourcePatternResolver RESOURCES = new PathMatchingResourcePatternResolver();
    private static final Pattern DIRECT_CHILD = Pattern.compile(".*/db/expand/V\\d+__[^/]+\\.sql");
    private static final Pattern VENDOR_TYPE = Pattern.compile("(?i)\\b(CLOB|MEDIUMTEXT|LONGTEXT|TEXT|BLOB|BYTEA)\\b");

    /** The long-text columns of the schema: the same twelve as the vendor scripts of the Flyway era made (no column changed its meaning). */
    private static final List<String> LONG_TEXT_COLUMNS = List.of("maintenance_reservation.baseline", "operation_command.caller_context", "operation_command.command_text",
        "operation_command.display_context", "operation_download.caller_context", "operation_download.display_context", "operation_legacy.original_text",
        "operation_result.result_texts", "operation_source.argv_json", "operation_source.caller_context", "operation_source.display_context", "operation_source.launcher_command");

    private static String code(Resource script) throws IOException {
        String text = new String(script.getContentAsByteArray(), StandardCharsets.UTF_8);
        return text.replaceAll("--[^\\n]*", "").replaceAll("'[^']*'", "''");
    }

    @Test
    @DisplayName("S-D0109-19: the scripts are directly under db/expand, numbered 1 to 7 without a gap; there is no db/migration, no folder per DBMS, and no contract script yet")
    void theScriptsAreOneFolderOfConsecutiveNumbers() throws IOException {
        assertThat(MigrationScript.load(Phase.EXPAND.location())).extracting(MigrationScript::version).containsExactlyElementsOf(LongStream.rangeClosed(1, 7).boxed().toList());

        List<String> scripts = new ArrayList<>();
        for (Resource script : RESOURCES.getResources("classpath*:db/expand/**/*.sql")) {
            scripts.add(script.getURL().getPath());
        }
        assertThat(scripts).hasSize(7).allMatch(path -> DIRECT_CHILD.matcher(path).matches());
        assertThat(RESOURCES.getResources("classpath*:db/migration/**")).as("no Flyway-era folder, no folder per DBMS").isEmpty();
        assertThat(MigrationScript.load(Phase.CONTRACT.location())).as("the product has no contract script yet").isEmpty();
    }

    @Test
    @DisplayName("S-D0109-20: on an embedded H2 database without a compatibility mode the whole set applies: mode REGULAR, 7 scripts, 28 tables, and no Flyway table")
    void theWholeSetAppliesOnPlainH2() {
        assertThat(freshUrl()).doesNotContain("MODE=");
        DataSource database = freshDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(database);

        assertThat(new MigrationRunner(CLOCK, database).run(Phase.EXPAND)).isEqualTo(7);

        assertThat(jdbc.queryForObject("SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'MODE'", String.class)).isEqualTo("REGULAR");
        assertThat(history(jdbc, Phase.EXPAND)).containsExactly("1/1/SUCCEEDED", "2/1/SUCCEEDED", "3/1/SUCCEEDED", "4/1/SUCCEEDED", "5/1/SUCCEEDED", "6/1/SUCCEEDED", "7/1/SUCCEEDED");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE'")).isEqualTo(28);
        assertThat(tableExists(jdbc, "PUBLIC", "flyway_schema_history")).isFalse();
    }

    @Test
    @DisplayName("S-D0109-21: no script names a DBMS's own long text type; the twelve long-text columns are written ${long_text} and have one type on the database")
    void longTextIsWrittenOnlyAsThePlaceholder() throws IOException, SQLException {
        List<String> offenders = new ArrayList<>();
        int placeholders = 0;
        for (Resource script : RESOURCES.getResources("classpath*:db/expand/**/*.sql")) {
            String code = code(script);
            if (VENDOR_TYPE.matcher(code.replace("${long_text}", "")).find()) {
                offenders.add(script.getFilename());
            }
            placeholders += code.split(Pattern.quote("${long_text}"), -1).length - 1;
        }
        assertThat(offenders).isEmpty();
        assertThat(placeholders).as("every long-text column of the schema comes from the placeholder").isEqualTo(LONG_TEXT_COLUMNS.size());

        // What the driver of this database reports for a long text (H2 2.4.240 lists a length for its CHARACTER LARGE OBJECT, which the rule still chooses, declared with its largest size);
        // the columns of the schema are exactly the twelve of the list, all of that type and size.
        DataSource database = freshDatabase();
        LongTextType chosen;
        try (Connection connection = database.getConnection()) {
            chosen = LongTextType.choose(connection.getMetaData());
        }
        new MigrationRunner(CLOCK, database).run(Phase.EXPAND);
        List<String> columns = new JdbcTemplate(database).query("SELECT LOWER(TABLE_NAME) || '.' || LOWER(COLUMN_NAME), DATA_TYPE, CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS "
            + "WHERE TABLE_SCHEMA = 'PUBLIC' ORDER BY 1", (rows, number) -> rows.getString(2).equalsIgnoreCase(chosen.name())
                && (!chosen.needsLength() || rows.getLong(3) == chosen.precision()) ? rows.getString(1) : null).stream().filter(java.util.Objects::nonNull).toList();
        assertThat(columns).as("the columns declared as " + chosen.declaration()).containsExactlyElementsOf(LONG_TEXT_COLUMNS);
    }
}
