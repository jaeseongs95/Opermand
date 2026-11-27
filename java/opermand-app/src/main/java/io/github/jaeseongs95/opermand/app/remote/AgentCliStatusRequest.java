package io.github.jaeseongs95.opermand.app.remote;

import java.util.Objects;

import io.github.jaeseongs95.opermand.domain.agentcli.AgentCliProviderId;

/**
 * A read-only question about the installation of a tool on a target. It sends no write and no input from a caller.
 *
 * @param target where to look
 * @param provider which tool
 * @param limits the deadline, the stop grace and the output cap of the read-only commands
 */
public record AgentCliStatusRequest(RemoteTarget target, AgentCliProviderId provider, String credentialFileName, ExecutionLimits limits) {

    public AgentCliStatusRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(credentialFileName, "credentialFileName");
        Objects.requireNonNull(limits, "limits");
        if (credentialFileName.isBlank() || credentialFileName.startsWith("/") || credentialFileName.contains("..") || credentialFileName.contains("\\")) {
            throw new IllegalArgumentException("Invalid credential file name");
        }
    }
}
