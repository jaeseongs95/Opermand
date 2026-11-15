package io.github.jaeseongs95.portal.archchecks;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Cases S-D0111-01 to S-D0111-03: the processors and the heap of the test JVM that Gradle forked are the ones the run stated with -Pportal.test.cpus and
 * -Pportal.test.heap, and nothing when it stated none (Principles 7.4: the repository does not fix the sizes of this computer). The test looks at the JVM it runs in
 * (its command line and Runtime), not at the build script. The build hands over what the run stated as the system properties portal.test.stated.cpus and
 * portal.test.stated.heap, and leaves them out when the run stated nothing.
 */
class TestJvmSizeTest {

    private static final Pattern SIZE = Pattern.compile("([1-9][0-9]*)([kKmMgG]?)");

    // System.getProperty is allowed in this class: a JUnit test outside Spring checks what Gradle's test JVM setup hands over; A-ENV binds the product code.
    private static final String STATED_CPUS = System.getProperty("portal.test.stated.cpus");
    private static final String STATED_HEAP = System.getProperty("portal.test.stated.heap");

    private static List<String> jvmArguments() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments();
    }

    @Test
    void theProcessorCountIsTheStatedOneAndOtherwiseNotLimited() {
        List<String> flags = jvmArguments().stream().filter(argument -> argument.startsWith("-XX:ActiveProcessorCount")).toList();
        if (STATED_CPUS == null) {
            assertThat(flags).as("no -Pportal.test.cpus was given, so the JVM must not be limited").isEmpty();
        } else {
            assertThat(flags).containsExactly("-XX:ActiveProcessorCount=" + STATED_CPUS);
            assertThat(Runtime.getRuntime().availableProcessors()).isEqualTo(Integer.parseInt(STATED_CPUS));
        }
    }

    @Test
    void theHeapIsTheStatedOneAndOtherwiseGradlesOwnDefault() {
        List<String> flags = jvmArguments().stream().filter(argument -> argument.startsWith("-Xmx")).toList();
        if (STATED_HEAP == null) {
            // Gradle gives a test JVM 512m when none is stated (Test.maxHeapSize); the build adds no -Xmx of its own.
            assertThat(flags).isSubsetOf("-Xmx512m");
        } else {
            assertThat(flags).containsExactly("-Xmx" + STATED_HEAP);
            long stated = bytes(STATED_HEAP);
            // The collector may leave one survivor space out of Runtime.maxMemory, so the value lies a little under -Xmx, never over.
            assertThat(Runtime.getRuntime().maxMemory()).isLessThanOrEqualTo(stated).isGreaterThan(stated / 10 * 9);
        }
    }

    private static long bytes(String size) {
        Matcher matcher = SIZE.matcher(size);
        assertThat(matcher.matches()).as(size).isTrue();
        long number = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2).toLowerCase(java.util.Locale.ROOT)) {
            case "k" -> number * 1024;
            case "m" -> number * 1024 * 1024;
            case "g" -> number * 1024 * 1024 * 1024;
            default -> number;
        };
    }
}
