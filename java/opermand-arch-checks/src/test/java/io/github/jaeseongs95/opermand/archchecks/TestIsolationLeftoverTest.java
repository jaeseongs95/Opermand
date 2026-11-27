package io.github.jaeseongs95.opermand.archchecks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Case S-D0111-06: the isolated home and temporary folders of an earlier test run are emptied before the next run starts (the root build.gradle.kts does it in the
 * first action of every Test task). This class leaves a file in each folder when it ends and expects none when it starts, so a second run of the same Test task
 * (./gradlew :opermand-arch-checks:test --rerun) fails when the build does not empty the folders. A first run in a clean tree passes by itself.
 */
class TestIsolationLeftoverTest {

    private static final String MARKER = "earlier-run.marker";

    // System.getProperty is allowed in this class: a JUnit test outside Spring checks what Gradle's test JVM setup hands over; A-ENV binds the product code.
    private static List<Path> markers() {
        return List.of(Path.of(System.getProperty("user.home")).resolve(MARKER), Path.of(System.getProperty("java.io.tmpdir")).resolve(MARKER));
    }

    @BeforeAll
    static void nothingOfAnEarlierRunIsLeft() {
        assertThat(markers()).noneMatch(Files::exists);
    }

    @Test
    void thisRunLeavesItsMarkers() {
        assertThat(markers()).allMatch(marker -> marker.getParent().toFile().isDirectory());
    }

    @AfterAll
    static void leaveMarkersForTheNextRun() throws IOException {
        for (Path marker : markers()) {
            Files.writeString(marker, "left by a test run");
        }
    }
}
