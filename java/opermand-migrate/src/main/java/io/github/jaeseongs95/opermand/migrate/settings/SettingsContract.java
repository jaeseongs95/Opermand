package io.github.jaeseongs95.opermand.migrate.settings;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What becomes of each setting of the 2.x environment file in 3.0 (the settings contract of the 3.0 baseline: {@code contract-baseline.md} of stage 4 and
 * {@code settings-contract.json}). The keys of 2.x are the ones 2.4.2 accepted ({@code allowedKeys}, 40 keys) and the {@code SSH_MCP_*} credentials of the SSH profiles; 3.0
 * removes twelve of them (including {@code BRIDGE_COMMAND_AUTOMATION} and the four Jev keys) and adds four, leaving 32 allowed keys. The test {@code SettingsContractTest}
 * compares {@link #legacyKeys()} with a copy of the 2.4.2 list so drift fails the build.
 */
public final class SettingsContract {

    /** The 2.4.2 {@code allowedKeys}. */
    private static final Set<String> LEGACY_KEYS = Set.of(
        "BRIDGE_MCP_TOKEN", "BRIDGE_ADMIN_TOKEN", "BRIDGE_UI_TICKET_KEY", "BRIDGE_ADMIN_ORIGIN",
        "BRIDGE_UI_ENABLED", "BRIDGE_UI_TOOL_PREFIX", "BRIDGE_HOST_KEY_MODE", "BRIDGE_TRANSFER_MAX_BYTES",
        "BRIDGE_TRANSFER_BUDGET_BYTES", "BRIDGE_REQUEST_CAPACITY", "BRIDGE_QUEUE_CAPACITY", "BRIDGE_MAX_CONCURRENCY", "BRIDGE_CONFIG_PATH", "BRIDGE_AUDIT_PATH", "BRIDGE_TRANSFER_ROOT",
        "BRIDGE_MCP_PORT", "BRIDGE_ADMIN_PORT", "BRIDGE_ADMIN_AUTH_MODE", "BRIDGE_ACCESS_TEAM_DOMAIN",
        "BRIDGE_ACCESS_AUD", "BRIDGE_ACCESS_OWNER_EMAILS", "BRIDGE_UPLOAD_SOURCE_HOSTS",
        "BRIDGE_OIDC_ISSUER", "BRIDGE_OIDC_JWKS_URL", "BRIDGE_OIDC_CLIENT_ID", "BRIDGE_OIDC_OWNER_EMAILS",
        "BRIDGE_DISPLAY_NAME", "BRIDGE_TARGET_DISPLAY_NAME", "BRIDGE_TARGETS", "BRIDGE_DEFAULT_TARGET",
        "BRIDGE_JEV_ENABLED", "BRIDGE_JEV_MAX_CALLS", "JEV_API_KEY", "BRIDGE_COMMAND_AUTOMATION",
        "BRIDGE_SECRET_STORE_DIR", "BRIDGE_JEV_KEY_REF", "BRIDGE_TEST_SERVER_SECRET_REF",
        "BRIDGE_VAULT_URL", "BRIDGE_VAULT_MOUNT", "BRIDGE_VAULT_TOKEN_REF");

    /** The twelve keys 3.0 does not have, and why (the 3.0 server refuses to start with a removed key via LegacyKeyGuard, so none of them is carried over). */
    private static final Map<String, String> REMOVED = Map.ofEntries(
        Map.entry("BRIDGE_AUDIT_PATH", "the 3.0 audit is a database table, there is no audit file to place (the 2.x audit file is not moved)"),
        Map.entry("BRIDGE_TRANSFER_ROOT", "3.0 keeps everything in one data folder, BRIDGE_DATA_DIR (it is set to the --target folder)"),
        Map.entry("BRIDGE_ACCESS_TEAM_DOMAIN", "the access login mode no longer exists"),
        Map.entry("BRIDGE_ACCESS_AUD", "the access login mode no longer exists"),
        Map.entry("BRIDGE_ACCESS_OWNER_EMAILS", "the access login mode no longer exists"),
        Map.entry("BRIDGE_OIDC_JWKS_URL", "3.0 finds the signing keys by the discovery document of BRIDGE_OIDC_ISSUER"),
        Map.entry("BRIDGE_OIDC_OWNER_EMAILS", "3.0 admits one owner account by its subject identifier (BRIDGE_OIDC_OWNER_SUBJECT), not by e-mail addresses"),
        Map.entry("BRIDGE_COMMAND_AUTOMATION", "deterministic command policy rules always evaluate in 3.0; there is no global automation toggle"),
        Map.entry("BRIDGE_JEV_ENABLED", "model-assisted risk evaluation (Jev) is excluded in 3.0; command policy rules are deterministic"),
        Map.entry("BRIDGE_JEV_MAX_CALLS", "model-assisted risk evaluation (Jev) is excluded in 3.0; command policy rules are deterministic"),
        Map.entry("BRIDGE_JEV_KEY_REF", "model-assisted risk evaluation (Jev) is excluded in 3.0; command policy rules are deterministic"),
        Map.entry("JEV_API_KEY", "model-assisted risk evaluation (Jev) is excluded in 3.0; command policy rules are deterministic"));

    /** The four keys 3.0 added: the data folder (the command knows it), the owner account and client secret of the OIDC login (it cannot know them), the source URL (optional). */
    private static final Set<String> ADDED = Set.of("BRIDGE_DATA_DIR", "BRIDGE_OIDC_OWNER_SUBJECT", "BRIDGE_OIDC_CLIENT_SECRET", "BRIDGE_SOURCE_URL");

    static final String DATA_DIR = "BRIDGE_DATA_DIR";
    static final String ADMIN_AUTH_MODE = "BRIDGE_ADMIN_AUTH_MODE";
    static final String CONFIG_PATH = "BRIDGE_CONFIG_PATH";
    static final String TARGETS = "BRIDGE_TARGETS";
    static final String SECRET_STORE_DIR = "BRIDGE_SECRET_STORE_DIR";

    /** The profile credentials 2.x took from the environment file: kept under the same names (decision D3-09). */
    private static final Pattern PROFILE_CREDENTIAL = Pattern.compile("SSH_MCP_[A-Z0-9_]+");

    /** The settings 2.4.2 accepted as keys of its environment file. */
    public Set<String> legacyKeys() {
        return LEGACY_KEYS;
    }

    /** The keys 3.0 does not have. */
    public Set<String> removedKeys() {
        return REMOVED.keySet();
    }

    /** The keys 3.0 has and 2.x had not. */
    public Set<String> addedKeys() {
        return ADDED;
    }

    /** True for a setting 2.x accepted: one of its keys or an {@code SSH_MCP_*} credential. */
    public boolean legacyAccepts(String key) {
        return LEGACY_KEYS.contains(key) || PROFILE_CREDENTIAL.matcher(key).matches();
    }

    /** The reason 3.0 has no such key, or null when it has. */
    public String removalReason(String key) {
        return REMOVED.get(key);
    }
}
