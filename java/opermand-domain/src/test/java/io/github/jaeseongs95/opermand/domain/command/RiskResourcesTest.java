package io.github.jaeseongs95.opermand.domain.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * S2-RE-01: the command risk catalogue resource is preserved and loads into {@link CommandRiskCatalog}.
 * The dead 2.x model-evaluation question files were removed in defect D-0122.
 */
class RiskResourcesTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("S2-RE-01 command-risks.json is the 2.x file: 22238 bytes (LF), SHA-256 85dd68f8..., and 22856 bytes with eb65ce4e... as CRLF")
    void commandRisksBytes() {
        byte[] bytes = resource("risk/command-risks.json");
        assertThat(bytes).hasSize(22_238);
        assertThat(sha256(bytes)).isEqualTo("85dd68f81a5e6416000b18a52bde0fc57269e8610c84e27f5bad0381fab2be59");
        byte[] crlf = toCrlf(bytes);
        assertThat(crlf).hasSize(22_856);
        assertThat(sha256(crlf)).isEqualTo("eb65ce4e1e1f73259a405afd5e4741fda1f529d1a64d2227bb33faff03c47d83");
    }

    @Test
    @DisplayName("S2-RE-01 the risk catalogue loads into the record: 64 names (12 low, 10 moderate, 42 high), sudo excluded, a name it does not know is unknown")
    void catalogueLoads() {
        CommandRiskCatalog catalog = mapper.readValue(resource("risk/command-risks.json"), CommandRiskCatalog.class);
        assertThat(catalog.commands()).hasSize(64);
        assertThat(catalog.commands().values().stream().filter(entry -> entry.riskLevel() == RiskLevel.LOW)).hasSize(12);
        assertThat(catalog.commands().values().stream().filter(entry -> entry.riskLevel() == RiskLevel.MODERATE)).hasSize(10);
        assertThat(catalog.commands().values().stream().filter(entry -> entry.riskLevel() == RiskLevel.HIGH)).hasSize(42);
        assertThat(catalog.authorityEffect()).isEqualTo(AuthorityEffect.NONE);
        assertThat(catalog.excludedCommands()).containsExactly("sudo");
        assertThat(catalog.defaultCommands()).containsExactly("pwd", "ls", "stat", "df", "du", "uname", "uptime", "whoami", "id", "hostname");
        assertThat(catalog.describe("ls")).isInstanceOf(RiskDescription.Known.class);
        assertThat(((RiskDescription.Known) catalog.describe("sh")).entry().capabilities()).containsExactly(RiskCapability.EXECUTE_CODE, RiskCapability.DELEGATE_COMMANDS);
        assertThat(catalog.describe("sudo")).isInstanceOf(RiskDescription.Unknown.class);
        assertThat(catalog.describe("no-such-program")).isEqualTo(new RiskDescription.Unknown(catalog.defaultRisk()));
        assertThat(catalog.defaultRisk().riskLevel()).isEqualTo(RiskLevel.UNKNOWN);
    }

    private byte[] resource(String name) {
        try (InputStream in = RiskResourcesTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as(name).isNotNull();
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private byte[] toCrlf(byte[] lf) {
        return new String(lf, StandardCharsets.UTF_8).replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform has SHA-256", e);
        }
    }
}
