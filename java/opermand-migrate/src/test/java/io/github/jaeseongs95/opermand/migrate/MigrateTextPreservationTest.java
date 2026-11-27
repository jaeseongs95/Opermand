package io.github.jaeseongs95.opermand.migrate;

import static io.github.jaeseongs95.opermand.migrate.LegacyJob.T0;
import static io.github.jaeseongs95.opermand.migrate.LegacyJob.job;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.opermand.domain.operation.CommandInput;
import io.github.jaeseongs95.opermand.migrate.MigrationKit.Run;
import io.github.jaeseongs95.opermand.migrate.MigrationKit.Store;

/**
 * Requirement 5 (defect D-0015): the UTF-8 bytes of the command and of its two descriptions are kept as they were, whatever the byte position of a multibyte character is.
 * The commands put a character of two, three and four bytes so that it starts one, two or three bytes before each of the buffer sizes a reader might use (1 KiB to 32 KiB),
 * so that it is split by a chunk of that size if anything reads the text in chunks. The oracle is the text the test wrote, compared as bytes.
 */
class MigrateTextPreservationTest {

    private static final String EXECUTE = "{\"decision\":\"execute\",\"reasonCode\":\"command_set\",\"policyRevision\":1}";
    private static final int[] BOUNDARIES = {1024, 4096, 8192, 16384, 32000};
    private static final String[] GLYPHS = {"é", "한", "😀"};

    private final MigrationKit kit = new MigrationKit();

    @Test
    void aMultibyteCharacterOnAnyChunkBoundaryComesBackByteForByte(@TempDir Path root) {
        List<LegacyJob> jobs = new ArrayList<>();
        Map<String, String> expected = new java.util.LinkedHashMap<>();
        int number = 1;
        for (int boundary : BOUNDARIES) {
            for (String glyph : GLYPHS) {
                int length = glyph.getBytes(StandardCharsets.UTF_8).length;
                for (int before = 1; before < length; before++) {
                    // The glyph starts "before" bytes before the boundary, so the boundary falls inside its bytes.
                    String command = "echo " + "a".repeat(boundary - 5 - before) + glyph + "z".repeat(40);
                    LegacyJob job = job(number).state("completed").times(T0, T0 + 1, T0 + 2).exited(0).decision(EXECUTE).command(command);
                    jobs.add(job);
                    expected.put(job.id(), command);
                    number++;
                }
            }
        }
        // The longest command 3.0 takes (32768 bytes) made only of four-byte characters, and layout (LF, CR LF, TAB) that must not be touched.
        String longest = "echo " + "😀".repeat((32768 - 5) / 4);
        String layout = "line one\r\nline two\n\tindented 한글\nlast";
        jobs.add(job(number).state("completed").times(T0, T0 + 1, T0 + 2).exited(0).decision(EXECUTE).command(longest));
        expected.put(jobs.getLast().id(), longest);
        jobs.add(job(number + 1).state("completed").times(T0, T0 + 1, T0 + 2).exited(0).decision(EXECUTE).command(layout));
        expected.put(jobs.getLast().id(), layout);
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(jobs);
        Path target = root.resolve("v3");
        assertThat(longest.getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(32768);

        Run run = kit.run(kit.importArgs(legacy.folder(), target));

        assertThat(run.exitCode()).as(run.output()).isZero();
        try (Store store = kit.open(target)) {
            assertThat(store.total()).isEqualTo(expected.size());
            for (Map.Entry<String, String> entry : expected.entrySet()) {
                String migrated = store.commandOf(entry.getKey());
                assertThat(migrated.getBytes(StandardCharsets.UTF_8)).as("command of %s", entry.getKey()).isEqualTo(entry.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void theContextTextsAreKeptByteForByteToo(@TempDir Path root) {
        String context = "배포 전 확인 🚀 " + "é".repeat(3000) + "\n둘째 줄\r\n\t끝";
        String display = "표시용 설명 " + "한".repeat(5000);
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(List.of(
            job(1).state("completed").times(T0, T0 + 1, T0 + 2).exited(0).decision(EXECUTE).context(context).displayContext(display)));
        Path target = root.resolve("v3");

        Run run = kit.run(kit.importArgs(legacy.folder(), target));

        assertThat(run.exitCode()).as(run.output()).isZero();
        try (Store store = kit.open(target)) {
            CommandInput input = (CommandInput) store.byId(String.format("%048x", 1)).input();
            assertThat(input.callerContext().value().getBytes(StandardCharsets.UTF_8)).isEqualTo(context.getBytes(StandardCharsets.UTF_8));
            assertThat(input.displayContext().value().getBytes(StandardCharsets.UTF_8)).isEqualTo(display.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void aBodyThatIsNotWellFormedUtf8IsReportedAndNeverRepaired(@TempDir Path root) {
        // 0xC3 0x28 is not UTF-8: the byte after a lead byte must be a continuation byte.
        byte[] broken = {'{', '"', 'i', 'd', '"', ':', '"', (byte) 0xC3, 0x28, '"', '}'};
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).rawRequestRow("agent-cli.sqlite", String.format("%048x", 1), broken);
        Path target = root.resolve("v3");

        Run run = kit.run(kit.importArgs(legacy.folder(), target));

        assertThat(run.exitCode()).as(run.output()).isEqualTo(2);
        assertThat(run.output()).contains("UNREPRESENTABLE_ROW").contains("agent-cli.sqlite#" + String.format("%048x", 1)).contains("not well-formed UTF-8");
        assertThat(target).as("nothing was created for a folder that cannot be moved").doesNotExist();
    }
}
