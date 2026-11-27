package io.github.jaeseongs95.opermand.app.artifact;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.github.jaeseongs95.opermand.app.storage.DiskCheck;
import io.github.jaeseongs95.opermand.domain.id.ArtifactId;
import io.github.jaeseongs95.opermand.domain.id.ArtifactUuid;
import io.github.jaeseongs95.opermand.domain.id.Digest;
import io.github.jaeseongs95.opermand.domain.id.OperationId;
import io.github.jaeseongs95.opermand.domain.id.OwnerId;

/**
 * The metadata and the budget of the artifacts (2.x {@code artifacts.mjs}, manifest files {@code <id>.json}). The bytes are {@link ArtifactPayloadStore}'s: the
 * use case writes the bytes first and the metadata second (design 8.5, "bytes forced to disk, then metadata commit"), so a crash leaves at worst a byte tail that the
 * start-up check truncates. No method of this interface touches a file, so each runs in one short transaction.
 */
public interface ArtifactRegistry {

    /**
     * Reserves the budget and creates the row in {@code STAGING}. The caller measures free disk space outside the transaction and passes {@code disk} (Principles 4.4);
     * checks are judged in order: per-file size limit, budget quota, then disk margin.
     */
    ArtifactReservation reserve(ArtifactSpec spec, Instant at, DiskCheck disk);

    /** Acknowledges the next chunk ({@code chunkNo} is the count so far); a chunk number out of order throws {@code ARTIFACT_CHUNK_CONFLICT}. */
    ArtifactRecord append(ArtifactId id, int chunkNo, ChunkDigest chunk);

    /** {@code STAGING} to {@code READY} once all bytes arrived and the whole-file digest is known. */
    ArtifactRecord markReady(ArtifactId id, Digest sha256);

    /**
     * {@code STAGING} to {@code READY} for a download, whose size was not known when the budget was reserved (the reservation holds no bytes, design 5.2, until {@link #sizeDownload} sets it): the declared size becomes the bytes received, so the charge is the real one. Only a {@code DOWNLOAD} that is
     * {@code STAGING} can be completed this way; any other artifact throws {@code ARTIFACT_NOT_WRITABLE}. Repeating it with the same digest answers the same record.
     */
    ArtifactRecord completeDownload(ArtifactId id, Digest sha256);

    /**
     * Sets the reservation of a download to {@code sizeBytes}, using pre-measured {@code disk} space (measured outside the transaction, Principles 4.4).
     */
    ArtifactReservation sizeDownload(ArtifactId id, long sizeBytes, DiskCheck disk);

    /** {@code Optional} here has the one meaning "no such artifact". */
    Optional<ArtifactRecord> find(ArtifactId id);

    Optional<ArtifactRecord> findStaged(ArtifactUuid publicUuid);

    /**
     * The staged file of an owner and operation key: the retry of a begin that must answer with the same public UUID (RC-B-091). {@code Optional} here has the one
     * meaning "no staged file for this owner and key".
     */
    Optional<ArtifactRecord> findStagedByKey(OwnerId owner, String operationKey);

    /** Every artifact that can hold bytes: all states except {@code CONSUMED} (a consumed row keeps its metadata only). The start-up check compares them with the files. */
    List<ArtifactRecord> holding();

    /** The acknowledged chunks of the artifact in chunk-number order; empty when there is none or the artifact is unknown. */
    List<ChunkDigest> chunks(ArtifactId id);

    ArtifactBudget budget();

    /** Releasable artifacts whose expiry is not after {@code now}; pinned ones are never listed, and neither are rows in {@code DELETING} (see {@link #deleting()}). */
    List<ArtifactRecord> expired(Instant now);

    /**
     * The artifacts whose removal began and did not finish: the row is {@code DELETING}, and the bytes may or may not be gone yet (a use case that failed to delete them
     * logged the failure and left the row, defect D-0012). The removal is due at once, whatever the expiry, so the reap lists them with {@link #expired} and finishes them.
     */
    List<ArtifactRecord> deleting();

    /** Records that an operation was made from the artifact. */
    ArtifactRecord submit(ArtifactId id, OperationId operationId);

    /**
     * Gives an artifact back to the expiry calculation: its retention goes from {@code PINNED} to {@code RELEASABLE}. The use case calls it when the operation that
     * depended on the artifact reached a final state (design 1.3, S3-AR-07). An artifact that is not pinned stays as it is, so the call may be repeated.
     */
    ArtifactRecord unpin(ArtifactId id, UnpinReason reason);

    /**
     * {@code READY} to {@code CONSUMING}: a staged file starts to be moved or removed, and the reason is recorded now. A crash before {@link #completeConsume} leaves
     * {@link ConsumeState.Consuming}, so the recovery ends the use for the same reason. A file already in use throws {@code STAGED_FILE_CONSUMED}.
     */
    ArtifactRecord beginConsume(ArtifactId id, ConsumeReason reason);

    /** {@code CONSUMING} to {@code CONSUMED}; the row stays and charges one byte. The reason must be the one {@link #beginConsume} recorded. */
    ArtifactRecord completeConsume(ArtifactId id, ConsumeReason reason);

    /** The rows whose use as a moved or removed file began and did not finish ({@link ConsumeState.Consuming}), in the order of their ids; the reap and the start-up check finish them. */
    List<ArtifactRecord> consuming();

    /** Marks the row {@code DELETING} before the bytes are deleted; the row is removed by {@link #release}. */
    ArtifactRecord beginDelete(ArtifactId id);

    /**
     * {@link #beginDelete} for a caller that expects the row may be gone, such as the reap, whose list can be older than the use case that removed the row meanwhile (defect
     * D-0012). It answers {@code true} when the row is {@code DELETING} now (it was {@code STAGING} or {@code READY}, or it already was {@code DELETING}) and {@code false}
     * when there is no such artifact, in which case nothing changed: an unknown id is an answer, not a refusal. Every other refusal of {@link #beginDelete} is thrown
     * ({@code ARTIFACT_BUSY} for a file in use, {@code STAGED_FILE_CONSUMED} for a consumed one). It is one conditional update, so it sets no transaction boundary of its own and the loser
     * of a race is told by the rows changed.
     */
    boolean beginDeleteIfPresent(ArtifactId id);

    /** Removes the row and gives its budget back. */
    void release(ArtifactId id, ReleaseReason reason);

    void addAction(StagedAction action);

    List<StagedAction> actions(ArtifactId id);

    /**
     * The use of a staged file that the operation was submitted for, or nothing when the operation is not the operation of a staged file. An operation belongs to at most one
     * staged action, so the answer is one action or none.
     */
    Optional<StagedAction> actionOf(OperationId operation);

    void updateAction(ArtifactId id, String operationKey, StagedActionState state);

    /**
     * Acquires the database row lock on a {@code READY} staged artifact inside the caller's transaction so concurrent actions serialize at the database without JVM locks.
     */
    default void lockForAction(ArtifactId id) {
    }

    /** Reconciles the {@code storage_budget} quota row for artifacts against live rows (called during startup recovery). */
    default void reconcileBudget() {
    }
}
