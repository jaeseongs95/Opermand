package io.github.jaeseongs95.portal.app.file;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.portal.app.artifact.ArtifactDestination;
import io.github.jaeseongs95.portal.app.artifact.ArtifactDirection;
import io.github.jaeseongs95.portal.app.artifact.ArtifactImporter;
import io.github.jaeseongs95.portal.app.artifact.ArtifactOrigin;
import io.github.jaeseongs95.portal.app.artifact.ArtifactPayloadStore;
import io.github.jaeseongs95.portal.app.artifact.ArtifactRecord;
import io.github.jaeseongs95.portal.app.artifact.ArtifactRefusal;
import io.github.jaeseongs95.portal.app.artifact.ArtifactRegistry;
import io.github.jaeseongs95.portal.app.artifact.ArtifactReservation;
import io.github.jaeseongs95.portal.app.artifact.ArtifactRetention;
import io.github.jaeseongs95.portal.app.artifact.ArtifactSpec;
import io.github.jaeseongs95.portal.app.artifact.ArtifactState;
import io.github.jaeseongs95.portal.app.artifact.BindingKind;
import io.github.jaeseongs95.portal.app.artifact.ChunkDigest;
import io.github.jaeseongs95.portal.app.artifact.ConsumeReason;
import io.github.jaeseongs95.portal.app.artifact.ConsumeState;
import io.github.jaeseongs95.portal.app.artifact.ContentDigest;
import io.github.jaeseongs95.portal.app.artifact.ImportResult;
import io.github.jaeseongs95.portal.app.artifact.ReleaseReason;
import io.github.jaeseongs95.portal.app.artifact.StagedAction;
import io.github.jaeseongs95.portal.app.artifact.StagedActionKind;
import io.github.jaeseongs95.portal.app.artifact.StagedActionState;
import io.github.jaeseongs95.portal.app.artifact.StagingTimes;
import io.github.jaeseongs95.portal.app.journal.OperationJournal;
import io.github.jaeseongs95.portal.app.maintenance.MaintenanceStore;
import io.github.jaeseongs95.portal.app.storage.DiskCheck;
import io.github.jaeseongs95.portal.app.storage.PayloadRead;
import io.github.jaeseongs95.portal.app.storage.RunningDigest;
import io.github.jaeseongs95.portal.domain.error.ArtifactErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.domain.file.FileBinding;
import io.github.jaeseongs95.portal.domain.file.FileName;
import io.github.jaeseongs95.portal.domain.file.FileTargetBinding;
import io.github.jaeseongs95.portal.domain.id.ArtifactId;
import io.github.jaeseongs95.portal.domain.id.ArtifactUuid;
import io.github.jaeseongs95.portal.domain.id.ArtifactUuidGenerator;
import io.github.jaeseongs95.portal.domain.id.CanonicalForm;
import io.github.jaeseongs95.portal.domain.id.Digest;
import io.github.jaeseongs95.portal.domain.id.Digester;
import io.github.jaeseongs95.portal.domain.id.OperationId;
import io.github.jaeseongs95.portal.domain.id.OperationIdGenerator;
import io.github.jaeseongs95.portal.domain.id.OwnerId;
import io.github.jaeseongs95.portal.domain.id.RequestKey;
import io.github.jaeseongs95.portal.domain.operation.CompletionEvidence;
import io.github.jaeseongs95.portal.domain.operation.ConflictKey;
import io.github.jaeseongs95.portal.domain.operation.Deadline;
import io.github.jaeseongs95.portal.domain.operation.Elevation;
import io.github.jaeseongs95.portal.domain.operation.NewOperation;
import io.github.jaeseongs95.portal.domain.operation.Operation;
import io.github.jaeseongs95.portal.domain.operation.OperationState;
import io.github.jaeseongs95.portal.domain.operation.OverwriteMode;
import io.github.jaeseongs95.portal.domain.operation.UploadInput;
import io.github.jaeseongs95.portal.domain.operation.UploadProtocol;
import io.github.jaeseongs95.portal.domain.target.ResolvedTarget;

/**
 * The files a client has put on the server and not yet sent anywhere (2.x opaque files, design 0.3 item 5): begin, append, complete, stat, and the three things a client
 * can do with a staged file. {@code copy} publishes a copy and keeps the staged file, {@code move} publishes and consumes the staged file once the publication is
 * <em>confirmed</em>, and {@code remove} deletes the staged bytes without any SSH at all. The owner is the verified owner of the request; another owner's UUID is "not found".
 *
 * <p>A copy and a move do not publish the staged file itself: they copy its bytes into a new artifact bound to the target and admit an upload of that copy through
 * {@link UploadAdmission}, so the approval, the policy and the queue of every other request apply, and the staged file can be copied again. A request with the same
 * operation key and the same content answers the first action; the same key with other content is {@code OPERATION_KEY_CONFLICT}. A move consumes the staged file in
 * {@link #operationSettled} only when the operation completed with the evidence {@code FilePublished} of this very content (D-0035); an operation that ended otherwise keeps it,
 * and one that is {@code UNKNOWN} keeps the action open.
 *
 * <p>While a maintenance reservation holds the installation no staged file is begun, changed or sent (RC-A-776, RC-A-777).
 */
@Service
public class StagedFileService {

    private static final Logger LOG = LoggerFactory.getLogger(StagedFileService.class);

    private final ArtifactRegistry registry;
    private final ArtifactPayloadStore payloads;
    private final ArtifactImporter importer;
    private final MaintenanceStore maintenance;
    private final UploadAdmission admission;
    private final OperationJournal journal;
    private final StagingTimes times;
    private final ArtifactUuidGenerator uuids;
    private final OperationIdGenerator operationIds;
    private final Digester digester;
    private final Clock clock;
    private final TransactionOperations readTx;
    private final TransactionOperations writeTx;

    @Autowired
    public StagedFileService(ArtifactRegistry registry, ArtifactPayloadStore payloads, ArtifactImporter importer, MaintenanceStore maintenance, UploadAdmission admission,
                             OperationJournal journal, StagingTimes times, ArtifactUuidGenerator uuids, OperationIdGenerator operationIds, Digester digester, Clock clock,
                             PlatformTransactionManager transactionManager) {
        this(registry, payloads, importer, maintenance, admission, journal, times, uuids, operationIds, digester, clock,
            readOnly(transactionManager), new TransactionTemplate(transactionManager));
    }

    public StagedFileService(ArtifactRegistry registry, ArtifactPayloadStore payloads, ArtifactImporter importer, MaintenanceStore maintenance, UploadAdmission admission,
                             OperationJournal journal, StagingTimes times, ArtifactUuidGenerator uuids, OperationIdGenerator operationIds, Digester digester, Clock clock) {
        this(registry, payloads, importer, maintenance, admission, journal, times, uuids, operationIds, digester, clock,
            TransactionOperations.withoutTransaction(), TransactionOperations.withoutTransaction());
    }

    private StagedFileService(ArtifactRegistry registry, ArtifactPayloadStore payloads, ArtifactImporter importer, MaintenanceStore maintenance, UploadAdmission admission,
                              OperationJournal journal, StagingTimes times, ArtifactUuidGenerator uuids, OperationIdGenerator operationIds, Digester digester, Clock clock,
                              TransactionOperations readTx, TransactionOperations writeTx) {
        this.registry = registry;
        this.payloads = payloads;
        this.importer = importer;
        this.maintenance = maintenance;
        this.admission = admission;
        this.journal = journal;
        this.times = times;
        this.uuids = uuids;
        this.operationIds = operationIds;
        this.digester = digester;
        this.clock = clock;
        this.readTx = readTx;
        this.writeTx = writeTx;
    }

    private static TransactionOperations readOnly(PlatformTransactionManager transactionManager) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        return tx;
    }

    // ---- begin, append, complete, stat ----

    /** Starts a staged upload, or answers the one that the same owner and key started before (RC-B-091: the retry of a begin gets the same UUID). */
    public StagedBegin begin(OwnerId owner, String operationKey, FileName fileName, long sizeBytes) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(fileName, "fileName");
        RequestKey key = requestKey(operationKey);
        Optional<ArtifactRecord> existing = readTx.execute(status -> {
            requireOpen();
            return registry.findStagedByKey(owner, key.value());
        });
        if (existing != null && existing.isPresent()) {
            return sameBegin(existing.orElseThrow(), fileName, sizeBytes);
        }
        return reserve(owner, key, fileName, sizeBytes);
    }

    private StagedBegin sameBegin(ArtifactRecord existing, FileName fileName, long sizeBytes) {
        if (!existing.spec().fileName().equals(fileName) || existing.spec().sizeBytes() != sizeBytes) {
            throw new RequestRefusedException(RequestErrorCode.OPERATION_KEY_CONFLICT);
        }
        return new StagedBegin.Begun(((ArtifactOrigin.Staged) existing.spec().origin()).publicUuid());
    }

    private StagedBegin reserve(OwnerId owner, RequestKey key, FileName fileName, long sizeBytes) {
        ArtifactUuid uuid = uuids.next();
        ArtifactSpec spec = new ArtifactSpec(ArtifactDirection.UPLOAD, fileName, sizeBytes, new ArtifactOrigin.Staged(uuid, owner, key.value()), new ArtifactDestination.Unassigned(),
            OverwriteMode.REPLACE, Elevation.NONE, UploadProtocol.MCP_JSON_V1, ArtifactRetention.RELEASABLE, times.upload());
        DiskCheck disk = DiskCheck.measure(payloads::usable);
        ArtifactReservation reservation = Objects.requireNonNull(writeTx.execute(status -> registry.reserve(spec, clock.instant(), disk)), "reservation");
        if (reservation instanceof ArtifactReservation.Reserved) {
            return new StagedBegin.Begun(uuid);
        }
        return new StagedBegin.Refused(ArtifactRefusal.of(reservation));
    }

    /**
     * Takes the next chunk: its digest must be the one the client gave (a wrong one changes nothing, RC-B-137), its number the next one, and the file must still be
     * staging and not expired. The bytes are written and forced to disk outside any transaction before the registry acknowledges them in a short write transaction.
     */
    public void append(OwnerId owner, ArtifactUuid uuid, int sequence, byte[] bytes, Digest claimed) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(claimed, "claimed");
        Digest actual = digester.digest(bytes);
        if (!actual.equals(claimed)) {
            throw new RequestRefusedException(ArtifactErrorCode.CHUNK_HASH_MISMATCH);
        }
        ArtifactRecord record = Objects.requireNonNull(readTx.execute(status -> {
            requireOpen();
            return owned(owner, uuid);
        }), "record");
        if (bytes.length < 1 || bytes.length > ChunkDigest.MAX_CHUNK_BYTES) {
            throw new RequestRefusedException(ArtifactErrorCode.INVALID_ARTIFACT_CHUNK);
        }
        if (record.state() != ArtifactState.STAGING) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_NOT_WRITABLE);
        }
        requireNotExpired(record, ArtifactErrorCode.ARTIFACT_EXPIRED);
        if (sequence != record.chunkCount()) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_CHUNK_CONFLICT);
        }
        if (record.receivedBytes() + bytes.length > record.spec().sizeBytes()) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_SEQUENCE_OR_SIZE);
        }
        payloads.append(record.id(), record.receivedBytes(), bytes);
        writeTx.executeWithoutResult(status -> registry.append(record.id(), sequence, new ChunkDigest(bytes.length, actual)));
    }

    /** Ends the upload: every byte must be there; the whole file is hashed outside any transaction and marked {@code READY} in a short write transaction. */
    public ArtifactRecord complete(OwnerId owner, ArtifactUuid uuid) {
        ArtifactRecord record = Objects.requireNonNull(readTx.execute(status -> {
            requireOpen();
            return owned(owner, uuid);
        }), "record");
        if (record.state() == ArtifactState.READY) {
            return record;
        }
        requireNotExpired(record, ArtifactErrorCode.ARTIFACT_EXPIRED);
        if (record.receivedBytes() != record.spec().sizeBytes()) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_INCOMPLETE);
        }
        Digest wholeDigest = hashOfStoredBytes(record);
        return Objects.requireNonNull(writeTx.execute(status -> registry.markReady(record.id(), wholeDigest)), "ready");
    }

    /** The staged file and the actions made on it. */
    public StagedFileView stat(OwnerId owner, ArtifactUuid uuid) {
        return Objects.requireNonNull(readTx.execute(status -> {
            ArtifactRecord record = owned(owner, uuid);
            return new StagedFileView(record, registry.actions(record.id()));
        }), "view");
    }

    // ---- remove ----

    /**
     * Removes the staged bytes (no SSH): TX #1 validates and moves the row to {@code CONSUMING(REMOVED)}, bytes are deleted outside any transaction, and TX #2 finishes the
     * tombstone ({@code CONSUMED(REMOVED)}).
     */
    public ArtifactRecord remove(OwnerId owner, ArtifactUuid uuid) {
        ArtifactRecord prepared = Objects.requireNonNull(writeTx.execute(status -> {
            requireOpen();
            ArtifactRecord record = owned(owner, uuid);
            if (record.consumption() instanceof ConsumeState.ConsumedBecause done) {
                if (done.reason() == ConsumeReason.REMOVED) {
                    return record;
                }
                throw new RequestRefusedException(ArtifactErrorCode.STAGED_FILE_CONSUMED);
            }
            if (registry.actions(record.id()).stream().anyMatch(this::dependsOnTheFile)
                || record.consumption() instanceof ConsumeState.Consuming consuming && consuming.reason() == ConsumeReason.MOVED) {
                throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_BUSY);
            }
            if (record.state() == ArtifactState.READY || record.state() == ArtifactState.STAGING) {
                return registry.beginConsume(record.id(), ConsumeReason.REMOVED);
            }
            return record;
        }), "prepared");
        if (prepared.consumption() instanceof ConsumeState.ConsumedBecause) {
            return prepared;
        }
        payloads.delete(prepared.id());
        return Objects.requireNonNull(writeTx.execute(status -> registry.completeConsume(prepared.id(), ConsumeReason.REMOVED)), "removed");
    }

    private boolean dependsOnTheFile(StagedAction action) {
        return action.state() == StagedActionState.RESERVED || action.state() == StagedActionState.SUBMITTED;
    }

    // ---- copy and move ----

    public StagedPlacement place(StagedPlacementRequest request) {
        Objects.requireNonNull(request, "request");
        RequestKey key = requestKey(request.operationKey());
        record PlaceLookup(ArtifactRecord source, StagedAction earlier) {
        }
        PlaceLookup lookup = Objects.requireNonNull(readTx.execute(status -> {
            requireOpen();
            ArtifactRecord source = owned(request.owner(), request.uuid());
            StagedAction earlier = registry.actions(source.id()).stream().filter(action -> action.operationKey().equals(key.value())).findFirst().orElse(null);
            return new PlaceLookup(source, earlier);
        }), "lookup");
        ArtifactRecord source = lookup.source();
        if (!request.expectedRevision().equals(request.target().revision())) {
            throw new RequestRefusedException(RequestErrorCode.TARGET_REVISION_CONFLICT);
        }
        Digest inputDigest = inputDigestOf(request);
        if (lookup.earlier() != null) {
            return answerEarlier(lookup.earlier(), inputDigest);
        }
        return reserveAndPublish(request, key, source, inputDigest);
    }

    private StagedPlacement answerEarlier(StagedAction earlier, Digest inputDigest) {
        if (!earlier.inputDigest().equals(inputDigest)) {
            throw new RequestRefusedException(RequestErrorCode.OPERATION_KEY_CONFLICT);
        }
        if (earlier.state() == StagedActionState.RESERVED) {
            Optional<Operation> accepted = acceptedBy(earlier);
            if (accepted.isPresent()) {
                adopt(earlier, accepted.orElseThrow());
                return new StagedPlacement.Placed(earlier.requestId(), StagedActionState.SUBMITTED);
            }
        }
        return new StagedPlacement.Placed(earlier.requestId(), earlier.state());
    }

    private Optional<Operation> acceptedBy(StagedAction action) {
        Optional<Operation> found = readTx.execute(status -> journal.findByRequestKey(action.requestKey()));
        return found == null ? Optional.empty() : found.filter(operation -> operation.id().equals(action.requestId()));
    }

    private void adopt(StagedAction reserved, Operation accepted) {
        writeTx.executeWithoutResult(status -> {
            if (accepted.input() instanceof UploadInput input) {
                registry.submit(input.artifactId(), accepted.id());
            }
            registry.updateAction(reserved.artifactId(), reserved.operationKey(), StagedActionState.SUBMITTED);
        });
    }

    private StagedPlacement reserveAndPublish(StagedPlacementRequest request, RequestKey key, ArtifactRecord source, Digest inputDigest) {
        requireUsable(source);
        StagedAction reserved = new StagedAction(source.id(), key.value(), requestKeyOf(request.owner(), key), inputDigest, operationIds.next(), request.kind(),
            StagedActionState.RESERVED, request.target().target().id(), request.target().revision(), request.remotePath(), request.overwrite(), request.elevation());
        try {
            // Serialize concurrent actions on the same staged file via the artifact row lock in the database (no JVM locks, Principles 4.4).
            writeTx.executeWithoutResult(status -> {
                registry.lockForAction(source.id());
                requireNoOpenDoubt(source, request.kind());
                registry.addAction(reserved);
            });
        } catch (RequestRefusedException e) {
            if (e.code() != ArtifactErrorCode.OPAQUE_ACTION_CONFLICT) {
                throw e;
            }
            // The write transaction rolled back cleanly (PostgreSQL-safe, no 25P02): read the winning action in a fresh read-only transaction.
            Optional<StagedAction> earlier = readTx.execute(status -> registry.actions(source.id()).stream()
                .filter(action -> action.operationKey().equals(key.value())).findFirst());
            return (earlier == null ? Optional.<StagedAction>empty() : earlier)
                .map(found -> answerEarlier(found, inputDigest)).orElseThrow(() -> e);
        }
        return publishCopy(request, key, source, reserved);
    }

    /**
     * A move is refused while an earlier action of the file has an outcome that nobody knows: an operation that is {@code UNKNOWN}, or one that was submitted and is not in the
     * journal (RC-B-134, {@code OPAQUE_ACTION_RECONCILIATION_REQUIRED}); the owner settles it first (see {@code OwnerReconciliation}), because the move consumes the file and
     * the earlier copy may or may not have reached the target. Then both kinds are refused while a move is open or being reserved ({@code ARTIFACT_BUSY}); a copy that is merely
     * pending or running does not stop another action (each action has a copy of its own).
     */
    private void requireNoOpenDoubt(ArtifactRecord source, StagedActionKind kind) {
        List<StagedAction> actions = registry.actions(source.id());
        if (kind == StagedActionKind.MOVE) {
            for (StagedAction action : actions) {
                if (action.state() == StagedActionState.SUBMITTED && journal.find(action.requestId()).map(operation -> operation.state() == OperationState.UNKNOWN).orElse(true)) {
                    throw new RequestRefusedException(ArtifactErrorCode.OPAQUE_ACTION_RECONCILIATION_REQUIRED);
                }
            }
        }
        boolean moveOpen = actions.stream().anyMatch(action -> action.kind() == StagedActionKind.MOVE && dependsOnTheFile(action));
        boolean reservedBeforeMove = kind == StagedActionKind.MOVE && actions.stream().anyMatch(action -> action.state() == StagedActionState.RESERVED);
        if (moveOpen || reservedBeforeMove) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_BUSY);
        }
    }

    private StagedPlacement publishCopy(StagedPlacementRequest request, RequestKey key, ArtifactRecord source, StagedAction reserved) {
        ArtifactId copyId = null;
        try {
            ArtifactSpec copySpec = new ArtifactSpec(ArtifactDirection.UPLOAD, source.spec().fileName(), source.spec().sizeBytes(), new ArtifactOrigin.Transfer(),
                new ArtifactDestination.Assigned(request.remotePath(), request.target().target().id(), request.target().revision(), BindingKind.BOUND), request.overwrite(),
                request.elevation(), UploadProtocol.MCP_JSON_V1, ArtifactRetention.RELEASABLE, times.upload());
            Digest sourceDigest = ((ContentDigest.Sealed) source.content()).sha256();
            ImportResult imported = importer.importStream(copySpec, () -> bytesOf(source), sourceDigest);
            if (imported instanceof ImportResult.Refused refused) {
                refuse(reserved);
                return new StagedPlacement.Refused(refused.refusal());
            }
            if (!(imported instanceof ImportResult.Imported done)) {
                // The staged bytes could not be read back as they were stored: the file is no longer what the owner staged.
                throw new RequestRefusedException(ArtifactErrorCode.STAGED_FILE_CHANGED);
            }
            ArtifactRecord copy = done.artifact();
            copyId = copy.id();
            OperationId admitted = admission.admit(newOperation(request, key, copy, sourceDigest), reserved.requestId());
            if (!admitted.equals(reserved.requestId())) {
                throw new RequestRefusedException(RequestErrorCode.OPERATION_KEY_CONFLICT);
            }
            writeTx.executeWithoutResult(status -> {
                registry.submit(copy.id(), admitted);
                registry.updateAction(reserved.artifactId(), reserved.operationKey(), StagedActionState.SUBMITTED);
            });
            return new StagedPlacement.Placed(admitted, StagedActionState.SUBMITTED);
        } catch (RuntimeException failure) {
            return afterFailure(reserved, copyId, failure);
        }
    }

    /**
     * A step after the reservation failed. When the operation is in the journal the admission worked and only a later step failed: the action is submitted and the caller gets
     * the placement. When it is not, the action is refused and the copy removed, and the failure is the answer. When the journal cannot be asked, nothing is decided: the action
     * stays reserved and the copy stays, for the same key again or the next start to settle.
     */
    private StagedPlacement afterFailure(StagedAction reserved, ArtifactId copyId, RuntimeException failure) {
        Optional<Operation> accepted;
        try {
            accepted = acceptedBy(reserved);
        } catch (RuntimeException lookup) {
            failure.addSuppressed(lookup);
            LOG.warn("Whether the upload of the staged file was accepted could not be told; the action stays reserved", failure);
            throw failure;
        }
        if (accepted.isPresent()) {
            try {
                adopt(reserved, accepted.orElseThrow());
                return new StagedPlacement.Placed(reserved.requestId(), StagedActionState.SUBMITTED);
            } catch (RuntimeException adoption) {
                LOG.warn("The upload of the staged file was accepted but its action could not be submitted; the same key again or the next start does it", adoption);
                return new StagedPlacement.Placed(reserved.requestId(), StagedActionState.RESERVED);
            }
        }
        refuse(reserved);
        if (copyId != null) {
            discard(copyId);
        }
        throw failure;
    }

    /** Ends a reserved action that no operation will ever answer to. A failure is logged: the action stays reserved and the start-up check refuses it. */
    private void refuse(StagedAction reserved) {
        try {
            writeTx.executeWithoutResult(status -> registry.updateAction(reserved.artifactId(), reserved.operationKey(), StagedActionState.REFUSED));
        } catch (RuntimeException e) {
            LOG.warn("The action {} of a staged file could not be refused; the start-up check refuses it", reserved.operationKey(), e);
        }
    }

    public int recoverReservations() {
        List<StagedAction> reservedActions = Objects.requireNonNull(readTx.execute(status -> {
            List<StagedAction> found = new java.util.ArrayList<>();
            for (ArtifactRecord record : registry.holding()) {
                if (!(record.spec().origin() instanceof ArtifactOrigin.Staged)) {
                    continue;
                }
                for (StagedAction action : registry.actions(record.id())) {
                    if (action.state() == StagedActionState.RESERVED) {
                        found.add(action);
                    }
                }
            }
            return found;
        }), "reservedActions");
        int settled = 0;
        for (StagedAction action : reservedActions) {
            try {
                Optional<Operation> accepted = acceptedBy(action);
                if (accepted.isPresent()) {
                    adopt(action, accepted.orElseThrow());
                } else {
                    writeTx.executeWithoutResult(status -> registry.updateAction(action.artifactId(), action.operationKey(), StagedActionState.REFUSED));
                }
                settled++;
            } catch (RuntimeException e) {
                LOG.warn("The reserved action {} of a staged file could not be settled at the start; it is tried again at the next start", action.operationKey(), e);
            }
        }
        return settled;
    }

    /** The stored bytes of a staged file; an empty file has no payload file, so it reads as no bytes. */
    private PayloadRead bytesOf(ArtifactRecord source) {
        if (source.spec().sizeBytes() == 0) {
            return new PayloadRead(Channels.newChannel(new ByteArrayInputStream(new byte[0])), 0L);
        }
        return payloads.open(source.id());
    }

    public void operationSettled(OwnerId owner, ArtifactUuid uuid, String operationKey, OperationState state, CompletionEvidence evidence) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(evidence, "evidence");
        record ActionTarget(ArtifactRecord record, StagedAction action) {
        }
        ActionTarget target = Objects.requireNonNull(readTx.execute(status -> {
            ArtifactRecord record = owned(owner, uuid);
            StagedAction action = registry.actions(record.id()).stream().filter(candidate -> candidate.operationKey().equals(operationKey)).findFirst()
                .orElseThrow(() -> new RequestRefusedException(ArtifactErrorCode.ARTIFACT_NOT_FOUND));
            return new ActionTarget(record, action);
        }), "target");
        settle(target.record(), target.action(), state, evidence);
    }

    public void operationSettled(OperationId operation, OperationState state, CompletionEvidence evidence) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(evidence, "evidence");
        record ActionTarget(ArtifactRecord record, StagedAction action) {
        }
        Optional<ActionTarget> target = readTx.execute(status -> registry.actionOf(operation)
            .flatMap(action -> registry.find(action.artifactId()).map(record -> new ActionTarget(record, action))));
        if (target != null && target.isPresent()) {
            ActionTarget found = target.orElseThrow();
            settle(found.record(), found.action(), state, evidence);
        }
    }

    private void settle(ArtifactRecord record, StagedAction action, OperationState state, CompletionEvidence evidence) {
        if (!state.isFinal() || action.state() == StagedActionState.SETTLED || action.state() == StagedActionState.REFUSED) {
            return;
        }
        if (action.kind() == StagedActionKind.MOVE && state == OperationState.COMPLETED && publishedThisFile(record, evidence)) {
            if (record.state() == ArtifactState.READY) {
                writeTx.executeWithoutResult(status -> registry.beginConsume(record.id(), ConsumeReason.MOVED));
            }
            payloads.delete(record.id());
            writeTx.executeWithoutResult(status -> {
                registry.completeConsume(record.id(), ConsumeReason.MOVED);
                registry.updateAction(record.id(), action.operationKey(), StagedActionState.SETTLED);
            });
            return;
        }
        writeTx.executeWithoutResult(status -> registry.updateAction(record.id(), action.operationKey(), StagedActionState.SETTLED));
    }

    private boolean publishedThisFile(ArtifactRecord record, CompletionEvidence evidence) {
        return evidence instanceof CompletionEvidence.FilePublished published && record.content() instanceof ContentDigest.Sealed sealed
            && sealed.sha256().equals(published.sha256());
    }

    // ---- helpers ----

    private void requireOpen() {
        if (maintenance.occupied()) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_MAINTENANCE);
        }
    }

    private RequestKey requestKey(String operationKey) {
        try {
            return new RequestKey(Objects.requireNonNull(operationKey, "operationKey"));
        } catch (IllegalArgumentException e) {
            throw new RequestRefusedException(ArtifactErrorCode.INVALID_OPERATION_KEY, e);
        }
    }

    /** The request key of the upload: the owner and the operation key, so two owners with the same operation key never meet in the journal. */
    private RequestKey requestKeyOf(OwnerId owner, RequestKey operationKey) {
        return new RequestKey("opaque:" + digester.digestUtf8(owner.value() + "\0" + operationKey.value()).hex());
    }

    private ArtifactRecord owned(OwnerId owner, ArtifactUuid uuid) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(uuid, "uuid");
        ArtifactRecord record = registry.findStaged(uuid).orElseThrow(() -> new RequestRefusedException(ArtifactErrorCode.ARTIFACT_NOT_FOUND));
        if (!(record.spec().origin() instanceof ArtifactOrigin.Staged staged) || !staged.owner().equals(owner)) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_NOT_FOUND);
        }
        return record;
    }

    private void requireNotExpired(ArtifactRecord record, ArtifactErrorCode code) {
        if (record.spec().retention() == ArtifactRetention.RELEASABLE && !clock.instant().isBefore(record.expiresAt())) {
            throw new RequestRefusedException(code);
        }
    }

    /** A file that can be sent: ready, not in use, not expired, and its digest known. */
    private void requireUsable(ArtifactRecord source) {
        if (source.consumption() instanceof ConsumeState.Consuming || source.consumption() instanceof ConsumeState.ConsumedBecause) {
            throw new RequestRefusedException(ArtifactErrorCode.STAGED_FILE_CONSUMED);
        }
        if (source.state() != ArtifactState.READY || !(source.content() instanceof ContentDigest.Sealed)) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_NOT_READY);
        }
        requireNotExpired(source, ArtifactErrorCode.STAGED_FILE_EXPIRED);
    }

    private Digest inputDigestOf(StagedPlacementRequest request) {
        return new CanonicalForm().field("uuid", request.uuid().text()).field("action", request.kind().name()).field("targetId", request.target().target().id().value())
            .field("targetRevision", request.target().revision().hex()).field("remotePath", request.remotePath().value()).field("overwrite", request.overwrite().name())
            .field("elevation", request.elevation().name()).digest(digester);
    }

    private NewOperation newOperation(StagedPlacementRequest request, RequestKey key, ArtifactRecord copy, Digest sha256) {
        ResolvedTarget target = request.target();
        UploadInput input = new UploadInput(copy.id(), request.remotePath(), copy.spec().fileName(), copy.spec().sizeBytes(), sha256, request.overwrite(), request.elevation(),
            new FileBinding.Bound(new FileTargetBinding(target.target().id(), target.revision())), UploadProtocol.MCP_JSON_V1);
        return NewOperation.of(digester, requestKeyOf(request.owner(), key), request.owner(), target.target().id(), target.revision(), target.target().writeProfile(), input,
            new ConflictKey.Of("upload:" + digester.digestUtf8(request.remotePath().value()).hex()), new Deadline.None());
    }

    private Digest hashOfStoredBytes(ArtifactRecord record) {
        RunningDigest whole = new RunningDigest();
        if (record.spec().sizeBytes() == 0) {
            return whole.finish();
        }
        try (PayloadRead read = payloads.open(record.id())) {
            ByteBuffer buffer = ByteBuffer.allocate(ChunkDigest.MAX_CHUNK_BYTES);
            long remaining = record.spec().sizeBytes();
            while (remaining > 0 && read.channel().read(buffer) >= 0) {
                buffer.flip();
                int take = (int) Math.min(buffer.remaining(), remaining);
                whole.update(buffer.array(), 0, take);
                remaining -= take;
                buffer.clear();
            }
        } catch (IOException e) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_STORAGE_IO_ERROR, e);
        }
        return whole.finish();
    }

    /** Removes a copy that no operation will ever use. A failure is logged: the row is {@code DELETING} and the start-up check finishes the removal. */
    private void discard(ArtifactId id) {
        try {
            writeTx.executeWithoutResult(status -> registry.beginDelete(id));
            payloads.delete(id);
            writeTx.executeWithoutResult(status -> registry.release(id, ReleaseReason.REFUSED));
        } catch (RuntimeException e) {
            LOG.warn("The copy {} of a staged file could not be discarded; the start-up check removes it", id.value(), e);
        }
    }
}
