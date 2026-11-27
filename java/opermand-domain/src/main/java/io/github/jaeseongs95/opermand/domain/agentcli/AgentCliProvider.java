package io.github.jaeseongs95.opermand.domain.agentcli;

/**
 * One agent command-line tool the portal can install: its id, the package that carries it and the command it provides. What differs between the tools lives in
 * an implementation of this interface outside the domain, never in a branch of the shared code; a new tool is a new implementation that the application adds to
 * the injected list.
 */
public interface AgentCliProvider {

    AgentCliProviderId id();

    /** The distribution package identifier owned by this provider. */
    String packageName();

    /** The command the package puts on the target. */
    String command();

    /** The trusted HTTPS distribution URL for the given stable release version. Owned by each provider so the common domain is not coupled to any registry. */
    String distributionUrl(String version);

    /**
     * Validates a provider-specific integrity checksum format (fail-closed: every provider must explicitly implement its checksum rule).
     * Throws {@link IllegalArgumentException} if the checksum does not satisfy this provider's distribution contract.
     */
    void verifyIntegrity(String integrity);

    /**
     * Relative path under the remote user's home directory where this tool stores its login state (for example {@code .tool/auth.json}).
     * Owned by each provider so the SSH installer holds no hardcoded vendor table.
     */
    String credentialFileName();
}
