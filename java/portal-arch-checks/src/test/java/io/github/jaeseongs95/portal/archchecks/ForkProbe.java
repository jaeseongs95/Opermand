package io.github.jaeseongs95.portal.archchecks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Case S-D0111-04: Gradle starts as many test JVMs at once as -Pportal.test.forks states (one when it states none), and no more.
 * <p>
 * Every concrete probe class (ForkProbeATest to ForkProbeDTest) registers the process id of the JVM it runs in as a file under java.io.tmpdir (a folder the build
 * empties before each test run, and that the forks of one task share), then waits until min(stated forks, number of probe classes) different JVMs have registered. That
 * wait only ends when Gradle really runs that many JVMs at the same time, so a build that ignores the property fails by timeout. Afterwards no more than the stated number
 * of JVMs may have registered. The wait is for a directory event (WatchService), not a fixed sleep: no other synchronisation reaches across JVMs.
 */
abstract class ForkProbe {

    static final int PROBE_CLASSES = 4;
    private static final long TIMEOUT_SECONDS = 120;

    @Test
    void gradleRunsTheStatedNumberOfTestJvms() throws IOException, InterruptedException {
        // System.getProperty is allowed here: a JUnit test outside Spring checks what Gradle's test JVM setup hands over; A-ENV binds the product code.
        int stated = Integer.parseInt(System.getProperty("portal.test.stated.forks", "1"));
        int needed = Math.min(stated, PROBE_CLASSES);
        Path folder = Path.of(System.getProperty("java.io.tmpdir")).resolve("fork-probe");
        Files.createDirectories(folder);
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            folder.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
            try {
                Files.createFile(folder.resolve(Long.toString(ProcessHandle.current().pid())));
            } catch (FileAlreadyExistsException sameJvmRanAnotherProbe) {
                // The JVM already registered itself for an earlier probe class.
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (registered(folder) < needed) {
                long remaining = deadline - System.nanoTime();
                assertThat(remaining).as("only %d of %d test JVMs ran at the same time within %d s (stated forks %d)", registered(folder), needed, TIMEOUT_SECONDS, stated)
                        .isPositive();
                WatchKey key = watcher.poll(remaining, TimeUnit.NANOSECONDS);
                if (key != null) {
                    key.pollEvents();
                    key.reset();
                }
            }
        }
        assertThat(registered(folder)).as("test JVMs started, stated forks %d", stated).isBetween(needed, stated);
    }

    private static long registered(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.count();
        }
    }
}
