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
 * S-D0109-19 to S-D0109-21 and S4-AT-06: the scripts of the product as they are in the jar. They are ANSI SQL in one folder ({@code db/expand}, no folder per DBMS),
 * numbered from 1 without a gap, and the whole set applies on an embedded H2 database without a compatibility mode. After B5-Crypto1 (U18 / D-0092), there are zero
 * {@code ${long_text}}, CLOB, BLOB, BYTEA, or unbounded text columns, and every string column has length at most 4096.
 */
class ProductScriptsTest {

    private static final PathMatchingResourcePatternResolver RESOURCES = new PathMatchingResourcePatternResolver();
    private static final Pattern DIRECT_CHILD = Pattern.compile(".*/db/expand/V\\d+__[^/]+\\.sql");
    private static final Pattern VENDOR_TYPE = Pattern.compile("(?i)\\b(CLOB|MEDIUMTEXT|LONGTEXT|TEXT|BLOB|BYTEA|VARBINARY)\\b");

    private static String code(Resource script) throws IOException {
        String text = new String(script.getContentAsByteArray(), StandardCharsets.UTF_8);
        return text.replaceAll("--[^\\n]*", "").replaceAll("'[^']*'", "''");
    }

    @Test
    @DisplayName("S-D0109-19: the scripts are directly under db/expand, numbered 1 to 8 without a gap; there is no db/migration, no folder per DBMS, and no contract script yet")
    void theScriptsAreOneFolderOfConsecutiveNumbers() throws IOException {
        assertThat(MigrationScript.load(Phase.EXPAND.location())).extracting(MigrationScript::version).containsExactlyElementsOf(LongStream.rangeClosed(1, 8).boxed().toList());

        List<String> scripts = new ArrayList<>();
        for (Resource script : RESOURCES.getResources("classpath*:db/expand/**/*.sql")) {
            scripts.add(script.getURL().getPath());
        }
        assertThat(scripts).hasSize(8).allMatch(path -> DIRECT_CHILD.matcher(path).matches());
        assertThat(RESOURCES.getResources("classpath*:db/migration/**")).as("no Flyway-era folder, no folder per DBMS").isEmpty();
        assertThat(MigrationScript.load(Phase.CONTRACT.location())).as("the product has no contract script yet").isEmpty();
    }

    @Test
    @DisplayName("S-D0109-20: on an embedded H2 database without a compatibility mode the whole set applies: mode REGULAR, 8 scripts, 25 tables, and no Flyway table")
    void theWholeSetAppliesOnPlainH2() {
        assertThat(freshUrl()).doesNotContain("MODE=");
        DataSource database = freshDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(database);

        assertThat(new MigrationRunner(CLOCK, database).run(Phase.EXPAND)).isEqualTo(8);

        assertThat(jdbc.queryForObject("SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'MODE'", String.class)).isEqualTo("REGULAR");
        assertThat(history(jdbc, Phase.EXPAND)).containsExactly("1/1/SUCCEEDED", "2/1/SUCCEEDED", "3/1/SUCCEEDED", "4/1/SUCCEEDED", "5/1/SUCCEEDED", "6/1/SUCCEEDED", "7/1/SUCCEEDED", "8/1/SUCCEEDED");
        assertThat(count(jdbc, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE'")).isEqualTo(25);
        assertThat(tableExists(jdbc, "PUBLIC", "flyway_schema_history")).isFalse();
    }

    @Test
    @DisplayName("S-D0109-21 / S4-AT-06: product scripts contain zero ${long_text}/CLOB/BLOB/BYTEA columns and every remaining character column has max length <= 4096")
    void noLongTextOrLobColumnsRemainInProductScripts() throws IOException {
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
        assertThat(placeholders).as("all 10 long-text columns moved to encrypted attachments (D-0092)").isZero();

        DataSource database = freshDatabase();
        new MigrationRunner(CLOCK, database).run(Phase.EXPAND);
        JdbcTemplate jdbc = new JdbcTemplate(database);
        List<String> lobColumns = jdbc.query("SELECT LOWER(TABLE_NAME) || '.' || LOWER(COLUMN_NAME), DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS "
            + "WHERE TABLE_SCHEMA = 'PUBLIC' ORDER BY 1",
            (rows, number) -> rows.getString(2).contains("LARGE OBJECT") || rows.getString(2).contains("BINARY") ? rows.getString(1) + ":" + rows.getString(2) : null)
            .stream().filter(java.util.Objects::nonNull).toList();
        assertThat(lobColumns).as("no CLOB/BLOB/BINARY columns in schema").isEmpty();

        List<String> oversizedCharColumns = jdbc.query("SELECT LOWER(TABLE_NAME) || '.' || LOWER(COLUMN_NAME), CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS "
            + "WHERE TABLE_SCHEMA = 'PUBLIC' AND CHARACTER_MAXIMUM_LENGTH IS NOT NULL ORDER BY 1",
            (rows, number) -> rows.getLong(2) > 4096L ? rows.getString(1) + "(" + rows.getLong(2) + ")" : null)
            .stream().filter(java.util.Objects::nonNull).toList();
        assertThat(oversizedCharColumns).as("every string column in V1..V8 is bounded to <= 4096 characters (S4-AT-06)").isEmpty();
    }
}
