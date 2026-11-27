package io.github.jaeseongs95.opermand.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The spool of a command output holds the raw, unredacted standard output and error, and it is gone once the command has ended. The way that turned it into an artifact had
 * no caller and could only fail or, worse, hand out raw output; it was removed (defect D-0163). The test reads the sources of this module so that the way does not come
 * back under the same name; an output reaches an owner only through the two redacted renderings that {@code OpenOutput} stores.
 */
class RawSpoolImportRemovedTest {

    @Test
    @DisplayName("S-D0163-01 D-0163 no class of opermand-app imports the raw spool of an output into an artifact")
    void theRawSpoolIsNeverImportedIntoAnArtifact() throws IOException {
        Path sources = Path.of("src", "main", "java");
        assertThat(sources).as("the test runs in the folder of the module").isDirectory();
        try (Stream<Path> files = Files.walk(sources)) {
            List<String> offenders = files.filter(path -> path.toString().endsWith(".java")).filter(path -> {
                try {
                    String text = Files.readString(path, StandardCharsets.UTF_8);
                    return text.contains("importSpool") || text.contains("OutputStreamImport");
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).map(Path::toString).toList();
            assertThat(offenders).isEmpty();
        }
    }
}
