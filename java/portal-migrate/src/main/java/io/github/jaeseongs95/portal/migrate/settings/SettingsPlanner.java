package io.github.jaeseongs95.portal.migrate.settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.jaeseongs95.portal.migrate.settings.EnvText.Entry;
import io.github.jaeseongs95.portal.migrate.settings.SettingsPlan.Problem;

/**
 * Turns a 2.x environment file into the 3.0 settings file. 3.0 reads the same {@code KEY=value} file with the same keys, except for the twelve it dropped
 * ({@link SettingsContract#REMOVED}) and the four it added; so every setting is carried over as it was written (a secret is copied, not parsed or changed), a dropped key is
 * left out and named, and the one added key that the command can know, {@code BRIDGE_DATA_DIR}, is the {@code --target} folder. What cannot be known (the owner account
 * of the OIDC login, its client secret, the login mode when 2.x used the access mode) is listed as an action for the operator, never filled with a guess.
 *
 * <p>The 2.x file is only read. Nothing here writes; {@link SettingsWriter} does, after every other check has passed.
 */
public final class SettingsPlanner {

    private final SettingsContract contract = new SettingsContract();
    private final EnvText envText = new EnvText(contract);

    /**
     * Reads and converts.
     *
     * @param envFile the 2.x environment file
     * @param out the file the 3.0 settings are to be written to
     * @param dataDir the 3.0 data folder ({@code --target}), or null when the store is named by {@code --spring.datasource.*}
     */
    public SettingsPlan plan(Path envFile, Path out, Path dataDir) {
        List<Problem> problems = new ArrayList<>();
        byte[] bytes = read(envFile, problems);
        if (bytes == null) {
            return new SettingsPlan(out, null, List.of(), List.of(), problems);
        }
        EnvText.Parsed parsed = envText.parse(bytes);
        parsed.problems().forEach(message -> problems.add(new Problem("SETTINGS_ENTRY", "environment file", message)));
        if (!problems.isEmpty()) {
            return new SettingsPlan(out, null, List.of(), List.of(), problems);
        }
        List<String> notes = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        List<String> moved = new ArrayList<>();
        for (Entry entry : parsed.entries().values()) {
            String removal = contract.removalReason(entry.key());
            if (removal != null) {
                notes.add("removed in 3.0, not carried over: " + entry.key() + " - " + removal);
            } else if (SettingsContract.ADMIN_AUTH_MODE.equals(entry.key()) && "access".equals(entry.value())) {
                notes.add("not carried over: " + entry.key() + " - 3.0 has the login modes token and oidc, not access");
                actions.add("choose the admin login mode: 2.x used the access mode, which 3.0 does not have; the new file leaves " + entry.key() + " out, so the default token mode"
                    + " applies; to use oidc set BRIDGE_ADMIN_AUTH_MODE=oidc, BRIDGE_OIDC_ISSUER, BRIDGE_OIDC_CLIENT_ID, BRIDGE_OIDC_CLIENT_SECRET and BRIDGE_OIDC_OWNER_SUBJECT");
            } else {
                moved.add(entry.key());
                lines.add(entry.key() + "=" + entry.written());
                if (SettingsContract.ADMIN_AUTH_MODE.equals(entry.key()) && "oidc".equals(entry.value())) {
                    actions.add("set BRIDGE_OIDC_OWNER_SUBJECT (the subject identifier of the one owner account; 2.x admitted owners by e-mail address, which cannot be converted)"
                        + " and BRIDGE_OIDC_CLIENT_SECRET (3.0 logs in by the authorization code flow, 2.x had no client secret) in the new file before the 3.0 server starts");
                }
            }
        }
        parsed.duplicates().forEach(key -> notes.add("written more than once in the 2.x file, the last one is carried over (as 2.x read it): " + key));
        notes.add(0, "carried over unchanged (" + moved.size() + "): " + String.join(", ", moved));
        if (dataDir != null) {
            lines.add(SettingsContract.DATA_DIR + "=" + dataDir.toAbsolutePath().toString().replace('\\', '/'));
            notes.add("added: " + SettingsContract.DATA_DIR + " is the --target folder");
        } else {
            actions.add("set " + SettingsContract.DATA_DIR + " to the data folder of the 3.0 server (it holds the artifacts, the outputs and known_hosts); no --target was given");
        }
        describeNeighbours(parsed, notes, actions);
        String text = "# 3.0 settings made by portal-migrate from a 2.x environment file; read it before the server uses it\n" + String.join("\n", lines) + "\n";
        return new SettingsPlan(out, text, notes, actions, problems);
    }

    /** What the settings point at and 3.0 does not take over with them. Only the files are looked at, never what is in them. */
    private void describeNeighbours(EnvText.Parsed parsed, List<String> notes, List<String> actions) {
        Entry config = parsed.entries().get(SettingsContract.CONFIG_PATH);
        if (config != null) {
            notes.add(profileFileState(config.value()));
        } else {
            notes.add("no BRIDGE_CONFIG_PATH: the 2.x service had no SSH profile file to carry over");
        }
        if (parsed.entries().containsKey(SettingsContract.TARGETS)) {
            notes.add("BRIDGE_TARGETS is carried over as written; the 3.0 server checks it when it starts");
        }
        if (parsed.entries().containsKey(SettingsContract.SECRET_STORE_DIR)) {
            actions.add("the local secret store named by BRIDGE_SECRET_STORE_DIR is neither read nor moved; the file format of the 3.0 secret store is decided with the server"
                + " settings (stage 4), until then set those secrets again with the 3.0 tool");
        }
    }

    private String profileFileState(String configured) {
        Path file;
        try {
            file = Path.of(configured);
        } catch (InvalidPathException e) {
            return "the SSH profile file: BRIDGE_CONFIG_PATH is not a path this system can use; 3.0 will not find the file";
        }
        if (!Files.isRegularFile(file)) {
            return "the SSH profile file: BRIDGE_CONFIG_PATH names no regular file here; 3.0 refuses to start without it (copy the file, or correct the path)";
        }
        try {
            if (Files.size(file) == 0) {
                return "the SSH profile file: it is empty, which 3.0 refuses";
            }
        } catch (IOException e) {
            return "the SSH profile file: it cannot be read here (" + e.getClass().getSimpleName() + ")";
        }
        return "the SSH profile file: 3.0 reads the same TOML format from the path BRIDGE_CONFIG_PATH names, so the file is used where it is; it is not copied or read here"
            + " (the 3.0 server checks the profile names and keys when it starts)";
    }

    private byte[] read(Path file, List<Problem> problems) {
        try {
            if (!Files.isRegularFile(file)) {
                problems.add(new Problem("SETTINGS_FILE", "environment file", "the 2.x environment file is not a regular file (or it does not exist)"));
                return null;
            }
            if (Files.size(file) > EnvText.MAX_BYTES) {
                problems.add(new Problem("SETTINGS_FILE", "environment file", "the 2.x environment file is larger than " + EnvText.MAX_BYTES + " bytes, which 2.x refused too"));
                return null;
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            problems.add(new Problem("SETTINGS_FILE", "environment file", "the 2.x environment file cannot be read: " + e.getClass().getSimpleName()));
            return null;
        }
    }
}
