package io.github.jaeseongs95.portal.app.artifact;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.github.jaeseongs95.portal.domain.id.ArtifactId;
import io.github.jaeseongs95.portal.domain.id.ArtifactUuid;
import io.github.jaeseongs95.portal.domain.id.Digest;
import io.github.jaeseongs95.portal.domain.id.OperationId;
import io.github.jaeseongs95.portal.domain.id.OwnerId;

/**
 * The metadata and the budget of the artifacts (2.x {@code artifacts.mjs}, manifest files {@code <id>.json}). The bytes are {@link ArtifactPayloadStore}'s: the
 * use case writes the bytes first and the metadata second (design 8.5, "bytes forced to disk, then metadata commit"), so a crash leaves at worst a byte tail that the
 * start-up check truncates. No method of this interface touches a file, so each runs in one short transaction.
 */
public interface ArtifactRegistry {

    /**
     * Reserves the budget and creates the row in {@code STAGING}. The three refusals are answered, not thrown, and are decided in this order: the size against the
     * per-file limit, the budget, the disk (defect D-0012). The registry reads the usable disk space through {@link ArtifactPayloadStore#usable()}, which is a read, not
     * a payload write. A disk that cannot be read is not a refusal: it throws {@code RequestRefusedException(ARTIFACT_STORAGE_IO_ERROR)}, and only when the file was not
     * already refused for its size or the budget.
     */
    ArtifactReservation reserve(ArtifactSpec spec, Instant at);

    /** Acknowledges the next chunk ({@code chunkNo} is the count so far); a chunk number out of order throws {@code ARTIFACT_CHUNK_CONFLICT}. */
    ArtifactRecord append(ArtifactId id, int chunkNo, ChunkDigest chunk);

    /** {@code STAGING} to {@code READY} once all bytes arrived and the whole-file digest is known. */
    ArtifactRecord markReady(ArtifactId id, Digest sha256);

    /**
     * {@code STAGING} to {@code READY} for a download, whose size was not known when the budget was reserved (the reservation holds the largest file a transfer may
     * carry, design 5.2, until {@link #narrowReservation} shrinks it): the declared size becomes the bytes received, so the charge is the real one. Only a {@code DOWNLOAD} that is
     * {@code STAGING} can be completed this way; any other artifact throws {@code ARTIFACT_NOT_WRITABLE}. Repeating it with the same digest answers the same record.
     */
    ArtifactRecord completeDownload(ArtifactId id, Digest sha256);

    /**
     * Shrinks the reservation of a download to {@code sizeBytes}, the size the target announced before the first byte is read (defect D-0012): the charge falls from the
     * largest file a transfer carries to the real size, so a download no longer holds more of the budget than its file needs. It is one conditional update, so it opens no
     * transaction and the loser of a race is told by the number of rows it changed. Only a {@code DOWNLOAD} that is {@code STAGING} and has received no byte can shrink,
     * and only to a size not above what it holds; any other artifact throws {@code ARTIFACT_NOT_WRITABLE}, a size above the reservation {@code ARTIFACT_SEQUENCE_OR_SIZE}
     * and an unknown id {@code ARTIFACT_NOT_FOUND}. The same size again is accepted: the charge stays, but the row is updated, so its {@code version} rises by one.
     */
    void narrowReservation(ArtifactId id, long sizeBytes);

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

    /** Marks the row {@code DELETING} before the bytes are deleted; the row is removed by {@link #release}. */
    ArtifactRecord beginDelete(ArtifactId id);

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
}
