package io.github.jaeseongs95.opermand.store.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S-D0109-23: the reasons and the decisions are written where a maintainer looks: the runner's javadoc (why Flyway is not used) and {@code docs/migration/migration-runner.md}
 * (the reasons, the correspondence with the AGS runner, the list of differences from it, the choice of the expand execution, the tests that were removed). Paths are relative to
 * the module directory, the working directory of a Gradle test task.
 */
class MigrationDocumentTest {

    private static final Path DOCUMENT = Path.of("../docs/migration/migration-runner.md");
    private static final Path RUNNER = Path.of("src/main/java/io/github/jaeseongs95/opermand/store/migration/MigrationRunner.java");

    @Test
    @DisplayName("S-D0109-23: the document has the sections on Flyway, the differences, the correspondence, the expand choice and the removed tests, and the runner's javadoc says why Flyway is not used")
    void theReasonsAreWrittenDown() throws IOException {
        String document = Files.readString(DOCUMENT, StandardCharsets.UTF_8);
        assertThat(document).contains("## Flyway를 쓰지 않는 이유", "## 차이 목록", "## 대응표", "## expand 실행 방식 선택", "## 한계와 운영자 복구", "## 지운 시험", "## 긴 텍스트 열");
        assertThat(document).as("no progress notes of the work in a public document").doesNotContain("리드", "기다리는 중", "전달)", "진단 프로그램");
        assertThat(document).as("no local path").doesNotContain("D:/", "D:\\", "C:\\", "C:/");

        String javadoc = Files.readString(RUNNER, StandardCharsets.UTF_8);
        assertThat(javadoc).contains("Flyway").contains("flyway-database");
    }

    @Test
    @DisplayName("MigrationScript value equality compares script bytes by content and formats a compact summary")
    void migrationScriptEqualityByContent() {
        MigrationScript a = MigrationScript.of(1L, "V1__init.sql", "SELECT 1;".getBytes(StandardCharsets.UTF_8));
        MigrationScript b = MigrationScript.of(1L, "V1__init.sql", "SELECT 1;".getBytes(StandardCharsets.UTF_8));
        MigrationScript diffVersion = MigrationScript.of(2L, "V1__init.sql", "SELECT 1;".getBytes(StandardCharsets.UTF_8));
        MigrationScript diffFile = MigrationScript.of(1L, "V1__other.sql", "SELECT 1;".getBytes(StandardCharsets.UTF_8));
        MigrationScript diffBytes = MigrationScript.of(1L, "V1__init.sql", "SELECT 2;".getBytes(StandardCharsets.UTF_8));
        MigrationScript diffDigest = new MigrationScript(1L, "V1__init.sql", "SELECT 1;".getBytes(StandardCharsets.UTF_8), "other-digest");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b)
            .isNotEqualTo(diffVersion)
            .isNotEqualTo(diffFile)
            .isNotEqualTo(diffBytes)
            .isNotEqualTo(diffDigest)
            .isNotEqualTo("not-a-script");
        assertThat(a.toString()).contains("V1__init.sql").contains("bytes=9");
    }
}
