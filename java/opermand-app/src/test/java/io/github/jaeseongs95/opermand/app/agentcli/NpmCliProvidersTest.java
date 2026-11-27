package io.github.jaeseongs95.opermand.app.agentcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.jaeseongs95.opermand.domain.agentcli.AgentCliCatalog;
import io.github.jaeseongs95.opermand.domain.agentcli.AgentCliProvider;
import io.github.jaeseongs95.opermand.domain.agentcli.AgentCliProviderId;
import io.github.jaeseongs95.opermand.domain.agentcli.AgentRelease;
import io.github.jaeseongs95.opermand.domain.id.Sha256Digester;

/**
 * TC-D0061-02: Verifies that the npm-backed vendor providers ({@link CodexCliProvider}, {@link ClaudeCliProvider}) live in {@code opermand-app}, supply their
 * npm distribution URLs, and enforce canonical 64-byte base64 {@code sha512-...==} SRI integrity.
 */
class NpmCliProvidersTest {

    private final CodexCliProvider codex = new CodexCliProvider();
    private final ClaudeCliProvider claude = new ClaudeCliProvider();
    private final Sha256Digester digester = new Sha256Digester();

    @Test
    @DisplayName("TC-D0061-02 npm providers expose expected ids, packages, commands, and registry.npmjs.org tarball URLs")
    void npmProvidersExposeExpectedMetadata() {
        AgentCliCatalog catalog = new AgentCliCatalog(List.of(codex, claude));
        assertThat(catalog.providers().stream().map(AgentCliProvider::id).toList())
            .containsExactly(CodexCliProvider.PROVIDER_ID, ClaudeCliProvider.PROVIDER_ID);
        assertThat(codex.packageName()).isEqualTo("@openai/codex");
        assertThat(codex.command()).isEqualTo("codex");
        assertThat(codex.credentialFileName()).isEqualTo(".codex/auth.json");
        assertThat(codex.distributionUrl("1.2.3")).isEqualTo("https://registry.npmjs.org/@openai/codex/-/codex-1.2.3.tgz");
        assertThat(claude.packageName()).isEqualTo("@anthropic-ai/claude-code");
        assertThat(claude.command()).isEqualTo("claude");
        assertThat(claude.credentialFileName()).isEqualTo(".claude/.credentials.json");
        assertThat(claude.distributionUrl("1.2.3")).isEqualTo("https://registry.npmjs.org/@anthropic-ai/claude-code/-/claude-code-1.2.3.tgz");

        String validIntegrity = "sha512-" + Base64.getEncoder().encodeToString(sevens(64));
        AgentRelease release = AgentRelease.verify(codex, "@openai/codex", "1.2.3", codex.distributionUrl("1.2.3"), validIntegrity, digester);
        assertThat(release.digestMatches(digester)).isTrue();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("badNpmIntegrities")
    @DisplayName("TC-D0061-02 npm providers reject any integrity that is not canonical 64-byte base64 sha512 SRI")
    void rejectsNonCanonicalSha512Sri(String integrity) {
        assertThatThrownBy(() -> AgentRelease.verify(codex, "@openai/codex", "1.2.3", codex.distributionUrl("1.2.3"), integrity, digester))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentRelease.verify(claude, "@anthropic-ai/claude-code", "1.2.3", claude.distributionUrl("1.2.3"), integrity, digester))
            .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<String> badNpmIntegrities() {
        String valid = "sha512-" + Base64.getEncoder().encodeToString(sevens(64));
        return Stream.of(
            "sha1-abcd",
            "sha256:" + "a".repeat(64),
            "sha512-" + Base64.getEncoder().encodeToString(sevens(63)),
            "sha512-" + Base64.getEncoder().encodeToString(sevens(65)),
            valid + " ",
            valid.substring(0, valid.length() - 3) + "B==",
            "");
    }

    private static byte[] sevens(int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) 7);
        return bytes;
    }
}
