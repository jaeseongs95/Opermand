package io.github.jaeseongs95.opermand.app.agentcli;

import io.github.jaeseongs95.opermand.domain.agentcli.AgentCliProviderId;

/** The OpenAI Codex command-line tool distributed via the public npm registry. */
public final class CodexCliProvider extends NpmCliProvider {

    public static final AgentCliProviderId ID = new AgentCliProviderId("codex");

    @Override
    public AgentCliProviderId id() {
        return ID;
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
    public String credentialFileName() {
        return ".codex/auth.json";
    }

    @Override
    protected String tarballBaseName() {
        return "codex";
    }
}
