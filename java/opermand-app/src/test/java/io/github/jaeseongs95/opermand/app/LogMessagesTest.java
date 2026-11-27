package io.github.jaeseongs95.opermand.app;

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
 * Principles 6: a log message of this module is a constant text with SLF4J placeholders, never a string that was put together with {@code +} (defect D-0101). The
 * placeholder form keeps the message a constant for the log tools and lets the logger skip the work when the level is off; the throwable stays the last argument.
 * The test reads the sources of this module, because no rule on the compiled classes can see a concatenation that the compiler folded into an invokedynamic call.
 */
class LogMessagesTest {

    private static final Pattern LOG_CALL = Pattern.compile("\\bLOG\\.(?:trace|debug|info|warn|error)\\([^;]*;", Pattern.DOTALL);
    private static final Pattern CONCATENATION = Pattern.compile("\"\\s*\\+|\\+\\s*\"");

    @Test
    @DisplayName("S-D0101-01 D-0101 no log call of opermand-app builds its message with +; every message is a constant text with {} placeholders")
    void noLogMessageIsConcatenated() throws IOException {
        Path sources = Path.of("src", "main", "java");
        assertThat(sources).as("the test runs in the folder of the module").isDirectory();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                var calls = LOG_CALL.matcher(text);
                while (calls.find()) {
                    if (CONCATENATION.matcher(calls.group()).find()) {
                        violations.add(file + ": " + calls.group().lines().findFirst().orElse(""));
                    }
                }
            }
        }
        assertThat(violations).isEmpty();
    }
}
