package io.github.jaeseongs95.opermand.app.execution;

import java.time.Clock;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import io.github.jaeseongs95.opermand.app.output.OutputBinding;
import io.github.jaeseongs95.opermand.app.output.OutputManifestStore;
import io.github.jaeseongs95.opermand.app.output.OutputPayloadStore;
import io.github.jaeseongs95.opermand.app.output.OutputReservation;
import io.github.jaeseongs95.opermand.app.output.OutputTarget;
import io.github.jaeseongs95.opermand.app.remote.ExecutionLimits;
import io.github.jaeseongs95.opermand.app.storage.DiskSpace;
import io.github.jaeseongs95.opermand.domain.error.ExecutionErrorCode;
import io.github.jaeseongs95.opermand.domain.error.ExecutionFailure;
import io.github.jaeseongs95.opermand.domain.error.ExecutionStage;
import io.github.jaeseongs95.opermand.domain.error.RemoteOutcome;
import io.github.jaeseongs95.opermand.domain.id.Digester;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.redact.OutputRedactor;

/**
 * Reserves the output of a command before the command starts, so a command whose output cannot be kept is refused while nothing has been sent (2.x {@code reserve}). The
 * reservation is the most the two renderings of the finished output (the JSON document and the text) can take when both streams are kept up to the cap of the
 * execution ({@link OutputRendering#maxBytes}, defect D-0154); it is not the size of the raw streams, which the renderings can exceed. A cap whose renderings are larger than
 * one output may hold is refused up front as {@code OUTPUT_CAPACITY}, with nothing sent. The reasons of a refusal stay apart: the size of one output
 * and the count of outputs are {@code OUTPUT_CAPACITY}, the byte budget is {@code ARTIFACT_QUOTA}, a disk without room is {@code ARTIFACT_STORAGE_FULL} (defect
 * D-0012; decision D3-03).
 */
@Component
public class CommandOutputWriter {

    private final OutputManifestStore manifests;
    private final OutputPayloadStore payloads;
    private final OutputRedactor redactor;
    private final JsonMapper json;
    private final Digester digester;
    private final Clock clock;
    private final TransactionOperations writeTx;

    @Autowired
    public CommandOutputWriter(OutputManifestStore manifests, OutputPayloadStore payloads, OutputRedactor redactor, JsonMapper json, Digester digester, Clock clock,
                               PlatformTransactionManager transactionManager) {
        this(manifests, payloads, redactor, json, digester, clock, new TransactionTemplate(transactionManager));
    }

    public CommandOutputWriter(OutputManifestStore manifests, OutputPayloadStore payloads, OutputRedactor redactor, JsonMapper json, Digester digester, Clock clock) {
        this(manifests, payloads, redactor, json, digester, clock, TransactionOperations.withoutTransaction());
    }

    private CommandOutputWriter(OutputManifestStore manifests, OutputPayloadStore payloads, OutputRedactor redactor, JsonMapper json, Digester digester, Clock clock,
                                TransactionOperations writeTx) {
        this.manifests = manifests;
        this.payloads = payloads;
        this.redactor = redactor;
        this.json = json;
        this.digester = digester;
        this.clock = clock;
        this.writeTx = writeTx;
    }

    /** Reserves the output of the operation's command under the limits of the execution; free disk space is measured outside the reservation transaction. */
    public OutputOpening open(Operation operation, ExecutionLimits limits) {
        long reserved = OutputRendering.maxBytes(limits.outputPerStream().toBytes());
        OutputBinding binding = new OutputBinding(operation.id(), new OutputTarget.Bound(operation.targetId(), operation.targetRevision()));
        DiskSpace disk = payloads.usable();
        OutputReservation reservation = Objects.requireNonNull(writeTx.execute(status -> manifests.reserve(binding, reserved, clock.instant(), disk)), "reservation");
        return switch (reservation) {
            case OutputReservation.Reserved ok -> new OutputOpening.Opened(new OpenOutput(ok.id(), reserved, payloads, manifests, redactor, json, digester, clock, writeTx));
            case OutputReservation.TooLarge tooLarge -> refused(ExecutionErrorCode.OUTPUT_CAPACITY);
            case OutputReservation.CountExceeded countExceeded -> refused(ExecutionErrorCode.OUTPUT_CAPACITY);
            case OutputReservation.BudgetExceeded budgetExceeded -> refused(ExecutionErrorCode.ARTIFACT_QUOTA);
            case OutputReservation.DiskSpaceInsufficient diskFull -> refused(ExecutionErrorCode.ARTIFACT_STORAGE_FULL);
        };
    }

    private OutputOpening refused(ExecutionErrorCode code) {
        return new OutputOpening.Refused(new ExecutionFailure.Classified(ExecutionStage.OUTPUT_RESERVATION, code, RemoteOutcome.NOT_STARTED));
    }
}
