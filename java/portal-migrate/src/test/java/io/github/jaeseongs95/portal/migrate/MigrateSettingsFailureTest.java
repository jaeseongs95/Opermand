package io.github.jaeseongs95.portal.migrate;

import static io.github.jaeseongs95.portal.migrate.LegacyJob.T0;
import static io.github.jaeseongs95.portal.migrate.LegacyJob.job;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.portal.migrate.MigrationKit.Run;
import io.github.jaeseongs95.portal.migrate.MigrationKit.Store;

/**
 * What the conversion of the settings does when something is wrong: nothing is written (not the settings file, not the store), the report names the line and never a value,
 * the 2.x files stay as they were, and a failure after the history was moved is repaired by running the command again.
 */
class MigrateSettingsFailureTest {

    private static final String EXECUTE = "{\"decision\":\"execute\",\"reasonCode\":\"command_set\",\"policyRevision\":1}";
    private static final String SECRET_ONE = "secret-one-5d3c1b9a7f";
    private static final String SECRET_TWO = "secret-two-e8c6a4f2d0";
    private static final String SECRET_THREE = "secret-three-13579bdf24";

    private final MigrationKit kit = new MigrationKit();

    private static String id(int number) {
        return String.format("%048x", number);
    }

    private String[] args(Path source, Path target, Path env, Path out, boolean dryRun, String... more) {
        List<String> args = new ArrayList<>(List.of(kit.args(source, target, dryRun)));
        args.add("--env-file=" + env);
        args.add("--env-out=" + out);
        args.addAll(List.of(more));
        return args.toArray(String[]::new);
    }

    private static LegacyJob done(int number) {
        return job(number).state("completed").times(T0, T0 + 1000, T0 + 2000).exited(0).decision(EXECUTE);
    }

    @Test
    void aLineThat2xRefusedStopsTheWholeRunNamesTheLineAndPrintsNoValue(@TempDir Path root) {
        Path env = LegacyEnvFixture.write(root.resolve("etc").resolve("bridge.env"), "BRIDGE_MCP_TOKEN=" + SECRET_ONE, "NOT_A_SETTING=" + SECRET_TWO,
            "this line is no entry " + SECRET_THREE, "lowercase_key=" + SECRET_ONE);
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(List.of(done(1)));
        Path target = root.resolve("v3");
        Path out = root.resolve("out.env");
        Map<String, String> before = kit.fingerprint(root);

        Run run = kit.run(args(legacy.folder(), target, env, out, false));

        assertThat(run.exitCode()).as(run.output()).isEqualTo(2);
        assertThat(run.output()).contains("CONFLICT SETTINGS_ENTRY").contains("line 2: NOT_A_SETTING is not a setting of 2.x, which refused it too")
            .contains("line 3 is not a KEY=value entry, which 2.x refused too").contains("line 4 is not a KEY=value entry, which 2.x refused too");
        assertThat(run.output()).doesNotContain(SECRET_ONE).doesNotContain(SECRET_TWO).doesNotContain(SECRET_THREE);
        assertThat(kit.fingerprint(root)).as("nothing was written anywhere: no settings file, no store, no change of the 2.x files").isEqualTo(before);
    }

    @Test
    void aHistoryConflictWritesNoSettingsFileEither(@TempDir Path root) {
        Path profile = LegacyEnvFixture.profileFile(root.resolve("etc").resolve("config.toml"));
        Path env = LegacyEnvFixture.write(root.resolve("etc").resolve("bridge.env"), LegacyEnvFixture.installerLines(profile));
        String sharedKey = "shared-key-" + id(7);
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(List.of(done(1).key(sharedKey), done(2).key(sharedKey)));
        Path target = root.resolve("v3");
        Path out = root.resolve("out.env");

        Run run = kit.run(args(legacy.folder(), target, env, out, false));

        assertThat(run.exitCode()).as(run.output()).isEqualTo(2);
        assertThat(run.output()).contains("CONFLICT REQUEST_KEY_SHARED").contains("settings file: not written, this run found a problem");
        assertThat(out).doesNotExist();
        assertThat(target).doesNotExist();
        LegacyEnvFixture.SECRETS.forEach(secret -> assertThat(run.output()).doesNotContain(secret));
    }

    @Test
    void aMoveThatWouldUseTheUnknownBudgetUpWritesNoSettingsFileAndNoStore(@TempDir Path root) {
        Path env = LegacyEnvFixture.write(root.resolve("etc").resolve("bridge.env"), "BRIDGE_MCP_PORT=39124");
        List<LegacyJob> unknown = new ArrayList<>();
        for (int number = 1; number <= 3; number++) {
            unknown.add(job(number).state("running").times(T0, T0 + 5, null).decision(EXECUTE));
        }
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(unknown);
        Path target = root.resolve("v3");
        Path out = root.resolve("out.env");

        Run refused = kit.run(args(legacy.folder(), target, env, out, false, "--unknown-budget=3"));

        assertThat(refused.exitCode()).as(refused.output()).isEqualTo(2);
        assertThat(refused.output()).contains("CONFLICT UNKNOWN_BUDGET").contains("settings file: not written");
        assertThat(out).doesNotExist();
        assertThat(target).doesNotExist();

        Run allowed = kit.run(args(legacy.folder(), target, env, out, false, "--unknown-budget=4"));

        assertThat(allowed.exitCode()).as(allowed.output()).isZero();
        assertThat(out).isRegularFile();
    }

    @Test
    void theEnvironmentFileCannotBeTheOutputAndTheTwoOptionsGoTogether(@TempDir Path root) {
        Path env = LegacyEnvFixture.write(root.resolve("etc").resolve("bridge.env"), "BRIDGE_MCP_TOKEN=" + SECRET_ONE);
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy"));
        Map<String, String> before = kit.fingerprint(root);

        Run same = kit.run(args(legacy.folder(), root.resolve("v3"), env, env, false));
        Run spelledDifferently = kit.run(args(legacy.folder(), root.resolve("v3"), env, root.resolve("etc").resolve("..").resolve("etc").resolve("bridge.env"), false));
        Run alone = kit.run(kit.args(legacy.folder(), root.resolve("v3"), false, "--env-file=" + env));

        assertThat(same.exitCode()).as(same.output()).isEqualTo(1);
        assertThat(same.output()).contains("--env-out is the 2.x environment file itself");
        assertThat(spelledDifferently.exitCode()).as(spelledDifferently.output()).isEqualTo(1);
        assertThat(alone.exitCode()).as(alone.output()).isEqualTo(1);
        assertThat(alone.output()).contains("--env-file and --env-out go together");
        assertThat(kit.fingerprint(root)).as("the 2.x environment file is byte for byte what it was").isEqualTo(before);
        assertThat(same.output() + spelledDifferently.output() + alone.output()).doesNotContain(SECRET_ONE);
    }

    @Test
    void aMissingOversizedOrNotUtf8EnvironmentFileIsAConflictNotAGuess(@TempDir Path root) throws IOException {
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy"));
        Path missing = root.resolve("etc").resolve("missing.env");
        Path big = LegacyEnvFixture.writeText(root.resolve("etc").resolve("big.env"), "BRIDGE_DISPLAY_NAME=" + "x".repeat(65536) + "\n");
        Path latin = root.resolve("etc").resolve("latin.env");
        Files.write(latin, "BRIDGE_DISPLAY_NAME=café\n".getBytes(StandardCharsets.ISO_8859_1));

        Run absent = kit.run(args(legacy.folder(), root.resolve("v3"), missing, root.resolve("out-1.env"), false));
        Run oversized = kit.run(args(legacy.folder(), root.resolve("v3"), big, root.resolve("out-2.env"), false));
        Run notUtf8 = kit.run(args(legacy.folder(), root.resolve("v3"), latin, root.resolve("out-3.env"), false));

        assertThat(absent.exitCode()).as(absent.output()).isEqualTo(2);
        assertThat(absent.output()).contains("CONFLICT SETTINGS_FILE").contains("is not a regular file (or it does not exist)");
        assertThat(oversized.exitCode()).as(oversized.output()).isEqualTo(2);
        assertThat(oversized.output()).contains("is larger than 65536 bytes, which 2.x refused too");
        assertThat(notUtf8.exitCode()).as(notUtf8.output()).isEqualTo(2);
        assertThat(notUtf8.output()).contains("the file is not well-formed UTF-8");
        for (String name : List.of("out-1.env", "out-2.env", "out-3.env")) {
            assertThat(root.resolve(name)).doesNotExist();
        }
    }

    @Test
    void aSettingsFileThatCannotBeWrittenFailsTheRunAfterTheHistoryIsMovedAndARerunRepairsIt(@TempDir Path root) throws IOException {
        Path env = LegacyEnvFixture.write(root.resolve("etc").resolve("bridge.env"), "BRIDGE_MCP_TOKEN=" + SECRET_ONE);
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(List.of(done(1), done(2)));
        Path target = root.resolve("v3");
        Path folder = root.resolve("settings");
        Path out = folder.resolve("bridge.env");
        Files.createDirectories(out.resolve("in-the-way"));

        Run failed = kit.run(args(legacy.folder(), target, env, out, false));

        assertThat(failed.exitCode()).as(failed.output()).isEqualTo(1);
        assertThat(failed.output()).contains("ERROR the settings file could not be written").contains("the history part of this run is done, run the command again").doesNotContain(SECRET_ONE);
        try (Stream<Path> files = Files.list(folder)) {
            assertThat(files.map(path -> path.getFileName().toString())).as("no staging file is left behind by the failure").containsExactly("bridge.env");
        }
        try (Store store = kit.open(target)) {
            assertThat(store.total()).as("the history is moved, as a run that is done with it leaves it").isEqualTo(2);
        }

        Files.delete(out.resolve("in-the-way"));
        Files.delete(out);
        Run repaired = kit.run(args(legacy.folder(), target, env, out, false));

        assertThat(repaired.exitCode()).as(repaired.output()).isZero();
        assertThat(repaired.output()).contains("already moved 2").contains("moved now: 0").contains("settings file: written, owner only");
        LegacyEnvFixture.assertOwnerOnly(out);
        try (Store store = kit.open(target)) {
            assertThat(store.total()).as("nothing was moved twice").isEqualTo(2);
        }
    }
}
