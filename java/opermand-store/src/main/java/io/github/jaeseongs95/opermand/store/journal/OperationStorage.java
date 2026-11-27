package io.github.jaeseongs95.opermand.store.journal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.opermand.app.attachment.AttachmentMetadata;
import io.github.jaeseongs95.opermand.app.attachment.AttachmentStore;
import io.github.jaeseongs95.opermand.app.attachment.AttachmentUnreadableException;
import io.github.jaeseongs95.opermand.app.attachment.OperationAttachmentPreparer;
import io.github.jaeseongs95.opermand.app.attachment.PreparedAttachment;
import io.github.jaeseongs95.opermand.app.attachment.PreparedOperationAttachments;
import io.github.jaeseongs95.opermand.domain.attachment.AttachmentId;
import io.github.jaeseongs95.opermand.domain.attachment.AttachmentRole;
import io.github.jaeseongs95.opermand.domain.error.OperationErrorCode;
import io.github.jaeseongs95.opermand.domain.error.RequestRefusedException;
import io.github.jaeseongs95.opermand.domain.operation.AgentCliInput;
import io.github.jaeseongs95.opermand.domain.operation.CommandInput;
import io.github.jaeseongs95.opermand.domain.operation.DecisionRecord;
import io.github.jaeseongs95.opermand.domain.operation.DownloadInput;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.operation.OperationInput;
import io.github.jaeseongs95.opermand.domain.operation.OperationSnapshot;
import io.github.jaeseongs95.opermand.domain.operation.OperationState;
import io.github.jaeseongs95.opermand.domain.operation.RecordedResult;
import io.github.jaeseongs95.opermand.domain.operation.Settlement;
import io.github.jaeseongs95.opermand.domain.operation.SourceInput;
import io.github.jaeseongs95.opermand.domain.operation.UploadInput;

/**
 * The reading and writing of one operation across the tables of the journal and its encrypted body attachments (stage 4 design B-15).
 *
 * <p>Reading splits cleanly into two stages so callers can keep database transactions free of file/crypto I/O (Principles 4.4):
 * <ol>
 *   <li>{@link #fetchRaw(OperationRow)} reads only SQL rows (including {@code attachment} metadata) inside the caller's read-only transaction.</li>
 *   <li>{@link #hydrate(LoadedOperation)} (strict, fail-closed) or {@link #hydrateForHistory(LoadedOperation)} (per-row masked fallback on unreadable bodies) runs
 *       file reads and AES-GCM decryption outside the transaction.</li>
 * </ol>
 *
 * <p>Writing accepts {@link PreparedOperationAttachments} already encrypted and {@code fsync}ed outside the write transaction; inside the transaction only
 * {@code attachment} metadata and journal rows are inserted.
 */
@Component
class OperationStorage {

    private static final Logger LOG = LoggerFactory.getLogger(OperationStorage.class);

    /** Masked sentinel shown in read-only history views when an attachment file is missing, corrupted, or undecryptable (stage 4 design B-15). */
    static final String MASKED_BODY = "본문을 읽을 수 없음";
    static final String MASKED_ARGV_JSON = "[\"본문을 읽을 수 없음\"]";
    static final List<String> MASKED_RESULT_TEXTS = List.of(MASKED_BODY);

    /** The states that hold concurrency slots: derived from the domain's {@code isActive()} so that the two cannot drift apart. */
    static final Set<OperationState> ACTIVE_STATES = Arrays.stream(OperationState.values()).filter(OperationState::isActive)
        .collect(Collectors.toCollection(() -> EnumSet.noneOf(OperationState.class)));

    private final OperationRowRepository operations;
    private final OperationEventRowRepository events;
    private final OperationCommandRowRepository commands;
    private final OperationSourceRowRepository sources;
    private final OperationUploadRowRepository uploads;
    private final OperationDownloadRowRepository downloads;
    private final OperationDecisionRowRepository decisions;
    private final OperationResultRowRepository results;
    private final OperationSlotRowRepository slots;
    private final OperationFenceRowRepository fences;
    private final JdbcAggregateOperations aggregates;
    private final OperationMapper mapper;
    private final AttachmentStore attachments;
    private final OperationAttachmentPreparer preparer;

    OperationStorage(OperationRowRepository operations, OperationEventRowRepository events, OperationCommandRowRepository commands, OperationSourceRowRepository sources,
                     OperationUploadRowRepository uploads, OperationDownloadRowRepository downloads, OperationDecisionRowRepository decisions,
                     OperationResultRowRepository results, OperationSlotRowRepository slots, OperationFenceRowRepository fences, JdbcAggregateOperations aggregates,
                     OperationMapper mapper, AttachmentStore attachments, OperationAttachmentPreparer preparer) {
        this.operations = operations;
        this.events = events;
        this.commands = commands;
        this.sources = sources;
        this.uploads = uploads;
        this.downloads = downloads;
        this.decisions = decisions;
        this.results = results;
        this.slots = slots;
        this.fences = fences;
        this.aggregates = aggregates;
        this.mapper = mapper;
        this.attachments = attachments;
        this.preparer = preparer;
    }

    /** DB-only snapshot of an operation and its attachment metadata rows; contains zero decrypted file contents. */
    record LoadedOperation(OperationRow row, OperationCommandRow command, OperationSourceRow source, OperationUploadRow upload, OperationDownloadRow download,
                           OperationDecisionRow decision, OperationResultRow result, Map<AttachmentId, AttachmentMetadata> metadata) {
    }

    /** Reads only SQL rows and attachment metadata inside the caller's transaction (zero file/crypto I/O). */
    LoadedOperation fetchRaw(OperationRow row) {
        String id = row.id();
        OperationCommandRow command = null;
        OperationSourceRow source = null;
        OperationUploadRow upload = null;
        OperationDownloadRow download = null;
        List<AttachmentId> attachmentIds = new ArrayList<>(5);
        switch (row.kind()) {
            case COMMAND -> {
                command = commands.findById(id).orElseThrow(OperationStorage::brokenRecord);
                addAttachmentId(attachmentIds, command.commandTextAttachmentId());
                addAttachmentId(attachmentIds, command.callerContextAttachmentId());
                addAttachmentId(attachmentIds, command.displayContextAttachmentId());
            }
            case SOURCE -> {
                source = sources.findById(id).orElseThrow(OperationStorage::brokenRecord);
                addAttachmentId(attachmentIds, source.launcherCommandAttachmentId());
                addAttachmentId(attachmentIds, source.argvJsonAttachmentId());
                addAttachmentId(attachmentIds, source.callerContextAttachmentId());
                addAttachmentId(attachmentIds, source.displayContextAttachmentId());
            }
            case UPLOAD -> upload = uploads.findById(id).orElseThrow(OperationStorage::brokenRecord);
            case DOWNLOAD -> {
                download = downloads.findById(id).orElseThrow(OperationStorage::brokenRecord);
                addAttachmentId(attachmentIds, download.callerContextAttachmentId());
                addAttachmentId(attachmentIds, download.displayContextAttachmentId());
            }
            case AGENT_CLI -> {
                // Intent digest lives on operation.input_digest.
            }
        }
        OperationDecisionRow decision = decisions.findById(id).orElse(null);
        OperationResultRow result = row.outcomeCertainty() == null ? null : results.findById(id).orElse(null);
        if (result != null) {
            addAttachmentId(attachmentIds, result.resultTextsAttachmentId());
        }
        Map<AttachmentId, AttachmentMetadata> metadata = attachments.findMetadata(attachmentIds);
        return new LoadedOperation(row, command, source, upload, download, decision, result, metadata);
    }

    /** Decrypts and verifies all body attachments (fail-closed) outside any DB transaction. */
    Operation hydrate(LoadedOperation raw) {
        return hydrate(raw, false);
    }

    /** Decrypts body attachments for read-only history views, masking any unreadable body per row without failing the whole page. */
    Operation hydrateForHistory(LoadedOperation raw) {
        return hydrate(raw, true);
    }

    /** Convenience strict load combining {@link #fetchRaw} and {@link #hydrate}. */
    Operation load(OperationRow row) {
        return hydrate(fetchRaw(row), false);
    }

    /** Convenience history load combining {@link #fetchRaw} and {@link #hydrateForHistory}. */
    Operation loadForHistory(OperationRow row) {
        return hydrate(fetchRaw(row), true);
    }

    private Operation hydrate(LoadedOperation raw, boolean maskUnreadable) {
        OperationRow row = raw.row();
        OperationInput input = switch (row.kind()) {
            case COMMAND -> {
                OperationCommandRow c = raw.command();
                String cmd = readBody(c.commandTextAttachmentId(), AttachmentRole.COMMAND_TEXT, raw.metadata(), maskUnreadable, MASKED_BODY);
                String caller = readBody(c.callerContextAttachmentId(), AttachmentRole.CALLER_CONTEXT, raw.metadata(), maskUnreadable, MASKED_BODY);
                String display = readBody(c.displayContextAttachmentId(), AttachmentRole.DISPLAY_CONTEXT, raw.metadata(), maskUnreadable, MASKED_BODY);
                yield mapper.commandInput(cmd, caller, display);
            }
            case SOURCE -> {
                OperationSourceRow s = raw.source();
                String launcher = readBody(s.launcherCommandAttachmentId(), AttachmentRole.LAUNCHER_COMMAND, raw.metadata(), maskUnreadable, MASKED_BODY);
                String argv = readBody(s.argvJsonAttachmentId(), AttachmentRole.ARGV_JSON, raw.metadata(), maskUnreadable, MASKED_ARGV_JSON);
                String caller = readBody(s.callerContextAttachmentId(), AttachmentRole.CALLER_CONTEXT, raw.metadata(), maskUnreadable, MASKED_BODY);
                String display = readBody(s.displayContextAttachmentId(), AttachmentRole.DISPLAY_CONTEXT, raw.metadata(), maskUnreadable, MASKED_BODY);
                yield mapper.sourceInput(s, launcher, argv, caller, display);
            }
            case UPLOAD -> mapper.uploadInput(raw.upload());
            case DOWNLOAD -> {
                OperationDownloadRow d = raw.download();
                String caller = readBody(d.callerContextAttachmentId(), AttachmentRole.CALLER_CONTEXT, raw.metadata(), maskUnreadable, MASKED_BODY);
                String display = readBody(d.displayContextAttachmentId(), AttachmentRole.DISPLAY_CONTEXT, raw.metadata(), maskUnreadable, MASKED_BODY);
                yield mapper.downloadInput(d, caller, display);
            }
            case AGENT_CLI -> mapper.agentCliInput(row);
        };
        DecisionRecord decision = raw.decision() == null ? new DecisionRecord.NoDecision() : mapper.decision(raw.decision());
        RecordedResult result = raw.result() == null ? new RecordedResult.NoResult() : readResult(raw.result(), raw.metadata(), maskUnreadable);
        return Operation.restore(mapper.snapshot(row, input, decision, result));
    }

    private RecordedResult readResult(OperationResultRow row, Map<AttachmentId, AttachmentMetadata> metadata, boolean maskUnreadable) {
        if (row.resultKind() == OperationResultRow.ResultKind.REFUSED) {
            return mapper.result(row, List.of());
        }
        String rawJson = readBody(row.resultTextsAttachmentId(), AttachmentRole.RESULT_TEXTS, metadata, maskUnreadable, null);
        if (rawJson == null) {
            return mapper.result(row, MASKED_RESULT_TEXTS);
        }
        try {
            return mapper.result(row, mapper.parseResultTexts(rawJson));
        } catch (RuntimeException e) {
            if (maskUnreadable) {
                LOG.warn("History read masked malformed RESULT_TEXTS JSON for attachment {}", row.resultTextsAttachmentId(), e);
                return mapper.result(row, MASKED_RESULT_TEXTS);
            }
            throw new AttachmentUnreadableException(new AttachmentId(row.resultTextsAttachmentId()), AttachmentRole.RESULT_TEXTS,
                AttachmentUnreadableException.Reason.CORRUPTED_ENVELOPE, "Result texts JSON could not be parsed", e);
        }
    }

    private String readBody(String rawId, AttachmentRole role, Map<AttachmentId, AttachmentMetadata> metadata, boolean maskUnreadable, String maskedFallback) {
        AttachmentId id = new AttachmentId(rawId);
        AttachmentMetadata meta = metadata.get(id);
        if (meta == null) {
            if (maskUnreadable) {
                LOG.warn("History read masked attachment {} ({}): metadata row is missing", id.value(), role);
                return maskedFallback;
            }
            throw new AttachmentUnreadableException(id, role, AttachmentUnreadableException.Reason.MISSING_METADATA,
                "Attachment metadata row is missing: " + id.value());
        }
        try {
            return attachments.readUtf8(meta, role);
        } catch (AttachmentUnreadableException e) {
            if (maskUnreadable) {
                LOG.warn("History read masked unreadable attachment {} ({}): {}", id.value(), role, e.reason());
                return maskedFallback;
            }
            throw e;
        }
    }

    private static void addAttachmentId(List<AttachmentId> target, String rawId) {
        if (rawId != null) {
            target.add(new AttachmentId(rawId));
        }
    }

    /**
     * Inserts a new operation in the state its admission decided. When {@code supplied} is non-null (service-layer path), zero file or crypto I/O runs here; when
     * {@code supplied} is null (direct store-slice tests), attachments are prepared on the spot and discarded if the insert throws.
     */
    void insertNew(Operation operation, Long readySequence, PreparedOperationAttachments supplied) {
        OperationSnapshot s = operation.snapshot();
        boolean queued = s.state() == OperationState.QUEUED;
        if (queued != (readySequence != null)) {
            throw new IllegalArgumentException("A queued operation takes a place in the ready order and no other does");
        }
        boolean preparedLocally = supplied == null;
        PreparedOperationAttachments prepared = preparedLocally ? preparer.prepareInput(s.input()) : supplied;
        try {
            for (PreparedAttachment attachment : prepared.all()) {
                attachments.recordMetadata(attachment.metadata());
            }
            prepared.markConsumed();
            aggregates.insert(mapper.operationRow(s, readySequence, queued ? s.updatedAt() : null, null));
            switch (s.input()) {
                case CommandInput command -> aggregates.insert(mapper.commandRow(s.id(), prepared));
                case SourceInput source -> aggregates.insert(mapper.sourceRow(s.id(), source, prepared));
                case UploadInput upload -> aggregates.insert(mapper.uploadRow(s.id(), upload));
                case DownloadInput download -> aggregates.insert(mapper.downloadRow(s.id(), download, prepared));
                case AgentCliInput agentCli -> {
                    // The digest of the intent is the column input_digest of the operation row; the rest of the install is AgentCliRequestStore's.
                }
            }
            writeDecision(s);
            aggregates.insert(new OperationEventRow(s.id().value(), s.version(), s.state(), s.updatedAt()));
        } catch (RuntimeException e) {
            if (preparedLocally) {
                preparer.discard(prepared);
            }
            throw e;
        }
    }

    Operation persist(OperationRow before, Operation next, long expectedVersion) {
        return persist(before, next, expectedVersion, null, null);
    }

    Operation persist(OperationRow before, Operation next, long expectedVersion, Long place, PreparedOperationAttachments supplied) {
        OperationSnapshot s = next.snapshot();
        if (s.version() != expectedVersion + 1) {
            throw new IllegalArgumentException("The next version must be " + (expectedVersion + 1) + " but is " + s.version());
        }
        if (operations.bumpVersion(before.id(), expectedVersion, s.version()) != 1) {
            throw new RequestRefusedException(OperationErrorCode.OPERATION_VERSION_CONFLICT);
        }
        Long readySequence = before.readySequence();
        Instant readyAt = before.readyAt();
        Instant startedAt = before.startedAt();
        switch (s.state()) {
            case QUEUED -> {
                readySequence = place != null ? place : operations.maxReadySequence() + 1;
                readyAt = s.updatedAt();
            }
            case STARTING -> startedAt = s.updatedAt();
            case PENDING, RUNNING, TERMINATING, COMPLETED, FAILED, CANCELLED, DENIED, EXPIRED, UNKNOWN -> {
                // The ready position and the start belong to the transitions into QUEUED and STARTING only.
            }
        }
        aggregates.update(mapper.operationRow(s, readySequence, readyAt, startedAt));
        writeDecision(s);
        writeResult(s, supplied);
        aggregates.insert(new OperationEventRow(s.id().value(), s.version(), s.state(), s.updatedAt()));
        if (before.state().isActive() && !s.state().isActive()) {
            slots.deleteByOperation(before.id());
        }
        if (s.state().isFinal()) {
            fences.deleteByOperation(before.id());
        }
        return next;
    }

    private void writeDecision(OperationSnapshot s) {
        if (!(s.decision() instanceof DecisionRecord.Decided decided)) {
            return;
        }
        OperationDecisionRow row = mapper.decisionRow(s.id(), decided);
        Optional<OperationDecisionRow> stored = decisions.findById(s.id().value());
        if (stored.filter(row::equals).isPresent()) {
            return;
        }
        stored.ifPresent(old -> decisions.deleteById(old.operationId()));
        aggregates.insert(row);
    }

    private void writeResult(OperationSnapshot s, PreparedOperationAttachments supplied) {
        if (!(s.settlement() instanceof Settlement.Settled settled)) {
            return;
        }
        if (settled.result() instanceof RecordedResult.NoResult) {
            return;
        }
        Optional<OperationResultRow> stored = results.findById(s.id().value());
        AttachmentId attachmentId;
        PreparedOperationAttachments locallyPrepared = null;
        if (stored.isPresent()) {
            // Operation results are write-once (reconciliation / audit-failure updates keep the existing result_texts attachment untouched).
            attachmentId = new AttachmentId(stored.orElseThrow().resultTextsAttachmentId());
        } else {
            PreparedOperationAttachments source;
            if (supplied != null && supplied.has(AttachmentRole.RESULT_TEXTS)) {
                source = supplied;
            } else {
                locallyPrepared = preparer.prepareResult(settled.result());
                source = locallyPrepared;
            }
            PreparedAttachment prepared = source.require(AttachmentRole.RESULT_TEXTS);
            try {
                attachments.recordMetadata(prepared.metadata());
                source.markConsumed();
                attachmentId = prepared.metadata().id();
            } catch (RuntimeException e) {
                if (locallyPrepared != null) {
                    preparer.discard(locallyPrepared);
                }
                throw e;
            }
        }
        OperationResultRow row = switch (settled.result()) {
            case RecordedResult.NoResult noResult -> null;
            case RecordedResult.Executed executed -> mapper.resultRow(s.id(), executed, attachmentId);
            case RecordedResult.Refused refused -> mapper.resultRow(s.id(), refused, attachmentId);
        };
        if (row == null) {
            return;
        }
        // The timeline of the execution is written after the outcome (ExecutionTimelineStore) and is not part of it: a result stored again keeps the instants it has.
        OperationResultRow candidate = stored.map(old -> row.withTimeline(old.execConnectedAt(), old.execDispatchedAt(), old.execClosedAt())).orElse(row);
        if (stored.filter(candidate::equals).isPresent()) {
            return;
        }
        try {
            stored.ifPresent(old -> results.deleteById(old.operationId()));
            aggregates.insert(candidate);
        } catch (RuntimeException e) {
            if (locallyPrepared != null) {
                preparer.discard(locallyPrepared);
            }
            throw e;
        }
    }

    private static RequestRefusedException brokenRecord() {
        return new RequestRefusedException(OperationErrorCode.INVALID_OPERATION_RECORD);
    }
}
