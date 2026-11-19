package io.github.jaeseongs95.portal.app.agentcli;

import io.github.jaeseongs95.portal.domain.agentcli.AgentCliProviderId;

/** The OpenAI Codex command-line tool distributed via the public npm registry. */
public final class CodexCliProvider extends NpmCliProvider {

    @Override
    public AgentCliProviderId id() {
        return AgentCliProviderId.CODEX;
    }

    @Override
    public String packageName() {
        return "@openai/codex";
    }

    @Override
    public String command() {
        return "codex";
    }

    @Override
    protected String tarballBaseName() {
        return "codex";
    }
}
