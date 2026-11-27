package io.github.jaeseongs95.opermand.store.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S-D0109-08: the runner has no sleep, no poll and no retry: the database makes a second runner wait (a lock wait on the claim row) and the duplicate key it then gets is the
 * answer. The check reads the main sources of the package ({@code src/main/java}, relative to the module directory, which is the working directory of a Gradle test task)
 * with the comments removed.
 */
class MigrationRunnerSourceTest {

    private static final Path SOURCES = Path.of("src/main/java/io/github/jaeseongs95/portal/store/migration");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
    private static final Pattern WAITING = Pattern.compile("\\bsleep\\b|\\bThread\\.|\\bRetry|\\bTimeUnit\\b|\\bExecutor|\\bTimer\\b|\\bAwaitility\\b|\\bScheduled|\\bwait\\s*\\(|\\bLockSupport\\b");

    @Test
    @DisplayName("S-D0109-08: the sources of the migration package have no sleep, thread, retry, timer or executor")
    void noSleepNoPollNoRetry() throws IOException {
        List<String> offenders = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> found = Files.list(SOURCES)) {
            files = found.filter(path -> path.toString().endsWith(".java")).toList();
        }
        assertThat(files).as("the migration sources were found").hasSizeGreaterThanOrEqualTo(6);
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String code = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(text).replaceAll("")).replaceAll("");
            if (WAITING.matcher(code).find()) {
                offenders.add(file.getFileName().toString());
            }
        }
        assertThat(offenders).isEmpty();
    }

    private static final String PRODUCTS = "(?:H2|POSTGRESQL|ORACLE|DB2|SQLSERVER|MARIADB|MYSQL)";
    private static final Pattern DBMS_SPECIFIC = Pattern.compile("\\bScriptDialect\\b|\\bLongTextType\\b|\\bgetTypeInfo\\b|\\bcase\\s+" + PRODUCTS + "\\b|\\bDatabase\\." + PRODUCTS + "\\b");

    @Test
    @DisplayName("S-D0109-14: the sources of the migration package have no DBMS-specific branch (no ScriptDialect, no getTypeInfo choice, no case or constant of a product): the dialect is SqlBuilder's")
    void noDbmsSpecificBranch() throws IOException {
        assertThat(SOURCES.resolve("ScriptDialect.java")).as("ScriptDialect is not ported").doesNotExist();
        assertThat(SOURCES.resolve("LongTextType.java")).as("the long text type is not chosen from the driver's type info").doesNotExist();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> found = Files.list(SOURCES)) {
            for (Path file : found.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                String code = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(text).replaceAll("")).replaceAll("");
                if (DBMS_SPECIFIC.matcher(code).find()) {
                    offenders.add(file.getFileName().toString());
                }
            }
        }
        assertThat(offenders).isEmpty();
    }
}
