package io.github.jaeseongs95.portal.app.agentcli;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.jaeseongs95.portal.domain.agentcli.AgentCliCatalog;
import io.github.jaeseongs95.portal.domain.agentcli.AgentCliProvider;

/**
 * Registers the vendor-specific agent CLI providers (owned by {@code portal-app}, not {@code portal-domain}) and the domain {@link AgentCliCatalog}
 * bean that collects every {@link AgentCliProvider} in injection order.
 */
@Configuration(proxyBeanMethods = false)
public class AgentCliConfiguration {

    @Bean
    public CodexCliProvider codexCliProvider() {
        return new CodexCliProvider();
    }

    @Bean
    public ClaudeCliProvider claudeCliProvider() {
        return new ClaudeCliProvider();
    }

    @Bean
    public AgentCliCatalog agentCliCatalog(List<AgentCliProvider> providers) {
        return new AgentCliCatalog(providers);
    }
}
