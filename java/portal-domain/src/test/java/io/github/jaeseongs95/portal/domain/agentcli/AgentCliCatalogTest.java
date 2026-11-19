package io.github.jaeseongs95.portal.domain.agentcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The fixed provider catalog (2.x agent-cli.test.mjs:12). The catalog gets its providers as a list, as the application will inject them. */
class AgentCliCatalogTest {

    private final AgentCliFixture fx = new AgentCliFixture();
    private final AgentCliCatalog catalog = new AgentCliCatalog(List.of(fx.codex, fx.claude));

    @Test
    @DisplayName("RC-A-088 the catalog lists injected providers in order with their package names and commands")
    void listsTheFixedProvidersInOrder() {
        List<AgentCliProvider> providers = catalog.providers();

        assertThat(providers.stream().map(AgentCliProvider::id).toList()).containsExactly(AgentCliProviderId.CODEX, AgentCliProviderId.CLAUDE);
        assertThat(providers.stream().map(provider -> provider.id().wire()).toList()).containsExactly("codex", "claude");
        assertThat(providers.stream().map(AgentCliProvider::packageName).toList()).containsExactly(fx.codex.packageName(), fx.claude.packageName());
        assertThat(providers.stream().map(AgentCliProvider::command).toList()).containsExactly(fx.codex.command(), fx.claude.command());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @NullAndEmptySource
    @ValueSource(strings = {"__proto__", "constructor", "toString", "Codex", "codex;id", " codex", "claude\n"})
    @DisplayName("RC-A-088 a provider name that is not exactly codex or claude is refused")
    void refusesPropertyNamesAndShellInput(String wire) {
        assertThatThrownBy(() -> catalog.provider(AgentCliProviderId.parse(wire))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("RC-A-088 the catalog hands out the provider of an id and the list cannot be changed")
    void providersAreFixed() {
        assertThat(catalog.provider(AgentCliProviderId.parse("claude")).command()).isEqualTo(fx.claude.command());
        assertThat(catalog.provider(AgentCliProviderId.CODEX).packageName()).isEqualTo(fx.codex.packageName());
        assertThatThrownBy(() -> catalog.providers().add(fx.codex)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("S2-AG-01 a catalog without a provider, or with one twice, is refused")
    void refusesIncompleteCatalogs() {
        AgentCliCatalog onlyCodex = new AgentCliCatalog(List.of(fx.codex));

        assertThatThrownBy(() -> onlyCodex.provider(AgentCliProviderId.CLAUDE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentCliCatalog(List.of(fx.codex, fx.codex))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentCliCatalog(List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
