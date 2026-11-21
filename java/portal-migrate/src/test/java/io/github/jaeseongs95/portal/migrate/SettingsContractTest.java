package io.github.jaeseongs95.portal.migrate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.json.JsonParserFactory;

import io.github.jaeseongs95.portal.migrate.settings.SettingsContract;

/**
 * The table of what becomes of each 2.x setting, held against what the sources say: the 2.4.2 list of accepted keys (a copy of its {@code allowedKeys}, in the test resources) and
 * the 3.0 contract baseline (seven keys dropped, four added). A key of 2.x that the table does not know, or a change of the baseline that the table does not follow, fails here.
 */
class SettingsContractTest {

    private final SettingsContract contract = new SettingsContract();

    /** Item 6 of the contract changes of the 3.0 baseline (stage 4): the keys that are no longer allowed. */
    private static final Set<String> DROPPED_BY_3_0 = Set.of("BRIDGE_AUDIT_PATH", "BRIDGE_TRANSFER_ROOT", "BRIDGE_ACCESS_TEAM_DOMAIN", "BRIDGE_ACCESS_AUD", "BRIDGE_ACCESS_OWNER_EMAILS",
        "BRIDGE_OIDC_JWKS_URL", "BRIDGE_OIDC_OWNER_EMAILS");

    /** The keys that are new in 3.0. */
    private static final Set<String> ADDED_BY_3_0 = Set.of("BRIDGE_DATA_DIR", "BRIDGE_OIDC_OWNER_SUBJECT", "BRIDGE_OIDC_CLIENT_SECRET", "BRIDGE_SOURCE_URL");

    private static Set<String> keysOf2x() throws IOException {
        try (InputStream in = SettingsContractTest.class.getResourceAsStream("/legacy/settings-contract-allowed-keys-2.4.2.json")) {
            assertThat(in).as("the copy of the 2.4.2 list of keys").isNotNull();
            Map<String, Object> json = JsonParserFactory.getJsonParser().parseMap(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            List<?> keys = (List<?>) json.get("allowedKeys");
            Set<String> names = new HashSet<>();
            keys.forEach(key -> names.add((String) key));
            assertThat(names).as("the keys of the copy are all different").hasSize(keys.size());
            return names;
        }
    }

    @Test
    void theTableKnowsExactlyTheKeys2xAccepted() throws IOException {
        assertThat(contract.legacyKeys()).containsExactlyInAnyOrderElementsOf(keysOf2x());
    }

    @Test
    void theDroppedKeysAreTheSevenOfTheBaselineAndAllOfThemWereKeysOf2x() throws IOException {
        assertThat(contract.removedKeys()).containsExactlyInAnyOrderElementsOf(DROPPED_BY_3_0);
        assertThat(keysOf2x()).containsAll(contract.removedKeys());
        assertThat(contract.addedKeys()).containsExactlyInAnyOrderElementsOf(ADDED_BY_3_0);
        assertThat(keysOf2x()).as("a key 3.0 added was not a key of 2.x").doesNotContainAnyElementsOf(ADDED_BY_3_0);
    }

    @Test
    void the3KeyListIs2xMinusTheDroppedPlusTheAddedAndEveryDroppedKeyHasAReason() throws IOException {
        Set<String> allowedIn3 = new HashSet<>(keysOf2x());
        allowedIn3.removeAll(DROPPED_BY_3_0);
        allowedIn3.addAll(ADDED_BY_3_0);

        assertThat(allowedIn3).hasSize(37);
        for (String key : keysOf2x()) {
            assertThat(contract.legacyAccepts(key)).isTrue();
            assertThat(contract.removalReason(key) != null).as(key).isEqualTo(DROPPED_BY_3_0.contains(key));
        }
        for (String key : DROPPED_BY_3_0) {
            assertThat(contract.removalReason(key)).as(key).isNotBlank();
        }
    }

    @Test
    void theProfileCredentialsOfThe2xFileAreAcceptedAndOtherNamesAreNot() {
        assertThat(contract.legacyAccepts("SSH_MCP_ALPHA_WRITE_PASSWORD")).isTrue();
        assertThat(contract.legacyAccepts("SSH_MCP_")).isFalse();
        assertThat(contract.legacyAccepts("SSH_MCP_lower")).isFalse();
        assertThat(contract.legacyAccepts("NODE_OPTIONS")).isFalse();
        assertThat(contract.legacyAccepts("BRIDGE_DATA_DIR")).as("a key of 3.0 is not a key of 2.x").isFalse();
    }
}
