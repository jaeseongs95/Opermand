package io.github.jaeseongs95.portal.app.remote;

import java.util.Objects;

/**
 * A request to open a local forward: a loopback listener whose first connection is carried through a {@code direct-tcpip} channel of the target's session to a host
 * and port the target can reach. The channel is opened before the answer, so a target that refuses the connection is answered here and not on first use.
 *
 * @param target the target whose connection carries the channel
 * @param host the host the target connects to, 1 to 253 characters
 * @param port the port, 1 to 65535
 * @param limits the time the channel may take to open ({@code deadline}) and the time a close, or a channel that opens after the owner gave up, may take to be
 *     confirmed ({@code stopGrace}); the output cap is not used
 */
public record ForwardRequest(RemoteTarget target, String host, int port, ExecutionLimits limits) {

    public ForwardRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(limits, "limits");
        if (host.isBlank() || host.length() > 253 || port < 1 || port > 65535) {
            throw new IllegalArgumentException("A forward needs a host of 1 to 253 characters and a port from 1 to 65535");
        }
    }
}
