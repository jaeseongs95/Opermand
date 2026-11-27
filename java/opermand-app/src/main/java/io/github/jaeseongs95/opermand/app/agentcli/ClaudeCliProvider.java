package io.github.jaeseongs95.opermand.app.agentcli;

import io.github.jaeseongs95.opermand.domain.agentcli.AgentCliProviderId;

/** The Anthropic Claude Code command-line tool distributed via the public npm registry. */
public final class ClaudeCliProvider extends NpmCliProvider {

    public static final AgentCliProviderId ID = new AgentCliProviderId("claude");

    @Override
    public AgentCliProviderId id() {
        return ID;
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
    public String credentialFileName() {
        return ".claude/.credentials.json";
    }

    @Override
    protected String tarballBaseName() {
        return "claude-code";
    }
}
