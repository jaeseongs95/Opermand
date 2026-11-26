package io.github.jaeseongs95.portal.store.artifact;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.stereotype.Repository;

import io.github.jaeseongs95.portal.app.artifact.ArtifactBudget;
import io.github.jaeseongs95.portal.app.artifact.ArtifactDirection;
import io.github.jaeseongs95.portal.app.artifact.ArtifactRecord;
import io.github.jaeseongs95.portal.app.artifact.ArtifactRegistry;
import io.github.jaeseongs95.portal.app.artifact.ArtifactReservation;
import io.github.jaeseongs95.portal.app.artifact.ArtifactRetention;
import io.github.jaeseongs95.portal.app.artifact.ArtifactSpec;
import io.github.jaeseongs95.portal.app.artifact.ArtifactState;
import io.github.jaeseongs95.portal.app.artifact.ChunkDigest;
import io.github.jaeseongs95.portal.app.artifact.ConsumeReason;
import io.github.jaeseongs95.portal.app.artifact.ReleaseReason;
import io.github.jaeseongs95.portal.app.artifact.StagedAction;
import io.github.jaeseongs95.portal.app.artifact.StagedActionState;
import io.github.jaeseongs95.portal.app.artifact.UnpinReason;
import io.github.jaeseongs95.portal.app.storage.DiskCheck;
import io.github.jaeseongs95.portal.domain.error.ArtifactErrorCode;
import io.github.jaeseongs95.portal.domain.error.ErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.domain.id.ArtifactId;
import io.github.jaeseongs95.portal.domain.id.ArtifactIdGenerator;
import io.github.jaeseongs95.portal.domain.id.ArtifactUuid;
import io.github.jaeseongs95.portal.domain.id.Digest;
import io.github.jaeseongs95.portal.domain.id.OperationId;
import io.github.jaeseongs95.portal.domain.id.OwnerId;
import io.github.jaeseongs95.portal.store.storage.StorageBudgetRow;
import io.github.jaeseongs95.portal.store.storage.StorageBudgetRowRepository;

/**
 * {@link ArtifactRegistry} over {@code artifact}, {@code artifact_chunk}, {@code staged_action}, and {@code storage_budget} (SSH_PORTAL-D-0058).
 * Transaction boundaries belong to the calling service (Principles 4.4); this repository opens no transactions and performs no reservation disk I/O.
 * Every quota-changing mutation locks {@code storage_budget} first (Lock Order #1) before mutating {@code artifact}, preventing oversubscription and AB-BA deadlocks.
 */
@Repository
public class JdbcArtifactRegistry implements ArtifactRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcArtifactRegistry.class);
    private static final String SCOPE = StorageBudgetRowRepository.ARTIFACT_SCOPE;

    /** The states in which an expired, unpinned artifact is released: not {@code CONSUMING} (in use) and not {@code DELETING} (being removed; {@link #deleting()} lists those). */
    private static final List<ArtifactState> RELEASABLE_STATES = List.of(ArtifactState.STAGING, ArtifactState.READY, ArtifactState.CONSUMED);

    private final ArtifactRowRepository artifacts;
    private final ArtifactChunkRowRepository chunks;
    private final StagedActionRowRepository actions;
    private final StorageBudgetRowRepository budgets;
    private final JdbcAggregateOperations aggregates;
    private final ArtifactMapper mapper;
    private final RetainedPayloads retained;
    private final ArtifactIdGenerator ids;
    private final ArtifactLimits limits;

    public JdbcArtifactRegistry(ArtifactRowRepository artifacts, ArtifactChunkRowRepository chunks, StagedActionRowRepository actions,
                                StorageBudgetRowRepository budgets, JdbcAggregateOperations aggregates, ArtifactMapper mapper,
                                RetainedPayloads retained, ArtifactIdGenerator ids, ArtifactLimits limits) {
        this.artifacts = artifacts;
        this.chunks = chunks;
        this.actions = actions;
        this.budgets = budgets;
        this.aggregates = aggregates;
        this.mapper = mapper;
        this.retained = retained;
        this.ids = ids;
        this.limits = limits;
    }

    @Override
    public ArtifactReservation reserve(ArtifactSpec spec, Instant at, DiskCheck disk) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(disk, "disk");
        if (spec.sizeBytes() > limits.maxFileBytes()) {
            return new ArtifactReservation.FileTooLarge(spec.sizeBytes(), limits.maxFileBytes());
        }
        long deltaBytes = Math.max(1L, spec.sizeBytes());
        if (deltaBytes > limits.budgetBytes()) {
            return new ArtifactReservation.BudgetExceeded(spec.sizeBytes(), budgetRow().chargedBytes(), limits.budgetBytes());
        }
        if (budgets.reserveBytes(SCOPE, deltaBytes, 1L, limits.budgetBytes() - deltaBytes) != 1) {
            return new ArtifactReservation.BudgetExceeded(spec.sizeBytes(), budgetRow().chargedBytes(), limits.budgetBytes());
        }
        long usable = disk.usableBytes();
        if (usable - spec.sizeBytes() < limits.diskMarginBytes()) {
            budgets.adjust(SCOPE, -deltaBytes, -1L);
            return new ArtifactReservation.DiskSpaceInsufficient(spec.sizeBytes(), usable, limits.diskMarginBytes());
        }
        ArtifactId id = ids.next();
        ArtifactRow row = mapper.toNewRow(id, spec, at);
        try {
            aggregates.insert(row);
        } catch (DuplicateKeyException e) {
            // The public UUID, or the pair (owner, operationKey), exists already; rollback undoes the storage_budget increment.
            throw new RequestRefusedException(RequestErrorCode.OPERATION_KEY_CONFLICT, e);
        }
        return new ArtifactReservation.Reserved(id);
    }

    @Override
    public ArtifactRecord append(ArtifactId id, int chunkNo, ChunkDigest chunk) {
        Objects.requireNonNull(chunk, "chunk");
        ArtifactRow row = require(id);
        if (row.state() != ArtifactState.STAGING) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_NOT_WRITABLE);
        }
        if (chunk.sizeBytes() > limits.chunkBytes()) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_INVALID_CHUNK);
        }
        if (chunkNo != row.chunkCount()) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_CHUNK_CONFLICT);
        }
        if (row.receivedBytes() + chunk.sizeBytes() > row.sizeBytes()) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_SEQUENCE_OR_SIZE);
        }
        long next = takeLock(row, ArtifactErrorCode.ARTIFACT_CHUNK_CONFLICT);
        aggregates.insert(mapper.toChunkRow(id, chunkNo, chunk));
        ArtifactRow updated = row.withChunk(chunk.sizeBytes(), next);
        aggregates.update(updated);
        return mapper.toRecord(updated);
    }

    @Override
    public ArtifactRecord markReady(ArtifactId id, Digest sha256) {
        Objects.requireNonNull(sha256, "sha256");
        ArtifactRow row = require(id);
        if (row.state() == ArtifactState.READY) {
            if (Objects.equals(row.sha256(), sha256.hex())) {
                return mapper.toRecord(row);
            }
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_HASH_MISMATCH);
        }
        if (row.state() != ArtifactState.STAGING) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_NOT_WRITABLE);
        }
        if (row.receivedBytes() != row.sizeBytes()) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_INCOMPLETE);
        }
        ArtifactRow updated = row.ready(sha256.hex(), takeLock(row, ArtifactErrorCode.ARTIFACT_CHANGED));
        aggregates.update(updated);
        return mapper.toRecord(updated);
    }

    @Override
    public ArtifactRecord completeDownload(ArtifactId id, Digest sha256) {
        Objects.requireNonNull(sha256, "sha256");
        lockBudget();
        ArtifactRow row = require(id);
        if (row.direction() != ArtifactDirection.DOWNLOAD) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_NOT_WRITABLE);
        }
        if (row.state() == ArtifactState.READY) {
            if (Objects.equals(row.sha256(), sha256.hex())) {
                return mapper.toRecord(row);
            }
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_HASH_MISMATCH);
        }
        if (row.state() != ArtifactState.STAGING) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_NOT_WRITABLE);
        }
        ArtifactRow updated = row.downloaded(sha256.hex(), takeLock(row, ArtifactErrorCode.ARTIFACT_CHANGED));
        long delta = Math.max(1L, row.receivedBytes()) - Math.max(1L, row.sizeBytes());
        if (delta != 0L) {
            budgets.adjust(SCOPE, delta, 0L);
        }
        aggregates.update(updated);
        return mapper.toRecord(updated);
    }

    @Override
    public ArtifactReservation sizeDownload(ArtifactId id, long sizeBytes, DiskCheck disk) {
        Objects.requireNonNull(disk, "disk");
        if (sizeBytes < 0) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_SEQUENCE_OR_SIZE);
        }
        if (sizeBytes > limits.maxFileBytes()) {
            return new ArtifactReservation.FileTooLarge(sizeBytes, limits.maxFileBytes());
        }
        lockBudget();
        ArtifactRow row = require(id);
        if (row.direction() != ArtifactDirection.DOWNLOAD || row.state() != ArtifactState.STAGING || row.receivedBytes() != 0L) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_NOT_WRITABLE);
        }
        long oldCharge = Math.max(1L, row.sizeBytes());
        long newCharge = Math.max(1L, sizeBytes);
        long delta = newCharge - oldCharge;
        if (delta > 0L) {
            if (delta > limits.budgetBytes() || budgets.reserveBytes(SCOPE, delta, 0L, limits.budgetBytes() - delta) != 1) {
                return new ArtifactReservation.BudgetExceeded(sizeBytes, budgetRow().chargedBytes() - oldCharge, limits.budgetBytes());
            }
        }
        long usable = disk.usableBytes();
        if (usable - sizeBytes < limits.diskMarginBytes()) {
            if (delta > 0L) {
                budgets.adjust(SCOPE, -delta, 0L);
            }
            return new ArtifactReservation.DiskSpaceInsufficient(sizeBytes, usable, limits.diskMarginBytes());
        }
        if (delta < 0L) {
            budgets.adjust(SCOPE, delta, 0L);
        }
        if (artifacts.resizeDownload(id.value(), sizeBytes) != 1) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_CHANGED);
        }
        return new ArtifactReservation.Reserved(id);
    }

    @Override
    public Optional<ArtifactRecord> find(ArtifactId id) {
        return artifacts.findById(id.value()).map(mapper::toRecord);
    }

    @Override
    public Optional<ArtifactRecord> findStaged(ArtifactUuid publicUuid) {
        return artifacts.findByPublicUuid(publicUuid.text()).map(mapper::toRecord);
    }

    /** Reads the quarantine folder outside any write transaction, then reads the domain quota row (defect D-0012, D-0058). */
    @Override
    public ArtifactBudget budget() {
        RetainedPayloads.Held quarantined = retained.held();
        return new ArtifactBudget(limits.budgetBytes(), budgetRow().chargedBytes(), quarantined.files(), quarantined.bytes());
    }

    @Override
    public List<ArtifactRecord> expired(Instant now) {
        Objects.requireNonNull(now, "now");
        return artifacts.findByRetentionAndStateInAndExpiresAtLessThanEqualOrderByExpiresAtAscIdAsc(ArtifactRetention.RELEASABLE, RELEASABLE_STATES, now).stream()
            .map(mapper::toRecord).toList();
    }

    @Override
    public List<ArtifactRecord> consuming() {
        return artifacts.findByStateOrderByIdAsc(ArtifactState.CONSUMING).stream().map(mapper::toRecord).toList();
    }

    @Override
    public List<ArtifactRecord> deleting() {
        return artifacts.findByStateOrderByIdAsc(ArtifactState.DELETING).stream().map(mapper::toRecord).toList();
    }

    @Override
    public ArtifactRecord submit(ArtifactId id, OperationId operationId) {
        Objects.requireNonNull(operationId, "operationId");
        ArtifactRow row = require(id);
        if (row.submissionId() != null) {
            if (Objects.equals(row.submissionId(), operationId.value())) {
                return mapper.toRecord(row);
            }
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_ALREADY_SUBMITTED);
        }
        if (row.state() != ArtifactState.READY) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_NOT_READY);
        }
        ArtifactRow updated = row.submittedAs(operationId.value(), takeLock(row, ArtifactErrorCode.ARTIFACT_ALREADY_SUBMITTED));
        aggregates.update(updated);
        return mapper.toRecord(updated);
    }

    @Override
    public ArtifactRecord unpin(ArtifactId id, UnpinReason reason) {
        Objects.requireNonNull(reason, "reason");
        ArtifactRow row = require(id);
        if (row.retention() == ArtifactRetention.RELEASABLE) {
            return mapper.toRecord(row);
        }
        ArtifactRow updated = row.unpinned(takeLock(row, ArtifactErrorCode.ARTIFACT_CHANGED));
        aggregates.update(updated);
        LOG.info("Artifact {} is no longer pinned: {}", id.value(), reason);
        return mapper.toRecord(updated);
    }

    @Override
    public ArtifactRecord beginConsume(ArtifactId id, ConsumeReason reason) {
        Objects.requireNonNull(reason, "reason");
        lockBudget();
        ArtifactRow row = require(id);
        switch (row.state()) {
            case READY -> {
                // fall out of the switch: the transition below
            }
            case STAGING -> {
                if (reason != ConsumeReason.REMOVED) {
                    throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_NOT_READY);
                }
            }
            case CONSUMING, CONSUMED -> throw new RequestRefusedException(ArtifactErrorCode.STAGED_FILE_CONSUMED);
            case DELETING -> throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_NOT_READY);
        }
        ArtifactRow updated = row.consumingBecause(reason, takeLock(row, ArtifactErrorCode.STAGED_FILE_CONSUMED));
        budgets.adjust(SCOPE, -Math.max(1L, row.sizeBytes()), 0L);
        aggregates.update(updated);
        return mapper.toRecord(updated);
    }

    @Override
    public ArtifactRecord completeConsume(ArtifactId id, ConsumeReason reason) {
        Objects.requireNonNull(reason, "reason");
        lockBudget();
        ArtifactRow row = require(id);
        if (row.state() == ArtifactState.CONSUMED) {
            return answerConsumed(row, reason);
        }
        if (row.state() != ArtifactState.CONSUMING || row.consumeReason() != reason) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_CHANGED);
        }
        long next = row.version() + 1;
        if (artifacts.bumpVersion(row.id(), row.version(), next) != 1) {
            return answerConsumed(require(id), reason);
        }
        ArtifactRow updated = row.consumedBecause(reason, next);
        budgets.adjust(SCOPE, 1L, 0L);
        aggregates.update(updated);
        return mapper.toRecord(updated);
    }

    private ArtifactRecord answerConsumed(ArtifactRow row, ConsumeReason reason) {
        if (row.state() == ArtifactState.CONSUMED && row.consumeReason() == reason) {
            return mapper.toRecord(row);
        }
        if (row.state() == ArtifactState.CONSUMED) {
            throw new RequestRefusedException(ArtifactErrorCode.STAGED_FILE_CONSUMED);
        }
        throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_CHANGED);
    }

    @Override
    public ArtifactRecord beginDelete(ArtifactId id) {
        lockBudget();
        ArtifactRow row = require(id);
        switch (row.state()) {
            case DELETING -> {
                return mapper.toRecord(row);
            }
            case STAGING, READY -> {
                // fall out of the switch: the transition below
            }
            case CONSUMING -> throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_BUSY);
            case CONSUMED -> throw new RequestRefusedException(ArtifactErrorCode.STAGED_FILE_CONSUMED);
        }
        ArtifactRow updated = row.withState(ArtifactState.DELETING, takeLock(row, ArtifactErrorCode.ARTIFACT_CHANGED));
        budgets.adjust(SCOPE, -Math.max(1L, row.sizeBytes()), 0L);
        aggregates.update(updated);
        return mapper.toRecord(updated);
    }

    @Override
    public boolean beginDeleteIfPresent(ArtifactId id) {
        lockBudget();
        Optional<ArtifactRow> before = artifacts.findById(id.value());
        if (before.isEmpty()) {
            return false;
        }
        if (artifacts.markDeleting(id.value()) == 1) {
            budgets.adjust(SCOPE, -Math.max(1L, before.orElseThrow().sizeBytes()), 0L);
            return true;
        }
        return artifacts.findById(id.value()).map(row -> switch (row.state()) {
            case DELETING -> true;
            case CONSUMING -> throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_BUSY);
            case CONSUMED -> throw new RequestRefusedException(ArtifactErrorCode.STAGED_FILE_CONSUMED);
            case STAGING, READY -> throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_CHANGED);
        }).orElse(false);
    }

    @Override
    public void release(ArtifactId id, ReleaseReason reason) {
        Objects.requireNonNull(reason, "reason");
        lockBudget();
        artifacts.findById(id.value()).ifPresent(row -> removeRows(row, reason));
    }

    private void removeRows(ArtifactRow row, ReleaseReason reason) {
        boolean neverHeldBytes = row.receivedBytes() == 0L && row.state() != ArtifactState.CONSUMING;
        boolean bytesGone = row.state() == ArtifactState.DELETING || row.state() == ArtifactState.CONSUMED || neverHeldBytes;
        if (!bytesGone) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_CHANGED);
        }
        actions.deleteAllOf(row.id());
        chunks.deleteAllOf(row.id());
        if (artifacts.removeById(row.id()) == 1) {
            budgets.adjust(SCOPE, -chargeOf(row), -1L);
        }
        LOG.info("Artifact {} released: {}", row.id(), reason);
    }

    @Override
    public void addAction(StagedAction action) {
        Objects.requireNonNull(action, "action");
        try {
            aggregates.insert(mapper.toActionRow(action));
        } catch (DuplicateKeyException e) {
            throw new RequestRefusedException(ArtifactErrorCode.OPAQUE_ACTION_CONFLICT, e);
        } catch (DataIntegrityViolationException e) {
            throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_NOT_FOUND, e);
        }
    }

    @Override
    public List<StagedAction> actions(ArtifactId id) {
        return actions.findByArtifactIdOrderByOperationKey(id.value()).stream().map(mapper::toAction).toList();
    }

    @Override
    public Optional<StagedAction> actionOf(OperationId operation) {
        Objects.requireNonNull(operation, "operation");
        return actions.findByOperation(operation.value()).stream().findFirst().map(mapper::toAction);
    }

    @Override
    public void updateAction(ArtifactId id, String operationKey, StagedActionState state) {
        Objects.requireNonNull(operationKey, "operationKey");
        Objects.requireNonNull(state, "state");
        StagedActionRow row = actions.findOne(id.value(), operationKey).orElseThrow(() -> new RequestRefusedException(ArtifactErrorCode.ARTIFACT_NOT_FOUND));
        if (row.state() == state) {
            return;
        }
        if (!allowed(row.state(), state)) {
            throw new RequestRefusedException(ArtifactErrorCode.OPAQUE_ACTION_CONFLICT);
        }
        if (actions.moveState(row.artifactId(), row.operationKey(), row.state().name(), state.name()) != 1) {
            throw new RequestRefusedException(ArtifactErrorCode.OPAQUE_ACTION_CONFLICT);
        }
    }

    @Override
    public void lockForAction(ArtifactId id) {
        if (artifacts.lockReadyForAction(id.value()) == 1) {
            return;
        }
        ArtifactRow row = require(id);
        switch (row.state()) {
            case READY -> throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_CHANGED);
            case CONSUMING, CONSUMED -> throw new RequestRefusedException(ArtifactErrorCode.STAGED_FILE_CONSUMED);
            case STAGING, DELETING -> throw new RequestRefusedException(ArtifactErrorCode.ARTIFACT_NOT_READY);
        }
    }

    @Override
    public void reconcileBudget() {
        if (budgets.reconcileArtifact() != 1) {
            throw new IllegalStateException("The storage_budget row for ARTIFACT is missing: the migration V8 was not applied");
        }
    }

    private boolean allowed(StagedActionState from, StagedActionState to) {
        return switch (from) {
            case RESERVED -> to == StagedActionState.SUBMITTED || to == StagedActionState.REFUSED;
            case SUBMITTED -> to == StagedActionState.SETTLED || to == StagedActionState.REFUSED;
            case REFUSED, SETTLED -> false;
        };
    }

    @Override
    public List<ArtifactRecord> holding() {
        return artifacts.findByStateNot(ArtifactState.CONSUMED).stream().map(mapper::toRecord).toList();
    }

    @Override
    public List<ChunkDigest> chunks(ArtifactId id) {
        return chunks.findByArtifactIdOrderByChunkNo(id.value()).stream().map(mapper::toChunk).toList();
    }

    @Override
    public Optional<ArtifactRecord> findStagedByKey(OwnerId owner, String operationKey) {
        return artifacts.findByOwnerIdAndOperationKey(owner.value(), operationKey).map(mapper::toRecord);
    }

    private ArtifactRow require(ArtifactId id) {
        return artifacts.findById(id.value()).orElseThrow(() -> new RequestRefusedException(ArtifactErrorCode.ARTIFACT_NOT_FOUND));
    }

    private long takeLock(ArtifactRow row, ErrorCode conflict) {
        long next = row.version() + 1;
        if (artifacts.bumpVersion(row.id(), row.version(), next) != 1) {
            throw new RequestRefusedException(conflict);
        }
        return next;
    }

    private void lockBudget() {
        if (budgets.adjust(SCOPE, 0L, 0L) != 1) {
            throw new IllegalStateException("The storage_budget row for ARTIFACT is missing: the migration V8 was not applied");
        }
    }

    private StorageBudgetRow budgetRow() {
        return budgets.findById(SCOPE)
            .orElseThrow(() -> new IllegalStateException("The storage_budget row for ARTIFACT is missing: the migration V8 was not applied"));
    }

    private static long chargeOf(ArtifactRow row) {
        return switch (row.state()) {
            case DELETING, CONSUMING -> 0L;
            case CONSUMED -> 1L;
            case STAGING, READY -> Math.max(1L, row.sizeBytes());
        };
    }
}
