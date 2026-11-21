package io.github.jaeseongs95.portal.migrate.plan;

import java.util.List;
import java.util.Locale;

import io.github.jaeseongs95.portal.domain.error.DispatchFailure;
import io.github.jaeseongs95.portal.domain.error.DispatchReason;
import io.github.jaeseongs95.portal.domain.error.DispatchStage;
import io.github.jaeseongs95.portal.domain.error.ExecutionErrorCode;
import io.github.jaeseongs95.portal.domain.error.ExecutionFailure;
import io.github.jaeseongs95.portal.domain.error.ExecutionStage;
import io.github.jaeseongs95.portal.domain.error.RemoteOutcome;
import io.github.jaeseongs95.portal.domain.operation.CaptureState;
import io.github.jaeseongs95.portal.domain.operation.CleanupState;
import io.github.jaeseongs95.portal.domain.operation.CompletionEvidence;
import io.github.jaeseongs95.portal.domain.operation.ExecutionOutcome;
import io.github.jaeseongs95.portal.domain.operation.ExecutionReason;
import io.github.jaeseongs95.portal.domain.operation.ExitStatus;
import io.github.jaeseongs95.portal.domain.operation.OutputState;
import io.github.jaeseongs95.portal.domain.operation.Reconciliation;
import io.github.jaeseongs95.portal.domain.operation.RemoteState;
import io.github.jaeseongs95.portal.domain.operation.ResultFlag;
import io.github.jaeseongs95.portal.domain.operation.SignalDelivery;
import io.github.jaeseongs95.portal.domain.operation.StreamCapture;
import io.github.jaeseongs95.portal.domain.operation.TimeoutMs;
import io.github.jaeseongs95.portal.domain.operation.Truncation;

/**
 * Reads the {@code result} of a 2.x job (the executor's outcome: {@code failed}, {@code texts}, {@code failure} and the {@code execution} object) into the 3.0
 * {@link ExecutionOutcome}, and the pre-dispatch refusal that 2.x kept in the {@code decision} object into a {@link DispatchFailure}. A value this tool does not know rejects
 * the row; a value that is absent takes the careful reading (a flag that was not recorded as true is not taken as true, so a missing cleanup flag means the cleanup is not
 * confirmed), and the domain then decides the state from the outcome: an unconfirmed outcome is unknown, never completed (defect D-0035).
 */
final class OutcomeReader {

    /** True when the result has an {@code execution} object, that is, when an executor ran. */
    boolean hasExecution(JsonObject result) {
        return result != null && result.has("execution");
    }

    /** The outcome of a result that has an execution; {@code legacyState} gives the reason when the execution does not say it. */
    ExecutionOutcome read(JsonObject result, String legacyState) {
        JsonObject execution = result.object("execution");
        ExecutionReason reason = execution.has("reason") ? parse(ExecutionReason.class, execution.string("reason"), "execution.reason")
            : "cancelled".equals(legacyState) ? ExecutionReason.CANCEL : ExecutionReason.EXIT;
        RemoteState remote = execution.has("remoteState") ? parse(RemoteState.class, execution.string("remoteState"), "execution.remoteState") : RemoteState.UNKNOWN;
        ExitStatus exit = exitOf(execution);
        SignalDelivery delivery = execution.has("signalDelivery") ? parse(SignalDelivery.class, execution.string("signalDelivery"), "execution.signalDelivery")
            : SignalDelivery.NOT_SENT;
        CleanupState cleanup = Boolean.TRUE.equals(execution.bool("cleanupComplete")) ? CleanupState.COMPLETE : CleanupState.INCOMPLETE;
        Reconciliation reconciliation = Boolean.TRUE.equals(execution.bool("requiresReconciliation")) ? Reconciliation.REQUIRED : Reconciliation.NOT_REQUIRED;
        OutputState output = execution.has("outputState") ? parse(OutputState.class, execution.string("outputState"), "execution.outputState") : OutputState.NOT_REPORTED;
        Boolean captureFailed = execution.bool("captureFailed");
        CaptureState capture = captureFailed == null ? CaptureState.NOT_REPORTED : captureFailed ? CaptureState.FAILED : CaptureState.OK;
        Long timeout = execution.integer("timeoutMs");
        TimeoutMs limit = timeout != null && timeout >= 1 ? new TimeoutMs.After(timeout) : new TimeoutMs.None();
        ResultFlag flag = Boolean.TRUE.equals(result.bool("failed")) ? ResultFlag.FAILED : ResultFlag.OK;
        List<String> recorded = result.strings("texts");
        List<String> texts = recorded == null ? List.of() : recorded;
        CompletionEvidence evidence = remote == RemoteState.EXITED && exit instanceof ExitStatus.Exited exited ? new CompletionEvidence.CommandExited(exited.code())
            : new CompletionEvidence.NoEvidence();
        return new ExecutionOutcome(reason, remote, exit, delivery, cleanup, reconciliation, output, capture, limit, streamOf(execution.object("stdout"), "stdout"),
            streamOf(execution.object("stderr"), "stderr"), failureOf(result.object("failure")), flag, texts, evidence);
    }

    /**
     * An operation that 2.x marked completed or unknown without recording what the executor reported: nothing is known beyond "an executor was involved". The outcome has no
     * evidence, so the domain settles it as unknown, which is the careful reading.
     */
    ExecutionOutcome withoutRecord(JsonObject result) {
        List<String> recorded = result == null ? null : result.strings("texts");
        List<String> texts = recorded == null ? List.of() : recorded;
        return new ExecutionOutcome(ExecutionReason.EXIT, RemoteState.EXITED, new ExitStatus.NoExit(), SignalDelivery.NOT_SENT, CleanupState.COMPLETE, Reconciliation.NOT_REQUIRED,
            OutputState.NOT_REPORTED, CaptureState.NOT_REPORTED, new TimeoutMs.None(), new StreamCapture.NotCaptured(), new StreamCapture.NotCaptured(),
            new ExecutionFailure.Unclassified(), ResultFlag.OK, texts, new CompletionEvidence.NoEvidence());
    }

    /** The refusal before dispatch that the 2.x decision object recorded ({@code dispatchFailure: "not_dispatched"}), or null when there is none. */
    DispatchFailure dispatchFailure(JsonObject decision) {
        if (decision == null || !"not_dispatched".equals(decision.string("dispatchFailure"))) {
            return null;
        }
        if (!decision.has("dispatchFailureReason") || !decision.has("dispatchFailureStage")) {
            return new DispatchFailure.Unclassified();
        }
        DispatchReason reason = parse(DispatchReason.class, decision.string("dispatchFailureReason"), "decision.dispatchFailureReason");
        DispatchStage stage = parse(DispatchStage.class, decision.string("dispatchFailureStage"), "decision.dispatchFailureStage");
        return stage.accepts(reason) ? DispatchFailure.refused(reason, stage) : new DispatchFailure.Unclassified();
    }

    private ExitStatus exitOf(JsonObject execution) {
        Long code = execution.integer("exitCode");
        if (code != null) {
            if (code < Integer.MIN_VALUE || code > Integer.MAX_VALUE) {
                throw new RowRejectedException("execution.exitCode is out of range");
            }
            return new ExitStatus.Exited(code.intValue());
        }
        String signal = execution.string("signal");
        return signal == null ? new ExitStatus.NoExit() : new ExitStatus.Signaled(signal);
    }

    private StreamCapture streamOf(JsonObject stream, String name) {
        if (stream == null) {
            return new StreamCapture.NotCaptured();
        }
        Long received = stream.integer("receivedBytes");
        Long retained = stream.integer("retainedBytes");
        Long cursor = stream.integer("cursor");
        Boolean truncated = stream.bool("truncated");
        if (received == null || retained == null || cursor == null || truncated == null) {
            throw new RowRejectedException("execution." + name + " lacks one of receivedBytes, retainedBytes, cursor, truncated");
        }
        return new StreamCapture.Captured(received, retained, cursor, truncated ? Truncation.TRUNCATED : Truncation.COMPLETE);
    }

    private ExecutionFailure failureOf(JsonObject failure) {
        if (failure == null) {
            return new ExecutionFailure.Unclassified();
        }
        ExecutionStage stage = parse(ExecutionStage.class, failure.string("stage"), "failure.stage");
        ExecutionErrorCode code = parse(ExecutionErrorCode.class, failure.string("code"), "failure.code");
        RemoteOutcome outcome = parse(RemoteOutcome.class, failure.string("remoteOutcome"), "failure.remoteOutcome");
        return new ExecutionFailure.Classified(stage, code, outcome);
    }

    private <E extends Enum<E>> E parse(Class<E> type, String text, String field) {
        if (text == null) {
            throw new RowRejectedException(field + " is missing");
        }
        try {
            return Enum.valueOf(type, text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RowRejectedException(field + " is \"" + text + "\", which 3.0 does not know");
        }
    }
}
