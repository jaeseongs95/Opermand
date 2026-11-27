package io.github.jaeseongs95.opermand.web.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The smallest test that reads the files of the 3.0 contract baseline ({@code contract/**}), docs/migration/stage4-design.md F.3 (review N21). It pins the facts that
 * the units U3 to U9 build on, so that a later edit of the baseline that breaks one of them fails here and not in a unit that was written against the old value. It
 * does not replace the tests of the units: {@code AdminRoutesContractTest} (U6) compares the routes the server really registers, {@code McpToolListContractTest}
 * (U5) compares the tools the server really lists, {@code SettingsContractTest} (U2) compares the keys the product really reads. When one of them exists it takes
 * over the matching case below and the case is deleted in the same change.
 */
class ContractBaselineIntegrityTest {

    private static final Set<String> AUTH30 = Set.of("public", "mcp-bearer", "owner", "owner-or-device", "owner-token", "login", "static", "session-probe",
        "download-capability");

    /** The routes a device token may call, in the words of the baseline (stage4-design.md B-2, decisions L-1, L-2). */
    private static final Set<String> OWNER_OR_DEVICE = Set.of(
        "GET ^/api/targets$",
        "POST ^/api/files/uploads$",
        "GET ^/api/files/uploads/([a-f0-9]{48})$",
        "POST ^/api/files/uploads/([a-f0-9]{48})/chunks$",
        "POST ^/api/files/uploads/([a-f0-9]{48})/finish$",
        "POST ^/api/files/downloads$",
        "POST ^/api/([a-f0-9]{48})/download$",
        "GET ^/api/operations/by-key$",
        "GET ^/api/([a-f0-9]{48})$ with query observe=1",
        "GET ^/api/([a-f0-9]{48})$",
        "POST ^/api/artifacts$",
        "GET ^/api/artifacts/([a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12})$",
        "POST ^/api/artifacts/([a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12})/complete$",
        "PUT ^/api/artifacts/([a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12})/chunks/(0|[1-9][0-9]*)$");

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("S4-CB-20 every route of admin-routes.json has a 3.0 category, no route is listed twice and the owner catch-all is the last one")
    void everyRouteHasACategory() {
        JsonNode routes = read("contract/http/admin-routes.json");
        assertThat(routes.size()).as("routes: 54 of the first revision, plus the four staged-file routes, the download split and the assets route").isEqualTo(60);
        Set<String> seen = new HashSet<>();
        for (JsonNode route : routes) {
            String key = route.path("method").asString() + " " + route.path("pathPattern").asString();
            assertThat(seen.add(key)).as("duplicate route %s", key).isTrue();
            assertThat(AUTH30).as(key).contains(route.path("auth30").asString());
            assertThat(route.path("port").asString()).as(key).isIn("mcp", "admin");
            String pattern = route.path("pathPattern").asString();
            if (pattern.startsWith("^") && !pattern.contains(" with query ")) {
                Pattern.compile(pattern);
            }
        }
        JsonNode last = routes.get(routes.size() - 1);
        assertThat(last.path("pathPattern").asString()).isEqualTo("(any other admin path)");
        assertThat(last.path("auth30").asString()).isEqualTo("owner");
    }

    @Test
    @DisplayName("S4-CB-21 the routes open to a device token are exactly the fourteen of the design; approve and deny stay owner only; the download link needs the ticket only")
    void deviceScopeIsTheDesignedOne() {
        JsonNode routes = read("contract/http/admin-routes.json");
        Set<String> deviceOpen = new TreeSet<>();
        List<String> capability = new ArrayList<>();
        for (JsonNode route : routes) {
            String key = route.path("method").asString() + " " + route.path("pathPattern").asString();
            switch (route.path("auth30").asString()) {
                case "owner-or-device" -> deviceOpen.add(key);
                case "download-capability" -> capability.add(key);
                default -> {
                }
            }
            if (route.path("pathPattern").asString().equals("^/api/([a-f0-9]{48})/(approve|deny)$")) {
                assertThat(route.path("auth30").asString()).as("approve and deny").isEqualTo("owner");
            }
        }
        assertThat(deviceOpen).containsExactlyInAnyOrderElementsOf(OWNER_OR_DEVICE);
        assertThat(capability).containsExactlyInAnyOrder("GET ^/downloads/([a-f0-9]{48})/([a-f0-9]{32})$", "HEAD ^/downloads/([a-f0-9]{48})/([a-f0-9]{32})$");
    }

    @Test
    @DisplayName("S4-CB-22 the settings contract: 32 allowed keys, every default is allowed, the twelve removed 2.x keys are not allowed and are listed with a reason")
    void settingsContractIsConsistent() {
        JsonNode settings = read("contract/settings/settings-contract.json");
        List<String> allowed = new ArrayList<>();
        settings.path("allowedKeys").forEach(key -> allowed.add(key.asString()));
        assertThat(allowed).hasSize(32).doesNotHaveDuplicates().contains("BRIDGE_DATA_DIR", "BRIDGE_OIDC_OWNER_SUBJECT", "BRIDGE_OIDC_CLIENT_SECRET", "BRIDGE_SOURCE_URL");
        for (String key : allowed) {
            assertThat(key.startsWith(settings.path("keyPrefix").asString())).as(key).isTrue();
        }
        for (String key : settings.path("defaults").propertyNames()) {
            assertThat(allowed).as("default %s is an allowed key", key).contains(key);
        }
        List<String> removed = new ArrayList<>();
        for (JsonNode entry : settings.path("removedKeys")) {
            removed.add(entry.path("key").asString());
            assertThat(entry.path("reason").asString()).as(entry.path("key").asString()).isNotBlank();
        }
        assertThat(removed).containsExactlyInAnyOrder("BRIDGE_ACCESS_TEAM_DOMAIN", "BRIDGE_ACCESS_AUD", "BRIDGE_ACCESS_OWNER_EMAILS", "BRIDGE_OIDC_OWNER_EMAILS",
            "BRIDGE_OIDC_JWKS_URL", "BRIDGE_AUDIT_PATH", "BRIDGE_TRANSFER_ROOT", "BRIDGE_COMMAND_AUTOMATION", "BRIDGE_JEV_ENABLED", "BRIDGE_JEV_MAX_CALLS",
            "BRIDGE_JEV_KEY_REF", "JEV_API_KEY");
        assertThat(allowed).doesNotContainAnyElementsOf(removed);
        assertThat(settings.path("adminAuthModes").toString()).isEqualTo("[\"token\",\"oidc\"]");
    }

    @Test
    @DisplayName("S4-CB-23 server.json and the tool files are Contract 3.0: the instructions, the version of the 15 public tools, the operationKey of files and desktop-open")
    void serverAndToolsAreContract30() {
        JsonNode server = read("contract/mcp/server.json");
        assertThat(server.path("contractVersion").asString()).isEqualTo("3.0");
        assertThat(server.path("instructions").asString()).startsWith("Contract 3.0. ").endsWith("Corresponding source (AGPL-3.0-or-later): {sourceUrl}");
        int tools = 0;
        for (String name : List.of("targets", "submit", "observe", "cancel", "files", "artifact-begin", "artifact-chunk", "artifact-status", "artifact-finish", "artifact-read",
            "desktop-open", "desktop-status", "desktop-observe", "desktop-input", "desktop-close")) {
            JsonNode tool = read("contract/mcp/tools/" + name + ".json");
            assertThat(tool.path("name").asString()).isEqualTo(name);
            assertThat(tool.path("outputSchema").path("properties").path("contractVersion").path("const").asString()).as(name).isEqualTo("3.0");
            tools++;
        }
        assertThat(tools).isEqualTo(15);
        assertThat(read("contract/mcp/tools/files.json").path("inputSchema").path("required").toString()).isEqualTo("[\"action\",\"uuid\",\"operationKey\"]");
        JsonNode desktopOpen = read("contract/mcp/tools/desktop-open.json").path("inputSchema");
        assertThat(desktopOpen.path("required").toString()).isEqualTo("[\"target\",\"expectedTargetRevision\",\"operationKey\"]");
        assertThat(desktopOpen.path("properties").has("requestKey")).isFalse();
    }

    @Test
    @DisplayName("S4-CB-24 the web layer codes are not in the eight enums' count and each has an HTTP status")
    void webLayerCodesAreListed() {
        JsonNode entries = read("contract/mcp/envelope.schema.json").path("x-portal-errorCodes").path("webLayerCodes").path("entries");
        List<String> codes = new ArrayList<>();
        for (JsonNode entry : entries) {
            codes.add(entry.get(0).asString());
            assertThat(entry.get(1).asInt()).isBetween(400, 599);
        }
        assertThat(codes).containsExactly("UNAUTHENTICATED", "FORBIDDEN", "CSRF_REJECTED", "ORIGIN_REJECTED", "LOGIN_THROTTLED", "LOGIN_BUSY");
    }

    private JsonNode read(String resource) {
        try (InputStream in = ContractBaselineIntegrityTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("%s is on the test classpath", resource).isNotNull();
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
