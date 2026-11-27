package io.github.jaeseongs95.opermand.app.remote;

import java.util.Objects;

import io.github.jaeseongs95.opermand.domain.operation.CleanupState;
import io.github.jaeseongs95.opermand.domain.operation.ExecutionReason;
import io.github.jaeseongs95.opermand.domain.operation.ExitStatus;
import io.github.jaeseongs95.opermand.domain.operation.RemoteState;

/**
 * How a streamed command ended, as the release of its {@link StreamHandle} learned it. The facts are the ones of an execution ({@code ExecutionOutcome}): the end of
 * a command is known only from its exit status or exit signal, and closing the channel is no evidence of it (a Windows OpenSSH server may leave the command running).
 *
 * @param reason {@link ExecutionReason#EXIT} the command ended by itself before the owner released the handle, {@link ExecutionReason#CANCEL} the owner released it
 *     first, {@link ExecutionReason#TRANSPORT_LOST} the session was lost before an end was seen
 * @param remoteState {@link RemoteState#EXITED} exactly when an exit status or an exit signal was seen, otherwise {@link RemoteState#UNKNOWN}
 * @param exit how the command ended, {@link ExitStatus.NoExit} when no end was seen
 * @param cleanup {@link CleanupState#COMPLETE} when the channel is confirmed closed and the command's end was seen (or the session is gone, which ends the command's
 *     channel), {@link CleanupState#INCOMPLETE} otherwise: the command may still run on the target and the owner has to reconcile
 */
public record StreamEnd(ExecutionReason reason, RemoteState remoteState, ExitStatus exit, CleanupState cleanup) {

    public StreamEnd {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(remoteState, "remoteState");
        Objects.requireNonNull(exit, "exit");
        Objects.requireNonNull(cleanup, "cleanup");
        boolean exited = !(exit instanceof ExitStatus.NoExit);
        if (reason != ExecutionReason.EXIT && reason != ExecutionReason.CANCEL && reason != ExecutionReason.TRANSPORT_LOST) {
            throw new IllegalArgumentException("A stream ends by its exit, a cancel of the owner or a lost transport, not " + reason);
        }
        if (exited != (remoteState == RemoteState.EXITED) || remoteState == RemoteState.NOT_DISPATCHED) {
            throw new IllegalArgumentException("The remote state is EXITED exactly when an exit status or an exit signal was seen");
        }
        if (reason == ExecutionReason.EXIT && !exited) {
            throw new IllegalArgumentException("A command that ended by itself has an exit status or an exit signal");
        }
        if (cleanup == CleanupState.COMPLETE && !exited && reason != ExecutionReason.TRANSPORT_LOST) {
            throw new IllegalArgumentException("The close of a channel is no evidence that the command ended: the cleanup is complete only with an exit, or with a lost transport");
        }
    }
}
