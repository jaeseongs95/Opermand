package io.github.jaeseongs95.portal.app.remote;

import java.util.Objects;

/**
 * A command whose standard streams are kept open as binary streams (no terminal), for a helper the desktop module talks to.
 *
 * @param target where it runs
 * @param command the command line, as the target's shell takes it; the bytes of its standard input are written when the command starts and the input stays open
 * @param limits {@code deadline} is the time the open may take (the command itself runs until the owner releases the handle), {@code stopGrace} is the time the stop of a
 *     released command may take; the output cap is not used for a stream
 * @param reply whether the answer to the {@code exec} request is awaited
 */
public record StreamRequest(RemoteTarget target, PreparedCommand command, ExecutionLimits limits, ExecReply reply) {

    public StreamRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(reply, "reply");
    }
}
