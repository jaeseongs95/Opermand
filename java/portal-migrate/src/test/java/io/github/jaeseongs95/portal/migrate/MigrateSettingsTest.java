package io.github.jaeseongs95.portal.migrate;

import static io.github.jaeseongs95.portal.migrate.LegacyEnvFixture.ADMIN_ORIGIN;
import static io.github.jaeseongs95.portal.migrate.LegacyEnvFixture.ADMIN_TOKEN;
import static io.github.jaeseongs95.portal.migrate.LegacyEnvFixture.JEV_KEY;
import static io.github.jaeseongs95.portal.migrate.LegacyEnvFixture.MCP_TOKEN;
import static io.github.jaeseongs95.portal.migrate.LegacyEnvFixture.PROFILE_PASSWORD;
import static io.github.jaeseongs95.portal.migrate.LegacyEnvFixture.TARGETS;
import static io.github.jaeseongs95.portal.migrate.LegacyEnvFixture.TICKET_KEY;
import static io.github.jaeseongs95.portal.migrate.LegacyJob.T0;
import static io.github.jaeseongs95.portal.migrate.LegacyJob.job;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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
 * The 2.x environment file (settings and secrets) turned into the 3.0 settings file: what is carried over as written, what 3.0 dropped, what the operator still has to set, that
 * the file is private to its owner on whichever file system the test runs, that a rerun and a dry run behave, and that nothing secret is ever printed. The 2.x files are made by
 * the test with invented values; the expected 3.0 text is written out here.
 */
class MigrateSettingsTest {

    private static final String EXECUTE = "{\"decision\":\"execute\",\"reasonCode\":\"command_set\",\"policyRevision\":1}";
    private static final String HEADER = "# 3.0 settings made by portal-migrate from a 2.x environment file; read it before the server uses it\n";

    private final MigrationKit kit = new MigrationKit();

    private static String dataDir(Path target) {
        return target.toAbsolutePath().toString().replace('\\', '/');
    }

    private static String[] settingsArgs(MigrationKit kit, Path source, Path target, Path env, Path out, boolean dryRun, String... more) {
        List<String> args = new ArrayList<>(List.of(kit.args(source, target, dryRun)));
        args.add("--env-file=" + env);
        args.add("--env-out=" + out);
        args.addAll(List.of(more));
        return args.toArray(String[]::new);
    }

    /** The 3.0 file the installer-shaped 2.x file becomes: every kept line as it was written, none of the five dropped keys, the data folder last. */
    private static String expectedFor(Path profile, Path target) {
        return HEADER
            + "BRIDGE_MCP_TOKEN=" + MCP_TOKEN + "\n"
            + "BRIDGE_ADMIN_TOKEN=" + ADMIN_TOKEN + "\n"
            + "BRIDGE_UI_TICKET_KEY=" + TICKET_KEY + "\n"
            + "BRIDGE_ADMIN_ORIGIN=" + ADMIN_ORIGIN + "\n"
            + "BRIDGE_DISPLAY_NAME=\"Example Bridge\"\n"
            + "BRIDGE_UI_ENABLED=false\n"
            + "BRIDGE_CONFIG_PATH=" + profile.toString().replace('\\', '/') + "\n"
            + "BRIDGE_TARGETS=" + TARGETS + "\n"
            + "BRIDGE_ADMIN_AUTH_MODE=token\n"
            + "SSH_MCP_ALPHA_WRITE_PASSWORD=" + PROFILE_PASSWORD + "\n"
            + "JEV_API_KEY=" + JEV_KEY + "\n"
            + "BRIDGE_DATA_DIR=" + dataDir(target) + "\n";
    }

    private static void assertNothingSecretWasPrinted(String output) {
        for (String secret : LegacyEnvFixture.SECRETS) {
            assertThat(output).as("the report never holds a setting value").doesNotContain(secret);
        }
        assertThat(output).doesNotContain(LegacyEnvFixture.OWNER_EMAIL);
    }

    @Test
    void theSettingsAreCarriedOverAsWrittenTheDroppedKeysAreLeftOutAndTheHistoryMovesWithThem(@TempDir Path root) {
        Path profile = LegacyEnvFixture.profileFile(root.resolve("etc").resolve("config.toml"));
        Path env = LegacyEnvFixture.write(root.resolve("etc").resolve("bridge.env"), LegacyEnvFixture.installerLines(profile));
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(List.of(job(1).state("completed").times(T0, T0 + 1000, T0 + 2000).exited(0).decision(EXECUTE)));
        Path target = root.resolve("v3");
        Path out = root.resolve("v3-settings").resolve("bridge.env");
        Map<String, String> sourcesBefore = kit.fingerprint(root.resolve("etc"));

        Run run = kit.run(settingsArgs(kit, legacy.folder(), target, env, out, false));

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(LegacyEnvFixture.read(out)).isEqualTo(expectedFor(profile, target));
        LegacyEnvFixture.assertOwnerOnly(out);
        assertThat(run.output()).contains("carried over unchanged (11): BRIDGE_MCP_TOKEN, BRIDGE_ADMIN_TOKEN, BRIDGE_UI_TICKET_KEY, BRIDGE_ADMIN_ORIGIN, BRIDGE_DISPLAY_NAME,"
            + " BRIDGE_UI_ENABLED, BRIDGE_CONFIG_PATH, BRIDGE_TARGETS, BRIDGE_ADMIN_AUTH_MODE, SSH_MCP_ALPHA_WRITE_PASSWORD, JEV_API_KEY")
            .contains("removed in 3.0, not carried over: BRIDGE_TRANSFER_ROOT").contains("removed in 3.0, not carried over: BRIDGE_AUDIT_PATH")
            .contains("removed in 3.0, not carried over: BRIDGE_ACCESS_TEAM_DOMAIN").contains("removed in 3.0, not carried over: BRIDGE_ACCESS_AUD")
            .contains("removed in 3.0, not carried over: BRIDGE_ACCESS_OWNER_EMAILS").contains("added: BRIDGE_DATA_DIR is the --target folder")
            .contains("settings file: written, owner only").contains("moved now: 1");
        assertNothingSecretWasPrinted(run.output());
        assertThat(kit.fingerprint(root.resolve("etc"))).as("the 2.x environment file and the profile file are byte for byte what they were").isEqualTo(sourcesBefore);
        assertThat(target.resolve("portal.mv.db")).as("the store is the file the server opens in its data folder (BRIDGE_DATA_DIR)").isRegularFile();
        try (Store store = kit.open(target)) {
            assertThat(store.total()).isEqualTo(1);
        }
    }

    @Test
    void theNoteAboutTheSshProfileFileSaysItIsUsedWhereItIs(@TempDir Path root) {
        Path profile = LegacyEnvFixture.profileFile(root.resolve("etc").resolve("config.toml"));
        Path env = LegacyEnvFixture.write(root.resolve("etc").resolve("bridge.env"), LegacyEnvFixture.installerLines(profile));
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy"));

        Run run = kit.run(settingsArgs(kit, legacy.folder(), root.resolve("v3"), env, root.resolve("out.env"), true));

        assertThat(run.output()).contains("the SSH profile file: 3.0 reads the same TOML format from the path BRIDGE_CONFIG_PATH names, so the file is used where it is")
            .contains("BRIDGE_TARGETS is carried over as written");
    }

    @Test
    void aMissingOrEmptyProfileFileIsNamedBecauseThe3ServerRefusesIt(@TempDir Path root) {
        Path missing = root.resolve("etc").resolve("missing.toml");
        Path env = LegacyEnvFixture.write(root.resolve("etc").resolve("bridge.env"), "BRIDGE_CONFIG_PATH=" + missing.toString().replace('\\', '/'));
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy"));

        Run absent = kit.run(settingsArgs(kit, legacy.folder(), root.resolve("v3"), env, root.resolve("out-1.env"), true));

        assertThat(absent.exitCode()).as(absent.output()).isZero();
        assertThat(absent.output()).contains("BRIDGE_CONFIG_PATH names no regular file here; 3.0 refuses to start without it");

        Path empty = LegacyEnvFixture.writeText(root.resolve("etc").resolve("empty.toml"), "");
        Path env2 = LegacyEnvFixture.write(root.resolve("etc").resolve("bridge-2.env"), "BRIDGE_CONFIG_PATH=" + empty.toString().replace('\\', '/'));

        Run emptyRun = kit.run(settingsArgs(kit, legacy.folder(), root.resolve("v3"), env2, root.resolve("out-2.env"), true));

        assertThat(emptyRun.output()).contains("the SSH profile file: it is empty, which 3.0 refuses");
    }

    @Test
    void aDryRunWritesNoSettingsFileAndSaysWhatARealRunWouldDo(@TempDir Path root) {
        Path profile = LegacyEnvFixture.profileFile(root.resolve("etc").resolve("config.toml"));
        Path env = LegacyEnvFixture.write(root.resolve("etc").resolve("bridge.env"), LegacyEnvFixture.installerLines(profile));
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(List.of(job(1).state("completed").times(T0, T0 + 1000, T0 + 2000).exited(0).decision(EXECUTE)));
        Path target = root.resolve("v3");
        Path out = root.resolve("v3-settings").resolve("bridge.env");
        Map<String, String> before = kit.fingerprint(root);

        Run run = kit.run(settingsArgs(kit, legacy.folder(), target, env, out, true));

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).contains("dry run, nothing is written").contains("settings file: would be written, owner only").contains("carried over unchanged (11)");
        assertThat(out).as("a dry run writes no settings file").doesNotExist();
        assertThat(out.getParent()).as("and creates no folder for it").doesNotExist();
        assertThat(target).doesNotExist();
        assertThat(kit.fingerprint(root)).as("not one file of the whole tree changed").isEqualTo(before);
        assertNothingSecretWasPrinted(run.output());
    }

    @Test
    void aRerunLeavesTheSettingsFileAndTheStoreAsTheyAre(@TempDir Path root) throws IOException {
        Path profile = LegacyEnvFixture.profileFile(root.resolve("etc").resolve("config.toml"));
        Path env = LegacyEnvFixture.write(root.resolve("etc").resolve("bridge.env"), LegacyEnvFixture.installerLines(profile));
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(List.of(job(1).state("completed").times(T0, T0 + 1000, T0 + 2000).exited(0).decision(EXECUTE)));
        Path target = root.resolve("v3");
        Path out = root.resolve("v3-settings").resolve("bridge.env");
        assertThat(kit.run(settingsArgs(kit, legacy.folder(), target, env, out, false)).exitCode()).isZero();
        String written = LegacyEnvFixture.read(out);
        var modified = Files.getLastModifiedTime(out);

        Run again = kit.run(settingsArgs(kit, legacy.folder(), target, env, out, false));

        assertThat(again.exitCode()).as(again.output()).isZero();
        assertThat(again.output()).contains("settings file: already there with exactly these settings, left as it is").contains("already moved 1").contains("moved now: 0");
        assertThat(LegacyEnvFixture.read(out)).isEqualTo(written);
        assertThat(Files.getLastModifiedTime(out)).as("the file was not written again").isEqualTo(modified);
        try (Stream<Path> files = Files.list(out.getParent())) {
            assertThat(files.map(path -> path.getFileName().toString())).as("no staging file is left beside the settings file").containsExactly("bridge.env");
        }
    }

    @Test
    void aSettingsFileTheOperatorCompletedIsNeverReplaced(@TempDir Path root) throws IOException {
        Path profile = LegacyEnvFixture.profileFile(root.resolve("etc").resolve("config.toml"));
        Path env = LegacyEnvFixture.write(root.resolve("etc").resolve("bridge.env"), LegacyEnvFixture.installerLines(profile));
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy"));
        Path target = root.resolve("v3");
        Path out = root.resolve("v3-settings").resolve("bridge.env");
        assertThat(kit.run(settingsArgs(kit, legacy.folder(), target, env, out, false)).exitCode()).isZero();
        String completed = LegacyEnvFixture.read(out) + "BRIDGE_OIDC_OWNER_SUBJECT=subject-invented-0001\n";
        Files.writeString(out, completed);

        Run again = kit.run(settingsArgs(kit, legacy.folder(), target, env, out, false));

        assertThat(again.exitCode()).as(again.output()).isZero();
        assertThat(again.output()).contains("settings file: there with other content (completed by hand?), left as it is");
        assertThat(LegacyEnvFixture.read(out)).isEqualTo(completed);
    }

    @Test
    void theSettingsAloneAreConvertedWhenTheFolderHasNoJournal(@TempDir Path root) {
        Path profile = LegacyEnvFixture.profileFile(root.resolve("etc").resolve("config.toml"));
        Path env = LegacyEnvFixture.write(root.resolve("etc").resolve("bridge.env"), LegacyEnvFixture.installerLines(profile));
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy"));
        Path target = root.resolve("v3");
        Path out = root.resolve("bridge-3.env");

        Run run = kit.run(settingsArgs(kit, legacy.folder(), target, env, out, false));

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(LegacyEnvFixture.read(out)).isEqualTo(expectedFor(profile, target));
        assertThat(run.output()).doesNotContain("moved now");
        assertThat(target).as("there was no history, so no store is made").doesNotExist();
    }

    @Test
    void quotesStayAsWrittenAndALaterDuplicateWinsAsItDidIn2x(@TempDir Path root) {
        Path env = LegacyEnvFixture.write(root.resolve("etc").resolve("bridge.env"), "BRIDGE_DISPLAY_NAME='Single quoted # not a comment'", "BRIDGE_TARGET_DISPLAY_NAME=  padded value  ",
            "BRIDGE_MCP_PORT=1111", "BRIDGE_ADMIN_PORT=2222", "BRIDGE_MCP_PORT=3333");
        Path out = root.resolve("out.env");

        Run run = kit.run(settingsArgs(kit, new LegacyFixture(root.resolve("legacy")).folder(), root.resolve("v3"), env, out, false));

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(LegacyEnvFixture.read(out)).isEqualTo(HEADER + "BRIDGE_DISPLAY_NAME='Single quoted # not a comment'\n" + "BRIDGE_TARGET_DISPLAY_NAME=padded value\n"
            + "BRIDGE_ADMIN_PORT=2222\n" + "BRIDGE_MCP_PORT=3333\n" + "BRIDGE_DATA_DIR=" + dataDir(root.resolve("v3")) + "\n");
        assertThat(run.output()).contains("written more than once in the 2.x file, the last one is carried over (as 2.x read it): BRIDGE_MCP_PORT");
    }

    @Test
    void aFileSavedWithWindowsLineEndsConvertsToTheSameSettingsAndReportsWithLfOnly(@TempDir Path root) {
        Path profile = LegacyEnvFixture.profileFile(root.resolve("etc").resolve("config.toml"));
        Path env = LegacyEnvFixture.writeCrLf(root.resolve("etc").resolve("bridge.env"), LegacyEnvFixture.installerLines(profile));
        Path target = root.resolve("v3");
        Path out = root.resolve("out.env");

        Run run = kit.run(settingsArgs(kit, new LegacyFixture(root.resolve("legacy")).folder(), target, env, out, false));

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(LegacyEnvFixture.read(out)).as("LF whatever the 2.x file used, so the 3.0 file is the same on every system").isEqualTo(expectedFor(profile, target)).doesNotContain("\r");
        assertThat(run.output()).as("the report ends its lines with LF on every system").doesNotContain("\r");
    }

    @Test
    void theOidcModeNeedsTheOwnerAccountAndTheClientSecretWhichOnlyTheOperatorCanGive(@TempDir Path root) {
        Path env = LegacyEnvFixture.write(root.resolve("etc").resolve("bridge.env"), "BRIDGE_ADMIN_AUTH_MODE=oidc", "BRIDGE_OIDC_ISSUER=https://issuer.example.test", "BRIDGE_OIDC_CLIENT_ID=client-invented-1",
            "BRIDGE_OIDC_JWKS_URL=https://issuer.example.test/jwks", "BRIDGE_OIDC_OWNER_EMAILS=" + LegacyEnvFixture.OWNER_EMAIL);
        Path out = root.resolve("out.env");

        Run run = kit.run(settingsArgs(kit, new LegacyFixture(root.resolve("legacy")).folder(), root.resolve("v3"), env, out, false));

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(LegacyEnvFixture.read(out)).isEqualTo(HEADER + "BRIDGE_ADMIN_AUTH_MODE=oidc\n" + "BRIDGE_OIDC_ISSUER=https://issuer.example.test\n" + "BRIDGE_OIDC_CLIENT_ID=client-invented-1\n"
            + "BRIDGE_DATA_DIR=" + dataDir(root.resolve("v3")) + "\n");
        assertThat(run.output()).contains("removed in 3.0, not carried over: BRIDGE_OIDC_JWKS_URL").contains("removed in 3.0, not carried over: BRIDGE_OIDC_OWNER_EMAILS")
            .contains("ACTION REQUIRED set BRIDGE_OIDC_OWNER_SUBJECT").contains("BRIDGE_OIDC_CLIENT_SECRET");
        assertThat(LegacyEnvFixture.read(out)).as("nothing is made up for the two keys 3.0 added").doesNotContain("BRIDGE_OIDC_OWNER_SUBJECT").doesNotContain("BRIDGE_OIDC_CLIENT_SECRET");
        assertThat(run.output()).doesNotContain(LegacyEnvFixture.OWNER_EMAIL);
    }

    @Test
    void the2xAccessModeHasNoEquivalentSoItIsLeftOutAndTheOperatorIsToldToChoose(@TempDir Path root) {
        Path env = LegacyEnvFixture.write(root.resolve("etc").resolve("bridge.env"), "BRIDGE_ADMIN_AUTH_MODE=access", "BRIDGE_ACCESS_TEAM_DOMAIN=https://team.example.test",
            "BRIDGE_ACCESS_AUD=" + "b".repeat(64), "BRIDGE_ACCESS_OWNER_EMAILS=" + LegacyEnvFixture.OWNER_EMAIL, "BRIDGE_ADMIN_ORIGIN=" + ADMIN_ORIGIN);
        Path out = root.resolve("out.env");

        Run run = kit.run(settingsArgs(kit, new LegacyFixture(root.resolve("legacy")).folder(), root.resolve("v3"), env, out, false));

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(LegacyEnvFixture.read(out)).isEqualTo(HEADER + "BRIDGE_ADMIN_ORIGIN=" + ADMIN_ORIGIN + "\n" + "BRIDGE_DATA_DIR=" + dataDir(root.resolve("v3")) + "\n");
        assertThat(run.output()).contains("not carried over: BRIDGE_ADMIN_AUTH_MODE - 3.0 has the login modes token and oidc, not access").contains("ACTION REQUIRED choose the admin login mode");
    }

    @Test
    void aSecretStoreFolderIsNotReadAndTheOperatorIsToldSo(@TempDir Path root) {
        Path env = LegacyEnvFixture.write(root.resolve("etc").resolve("bridge.env"), "BRIDGE_SECRET_STORE_DIR=/var/lib/example/secrets", "BRIDGE_JEV_KEY_REF=secret://local-file/example-key");
        Path out = root.resolve("out.env");

        Run run = kit.run(settingsArgs(kit, new LegacyFixture(root.resolve("legacy")).folder(), root.resolve("v3"), env, out, false));

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(LegacyEnvFixture.read(out)).contains("BRIDGE_SECRET_STORE_DIR=/var/lib/example/secrets\n").contains("BRIDGE_JEV_KEY_REF=secret://local-file/example-key\n");
        assertThat(run.output()).contains("ACTION REQUIRED the local secret store named by BRIDGE_SECRET_STORE_DIR is neither read nor moved");
    }

    @Test
    void withoutATargetFolderTheDataFolderIsLeftToTheOperator(@TempDir Path root) {
        Path env = LegacyEnvFixture.write(root.resolve("etc").resolve("bridge.env"), "BRIDGE_MCP_PORT=39124");
        Path out = root.resolve("out.env");

        Run run = kit.run("--source=" + new LegacyFixture(root.resolve("legacy")).folder(), "--spring.datasource.url=jdbc:h2:mem:settings-test", "--env-file=" + env, "--env-out=" + out);

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(LegacyEnvFixture.read(out)).isEqualTo(HEADER + "BRIDGE_MCP_PORT=39124\n");
        assertThat(run.output()).contains("ACTION REQUIRED set BRIDGE_DATA_DIR to the data folder of the 3.0 server");
    }
}
