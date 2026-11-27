package io.github.jaeseongs95.opermand.store.artifact;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import io.github.jaeseongs95.opermand.app.artifact.ArtifactRetention;
import io.github.jaeseongs95.opermand.app.artifact.ArtifactState;

/** Spring Data JDBC repository of the artifact rows. The statements are ANSI (no LIMIT, no vendor function); the budget is an aggregate. */
interface ArtifactRowRepository extends CrudRepository<ArtifactRow, String> {

    /** The optimistic-lock step: moves the version from {@code expected} to {@code next}; returns 1 for the caller that won and 0 for a loser. */
    @Modifying
    @Query("UPDATE artifact SET version = :next WHERE id = :id AND version = :expected")
    int bumpVersion(@Param("id") String id, @Param("expected") long expected, @Param("next") long next);

    /**
     * Sets the reserved size of a download that has received no byte, in one statement (the version moves with it, so a concurrent step of the optimistic lock loses). It
     * changes one row when the artifact is such a download, and none otherwise.
     */
    @Modifying
    @Query("UPDATE artifact SET size_bytes = :size, version = version + 1 WHERE id = :id AND direction = 'DOWNLOAD' AND state = 'STAGING' AND received_bytes = 0")
    int resizeDownload(@Param("id") String id, @Param("size") long size);

    /**
     * Moves a row that is {@code STAGING} or {@code READY} and that nobody keeps to {@code DELETING} in one statement (the version moves with it, so a concurrent step of the
     * optimistic lock loses). It changes one row when the row exists in one of those states and is {@code RELEASABLE}, and none otherwise (the row is gone, already
     * {@code DELETING}, in use, consumed, or pinned by a submitted operation). The retention is part of the condition and not of an earlier read: a reap that listed the row as
     * expired and was overtaken by a submit that pinned it must not delete the bytes a submitted operation needs (defect D-0095).
     */
    @Modifying
    @Query("UPDATE artifact SET state = 'DELETING', version = version + 1 WHERE id = :id AND state IN ('STAGING', 'READY') AND retention = 'RELEASABLE'")
    int markDeleting(@Param("id") String id);

    /**
     * Acquires the row lock on a {@code READY} artifact by advancing its version (used to serialize concurrent staged-file actions without JVM locks).
     */
    @Modifying
    @Query("UPDATE artifact SET version = version + 1 WHERE id = :id AND state = 'READY'")
    int lockReadyForAction(@Param("id") String id);

    @Modifying
    @Query("DELETE FROM artifact WHERE id = :id")
    int removeById(@Param("id") String id);

    /**
     * What the rows charge now: a consumed row and an empty file charge one byte each (2.x {@code artifacts.mjs:18}). A row in {@code DELETING} or {@code CONSUMING} charges nothing: its bytes
     * are being removed and no transfer uses them, so an artifact that cannot be deleted must not eat the application budget for good (defect D-0012, S-D0012-11, S-D0012-18); the
     * disk check sees the space it still uses.
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN state IN ('DELETING', 'CONSUMING') THEN 0 WHEN state = 'CONSUMED' THEN 1 WHEN size_bytes < 1 THEN 1 ELSE size_bytes END), 0) FROM artifact")
    long chargedBytes();

    Optional<ArtifactRow> findByPublicUuid(String publicUuid);

    Optional<ArtifactRow> findByOwnerIdAndOperationKey(String ownerId, String operationKey);

    /** The rows whose expiry has come and that the owner did not pin, oldest first (the order is by id inside one instant). */
    List<ArtifactRow> findByRetentionAndStateInAndExpiresAtLessThanEqualOrderByExpiresAtAscIdAsc(ArtifactRetention retention, Collection<ArtifactState> states,
                                                                                                  Instant now);

    /** The rows in one state, in the order of their ids ({@code DELETING}: removal began and did not finish; {@code CONSUMING}: a use began and did not finish). */
    List<ArtifactRow> findByStateOrderByIdAsc(ArtifactState state);


    /** Every row except the consumed ones (their bytes are gone). */
    List<ArtifactRow> findByStateNot(ArtifactState state);
}
