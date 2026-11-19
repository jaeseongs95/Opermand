package io.github.jaeseongs95.portal.app.agentcli;

import io.github.jaeseongs95.portal.domain.agentcli.AgentCliProviderId;

/** The Anthropic Claude Code command-line tool distributed via the public npm registry. */
public final class ClaudeCliProvider extends NpmCliProvider {

    @Override
    public AgentCliProviderId id() {
        return AgentCliProviderId.CLAUDE;
    }

    @Override
    public String packageName() {
        return "@anthropic-ai/claude-code";
    }

    @Override
    public String command() {
        return "claude";
    }

    @Override
    protected String tarballBaseName() {
        return "claude-code";
    }
}
