package io.github.jaeseongs95.portal.archchecks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The additional terms under AGPL section 7 are part of the repository (docs/migration/regression-cases.part-b.md, cases S-LIC-01 to S-LIC-04): the English text
 * is the effective one, the Korean text a translation, and neither may keep a template placeholder. The source file headers are enforced by Spotless (S-LIC-05).
 * Run alone: ./gradlew :portal-arch-checks:test --tests '*LicenseTermsTest'
 */
class LicenseTermsTest {

    /** The repository root is the parent of the Gradle build folder ({@code java/}), which the build passes as portal.arch.root. */
    private static final Path REPOSITORY = Path.of(System.getProperty("portal.arch.root")).getParent();

    private static String read(String name) throws IOException {
        Path file = REPOSITORY.resolve(name);
        assertThat(file).as(name + " exists in the repository root").isRegularFile();
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    void bothEditionsExistAndTheKoreanOneStatesThatTheEnglishOneGoverns() throws IOException {
        assertThat(read("ADDITIONAL-TERMS.md")).isNotBlank();
        assertThat(read("ADDITIONAL-TERMS.ko.md")).contains("영문판이 우선한다");
    }

    @Test
    void noTemplatePlaceholderRemainsAndTheProductNamesAreFilled() throws IOException {
        for (String name : new String[] {"ADDITIONAL-TERMS.md", "ADDITIONAL-TERMS.ko.md"}) {
            String text = read(name);
            assertThat(text).as(name).doesNotContain("{PRODUCT}").doesNotContain("{FORMER_NAMES}");
            assertThat(text).as(name).contains("SSH Approval Portal").contains("SSH Approval Bridge");
        }
    }

    @Test
    void theCopyrightHolderIsNamedInBothEditions() throws IOException {
        assertThat(read("ADDITIONAL-TERMS.md")).contains("Jaeseong Song");
        assertThat(read("ADDITIONAL-TERMS.ko.md")).contains("Jaeseong Song");
    }

    @Test
    void bothReadmesPointTheLicenseSectionAtTheAdditionalTerms() throws IOException {
        assertThat(read("README.md")).contains("ADDITIONAL-TERMS.md");
        assertThat(read("README.ko.md")).contains("ADDITIONAL-TERMS.ko.md");
    }
}
