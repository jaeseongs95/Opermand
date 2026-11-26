package io.github.jaeseongs95.portal.store.journal;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import io.github.jaeseongs95.portal.app.attachment.PreparedOperationAttachments;
import io.github.jaeseongs95.portal.domain.attachment.AttachmentId;
import io.github.jaeseongs95.portal.domain.attachment.AttachmentRole;
import io.github.jaeseongs95.portal.domain.error.DispatchFailure;
import io.github.jaeseongs95.portal.domain.error.ExecutionFailure;
import io.github.jaeseongs95.portal.domain.file.FileBinding;
import io.github.jaeseongs95.portal.domain.file.FileName;
import io.github.jaeseongs95.portal.domain.file.FileTargetBinding;
import io.github.jaeseongs95.portal.domain.id.ArtifactId;
import io.github.jaeseongs95.portal.domain.id.Digest;
import io.github.jaeseongs95.portal.domain.id.OperationId;
import io.github.jaeseongs95.portal.domain.id.OwnerId;
import io.github.jaeseongs95.portal.domain.id.RequestKey;
import io.github.jaeseongs95.portal.domain.id.TargetId;
import io.github.jaeseongs95.portal.domain.operation.AgentCliInput;
import io.github.jaeseongs95.portal.domain.operation.CancellationRequest;
import io.github.jaeseongs95.portal.domain.operation.CommandInput;
import io.github.jaeseongs95.portal.domain.operation.CompletionEvidence;
import io.github.jaeseongs95.portal.domain.operation.ConflictKey;
import io.github.jaeseongs95.portal.domain.operation.Deadline;
import io.github.jaeseongs95.portal.domain.operation.DecisionRecord;
import io.github.jaeseongs95.portal.domain.operation.DownloadInput;
import io.github.jaeseongs95.portal.domain.operation.ExecutionOutcome;
import io.github.jaeseongs95.portal.domain.operation.ExitStatus;
import io.github.jaeseongs95.portal.domain.operation.OperationInput;
import io.github.jaeseongs95.portal.domain.operation.OperationSnapshot;
import io.github.jaeseongs95.portal.domain.operation.RecordedResult;
import io.github.jaeseongs95.portal.domain.operation.Settlement;
import io.github.jaeseongs95.portal.domain.operation.SourceInput;
import io.github.jaeseongs95.portal.domain.operation.StreamCapture;
import io.github.jaeseongs95.portal.domain.operation.TimeoutMs;
import io.github.jaeseongs95.portal.domain.operation.UploadInput;
import io.github.jaeseongs95.portal.domain.path.RemotePath;
import io.github.jaeseongs95.portal.domain.target.ProfileAlias;
import io.github.jaeseongs95.portal.domain.target.TargetRevision;
import io.github.jaeseongs95.portal.domain.text.CommandText;
import io.github.jaeseongs95.portal.domain.text.Utf8Text;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Converts between the domain's {@code OperationSnapshot} and the rows of the journal, in both directions, without touching the database. Body texts are stored in
 * encrypted attachments referenced by their {@code CHAR(48)} ids on the child rows (stage 4 design B-15).
 */
@Component
class OperationMapper {

    private static final TypeReference<List<String>> TEXT_LIST = new TypeReference<>() {
    };

    private final JsonMapper mapper;

    OperationMapper(JsonMapper mapper) {
        this.mapper = mapper;
    }

    // ----- snapshot -> rows -----

    /** The {@code operation} row. The three store-derived values come from the caller, which knows the previous row and the transition. */
    OperationRow operationRow(OperationSnapshot s, Long readySequence, Instant readyAt, Instant startedAt) {
        Instant expiresAt = switch (s.expiry()) {
            case Deadline.None none -> null;
            case Deadline.At at -> at.instant();
        };
        String conflictKey = switch (s.conflictKey()) {
            case ConflictKey.None none -> null;
            case ConflictKey.Of of -> of.value();
        };
        Instant cancellationRequestedAt = switch (s.cancellation()) {
            case CancellationRequest.NotRequested notRequested -> null;
            case CancellationRequest.RequestedAt requested -> requested.instant();
        };
        String inputDigest = switch (s.input()) {
            case AgentCliInput cli -> cli.intentDigest().hex();
            case CommandInput command -> null;
            case SourceInput source -> null;
            case UploadInput upload -> null;
            case DownloadInput download -> null;
        };
        return switch (s.settlement()) {
            case Settlement.Unsettled unsettled -> new OperationRow(s.id().value(), s.requestKey().value(), s.owner().value(), s.targetId().value(), s.targetRevision().hex(),
                s.kind(), s.state(), s.version(), s.approvalMode(), s.profile().value(), inputDigest, s.contractDigest().hex(), conflictKey, readySequence, s.createdAt(),
                s.updatedAt(), expiresAt, readyAt, startedAt, null, cancellationRequestedAt, null, null, s.recovery(), s.audit());
            case Settlement.Settled settled -> new OperationRow(s.id().value(), s.requestKey().value(), s.owner().value(), s.targetId().value(), s.targetRevision().hex(),
                s.kind(), s.state(), s.version(), s.approvalMode(), s.profile().value(), inputDigest, s.contractDigest().hex(), conflictKey, readySequence, s.createdAt(),
                s.updatedAt(), expiresAt, readyAt, startedAt, settled.finishedAt(), cancellationRequestedAt, settled.certainty(), settled.reconciliation(), s.recovery(),
                s.audit());
        };
    }

    OperationCommandRow commandRow(OperationId id, PreparedOperationAttachments prepared) {
        return new OperationCommandRow(id.value(),
            prepared.require(AttachmentRole.COMMAND_TEXT).metadata().id().value(),
            prepared.require(AttachmentRole.CALLER_CONTEXT).metadata().id().value(),
            prepared.require(AttachmentRole.DISPLAY_CONTEXT).metadata().id().value());
    }

    OperationSourceRow sourceRow(OperationId id, SourceInput input, PreparedOperationAttachments prepared) {
        return new OperationSourceRow(id.value(), input.planDigest().hex(), input.sourceSha256().hex(), input.sourceBytes(), input.cwd().value(), input.elevation(),
            input.environmentRevision().hex(),
            prepared.require(AttachmentRole.LAUNCHER_COMMAND).metadata().id().value(),
            prepared.require(AttachmentRole.ARGV_JSON).metadata().id().value(),
            prepared.require(AttachmentRole.CALLER_CONTEXT).metadata().id().value(),
            prepared.require(AttachmentRole.DISPLAY_CONTEXT).metadata().id().value());
    }

    OperationUploadRow uploadRow(OperationId id, UploadInput input) {
        String targetId = switch (input.binding()) {
            case FileBinding.Unbound unbound -> null;
            case FileBinding.Bound bound -> bound.binding().targetId().value();
        };
        String revision = switch (input.binding()) {
            case FileBinding.Unbound unbound -> null;
            case FileBinding.Bound bound -> bound.binding().targetRevision().hex();
        };
        return new OperationUploadRow(id.value(), input.artifactId().value(), input.remotePath().value(), input.fileName().value(), input.sizeBytes(), input.sha256().hex(),
            input.overwrite(), input.elevation(), targetId, revision, input.protocol());
    }

    OperationDownloadRow downloadRow(OperationId id, DownloadInput input, PreparedOperationAttachments prepared) {
        String targetId = switch (input.binding()) {
            case FileBinding.Unbound unbound -> null;
            case FileBinding.Bound bound -> bound.binding().targetId().value();
        };
        String revision = switch (input.binding()) {
            case FileBinding.Unbound unbound -> null;
            case FileBinding.Bound bound -> bound.binding().targetRevision().hex();
        };
        return new OperationDownloadRow(id.value(), input.remotePath().value(), input.elevation(), targetId, revision,
            prepared.require(AttachmentRole.CALLER_CONTEXT).metadata().id().value(),
            prepared.require(AttachmentRole.DISPLAY_CONTEXT).metadata().id().value());
    }

    /** The decision row, or nothing when the operation has no decision. */
    OperationDecisionRow decisionRow(OperationId id, DecisionRecord.Decided decided) {
        return new OperationDecisionRow(id.value(), decided.kind(), decided.reason(), decided.reviewReason(), decided.policyRevision());
    }

    OperationResultRow resultRow(OperationId id, RecordedResult.Executed executed, AttachmentId resultTextsAttachmentId) {
        ExecutionOutcome o = executed.outcome();
        OperationResultRow.ExitKind exitKind = switch (o.exit()) {
            case ExitStatus.Exited exited -> OperationResultRow.ExitKind.EXITED;
            case ExitStatus.Signaled signaled -> OperationResultRow.ExitKind.SIGNALED;
            case ExitStatus.NoExit noExit -> OperationResultRow.ExitKind.NO_EXIT;
        };
        Integer exitCode = o.exit() instanceof ExitStatus.Exited exited ? exited.code() : null;
        String exitSignal = o.exit() instanceof ExitStatus.Signaled signaled ? signaled.name() : null;
        Long timeoutMs = switch (o.timeout()) {
            case TimeoutMs.None none -> null;
            case TimeoutMs.After after -> after.millis();
        };
        StreamColumns out = streamColumns(o.stdout());
        StreamColumns err = streamColumns(o.stderr());
        OperationResultRow.FailureKind failureKind = switch (o.failure()) {
            case ExecutionFailure.Classified classified -> OperationResultRow.FailureKind.CLASSIFIED;
            case ExecutionFailure.Unclassified unclassified -> OperationResultRow.FailureKind.UNCLASSIFIED;
        };
        boolean classified = o.failure() instanceof ExecutionFailure.Classified;
        EvidenceColumns evidence = evidenceColumns(o.evidence());
        return new OperationResultRow(id.value(), OperationResultRow.ResultKind.EXECUTED, o.failed(), o.reason(), o.remoteState(), exitKind, exitCode, exitSignal,
            o.signalDelivery(), timeoutMs, o.cleanup(), o.reconciliation(), o.outputState(), o.capture(), out.received(), out.retained(), out.cursor(), out.truncation(),
            err.received(), err.retained(), err.cursor(), err.truncation(), failureKind, classified ? o.failure().stage() : null, classified ? o.failure().code() : null,
            classified ? o.failure().remoteOutcome() : null, null, null, null, evidence.kind(), evidence.exitCode(), evidence.confirmedAt(), evidence.sha256(), evidence.read(),
            resultTextsAttachmentId.value(), null, null, null);
    }

    OperationResultRow resultRow(OperationId id, RecordedResult.Refused refused, AttachmentId resultTextsAttachmentId) {
        DispatchFailure f = refused.failure();
        OperationResultRow.DispatchKind kind = switch (f) {
            case DispatchFailure.Refused r -> OperationResultRow.DispatchKind.REFUSED;
            case DispatchFailure.StepFailed s -> OperationResultRow.DispatchKind.STEP_FAILED;
            case DispatchFailure.Unclassified u -> OperationResultRow.DispatchKind.UNCLASSIFIED;
        };
        return new OperationResultRow(id.value(), OperationResultRow.ResultKind.REFUSED, io.github.jaeseongs95.portal.domain.operation.ResultFlag.FAILED, null, null,
            OperationResultRow.ExitKind.NO_EXIT, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            OperationResultRow.FailureKind.NONE, null, null, null, kind, f.reason(), f.stage(), OperationResultRow.EvidenceKind.NO_EVIDENCE, null, null, null, null,
            resultTextsAttachmentId.value(), null, null, null);
    }

    // ----- rows -> snapshot -----

    OperationSnapshot snapshot(OperationRow row, OperationInput input, DecisionRecord decision, RecordedResult result) {
        Deadline expiry = row.expiresAt() == null ? new Deadline.None() : new Deadline.At(row.expiresAt());
        ConflictKey conflictKey = row.conflictKey() == null ? new ConflictKey.None() : new ConflictKey.Of(row.conflictKey());
        CancellationRequest cancellation = row.cancellationRequestedAt() == null ? new CancellationRequest.NotRequested()
            : new CancellationRequest.RequestedAt(row.cancellationRequestedAt());
        Settlement settlement = row.outcomeCertainty() == null ? new Settlement.Unsettled()
            : new Settlement.Settled(result, row.outcomeCertainty(), Objects.requireNonNull(row.reconciliation(), "reconciliation of a settled operation"),
                Objects.requireNonNull(row.finishedAt(), "finishedAt of a settled operation"));
        return new OperationSnapshot(new OperationId(row.id()), new RequestKey(row.requestKey()), new OwnerId(row.ownerId()), new TargetId(row.targetId()),
            targetRevision(row.targetRevision()), row.kind(), input, new Digest(row.contractDigest()), row.createdAt(), expiry, conflictKey, row.state(), row.version(),
            row.updatedAt(), row.approvalMode(), decision, settlement, row.recoveryReason(), cancellation, new ProfileAlias(row.profile()), row.auditFailure());
    }

    private TargetRevision targetRevision(String stored) {
        return TargetRevision.of(stored);
    }

    OperationInput commandInput(String commandText, String callerContext, String displayContext) {
        return new CommandInput(new CommandText(commandText), new Utf8Text(callerContext), new Utf8Text(displayContext));
    }

    OperationInput sourceInput(OperationSourceRow row, String launcherCommand, String argvJson, String callerContext, String displayContext) {
        return new SourceInput(new CommandText(launcherCommand), new Digest(row.planDigest()), new Digest(row.sourceSha256()), row.sourceBytes(),
            new Utf8Text(argvJson), new Utf8Text(row.cwd()), row.elevation(), new Digest(row.environmentRevision()), new Utf8Text(callerContext),
            new Utf8Text(displayContext));
    }

    OperationInput uploadInput(OperationUploadRow row) {
        return new UploadInput(new ArtifactId(row.artifactId()), RemotePath.parse(row.remotePath()), new FileName(row.fileName()), row.sizeBytes(), new Digest(row.sha256()),
            row.overwriteMode(), row.elevation(), fileBinding(row.fileTargetId(), row.fileTargetRevision()), row.uploadProtocol());
    }

    OperationInput downloadInput(OperationDownloadRow row, String callerContext, String displayContext) {
        return new DownloadInput(RemotePath.parse(row.remotePath()), row.elevation(), fileBinding(row.fileTargetId(), row.fileTargetRevision()),
            new Utf8Text(callerContext), new Utf8Text(displayContext));
    }

    OperationInput agentCliInput(OperationRow row) {
        return new AgentCliInput(new Digest(Objects.requireNonNull(row.inputDigest(), "inputDigest of an agent CLI operation")));
    }

    DecisionRecord decision(OperationDecisionRow row) {
        return new DecisionRecord.Decided(row.decision(), row.reasonCode(), row.reviewReason(), row.policyRevision());
    }

    List<String> parseResultTexts(String json) {
        return mapper.readValue(json, TEXT_LIST);
    }

    RecordedResult result(OperationResultRow row, List<String> resultTexts) {
        return switch (row.resultKind()) {
            case EXECUTED -> new RecordedResult.Executed(outcome(row, resultTexts));
            case REFUSED -> new RecordedResult.Refused(dispatchFailure(row));
        };
    }

    private FileBinding fileBinding(String targetId, String revision) {
        if (targetId == null) {
            return new FileBinding.Unbound();
        }
        return new FileBinding.Bound(new FileTargetBinding(new TargetId(targetId), TargetRevision.of(Objects.requireNonNull(revision, "fileTargetRevision"))));
    }

    private ExecutionOutcome outcome(OperationResultRow row, List<String> resultTexts) {
        ExitStatus exit = switch (row.exitKind()) {
            case EXITED -> new ExitStatus.Exited(Objects.requireNonNull(row.exitCode(), "exitCode"));
            case SIGNALED -> new ExitStatus.Signaled(Objects.requireNonNull(row.exitSignal(), "exitSignal"));
            case NO_EXIT -> new ExitStatus.NoExit();
        };
        TimeoutMs timeout = row.timeoutMs() == null ? new TimeoutMs.None() : new TimeoutMs.After(row.timeoutMs());
        ExecutionFailure failure = switch (row.failureKind()) {
            case CLASSIFIED -> new ExecutionFailure.Classified(Objects.requireNonNull(row.failureStage(), "failureStage"), Objects.requireNonNull(row.failureCode(),
                "failureCode"), Objects.requireNonNull(row.failureRemoteOutcome(), "failureRemoteOutcome"));
            case UNCLASSIFIED, NONE -> new ExecutionFailure.Unclassified();
        };
        return new ExecutionOutcome(row.execReason(), row.remoteState(), exit, row.signalDelivery(), row.cleanup(), row.execReconciliation(), row.outputState(), row.capture(),
            timeout, capture(row.stdoutReceivedBytes(), row.stdoutRetainedBytes(), row.stdoutCursor(), row.stdoutTruncated()),
            capture(row.stderrReceivedBytes(), row.stderrRetainedBytes(), row.stderrCursor(), row.stderrTruncated()), failure, row.resultFlag(),
            resultTexts, evidence(row));
    }

    private StreamCapture capture(Long received, Long retained, Long cursor, io.github.jaeseongs95.portal.domain.operation.Truncation truncation) {
        if (received == null) {
            return new StreamCapture.NotCaptured();
        }
        return new StreamCapture.Captured(received, Objects.requireNonNull(retained, "retained"), Objects.requireNonNull(cursor, "cursor"),
            Objects.requireNonNull(truncation, "truncation"));
    }

    private CompletionEvidence evidence(OperationResultRow row) {
        return switch (row.evidenceKind()) {
            case NO_EVIDENCE -> new CompletionEvidence.NoEvidence();
            case COMMAND_EXITED -> new CompletionEvidence.CommandExited(Objects.requireNonNull(row.evidenceExitCode(), "evidenceExitCode"));
            case FILE_PUBLISHED -> new CompletionEvidence.FilePublished(Objects.requireNonNull(row.evidenceConfirmedAt(), "evidenceConfirmedAt"),
                new Digest(Objects.requireNonNull(row.evidenceSha256(), "evidenceSha256")));
            case FILE_READ -> new CompletionEvidence.FileRead(row.evidenceRead() == OperationResultRow.EvidenceRead.VERIFIED);
        };
    }

    private DispatchFailure dispatchFailure(OperationResultRow row) {
        return switch (Objects.requireNonNull(row.dispatchKind(), "dispatchKind")) {
            case REFUSED -> new DispatchFailure.Refused(Objects.requireNonNull(row.dispatchReason(), "dispatchReason"), Objects.requireNonNull(row.dispatchStage(), "dispatchStage"));
            case STEP_FAILED -> new DispatchFailure.StepFailed(Objects.requireNonNull(row.dispatchStage(), "dispatchStage"),
                Objects.requireNonNull(row.dispatchReason(), "dispatchReason"));
            case UNCLASSIFIED -> new DispatchFailure.Unclassified();
        };
    }

    // ----- small column groups -----

    private record StreamColumns(Long received, Long retained, Long cursor, io.github.jaeseongs95.portal.domain.operation.Truncation truncation) {
    }

    private StreamColumns streamColumns(StreamCapture capture) {
        return switch (capture) {
            case StreamCapture.NotCaptured notCaptured -> new StreamColumns(null, null, null, null);
            case StreamCapture.Captured captured -> new StreamColumns(captured.receivedBytes(), captured.retainedBytes(), captured.cursor(), captured.truncation());
        };
    }

    private record EvidenceColumns(OperationResultRow.EvidenceKind kind, Integer exitCode, Instant confirmedAt, String sha256, OperationResultRow.EvidenceRead read) {
    }

    private EvidenceColumns evidenceColumns(CompletionEvidence evidence) {
        return switch (evidence) {
            case CompletionEvidence.NoEvidence none -> new EvidenceColumns(OperationResultRow.EvidenceKind.NO_EVIDENCE, null, null, null, null);
            case CompletionEvidence.CommandExited exited -> new EvidenceColumns(OperationResultRow.EvidenceKind.COMMAND_EXITED, exited.exitCode(), null, null, null);
            case CompletionEvidence.FilePublished published -> new EvidenceColumns(OperationResultRow.EvidenceKind.FILE_PUBLISHED, null, published.confirmedAt(),
                published.sha256().hex(), null);
            case CompletionEvidence.FileRead read -> new EvidenceColumns(OperationResultRow.EvidenceKind.FILE_READ, null, null, null,
                read.verified() ? OperationResultRow.EvidenceRead.VERIFIED : OperationResultRow.EvidenceRead.UNVERIFIED);
        };
    }
}
