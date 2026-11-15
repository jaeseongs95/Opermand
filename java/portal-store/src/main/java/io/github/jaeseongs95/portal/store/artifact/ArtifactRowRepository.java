package io.github.jaeseongs95.portal.store.artifact;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import io.github.jaeseongs95.portal.app.artifact.ArtifactRetention;
import io.github.jaeseongs95.portal.app.artifact.ArtifactState;

/** Spring Data JDBC repository of the artifact rows. The statements are ANSI (no LIMIT, no vendor function); the budget is an aggregate. */
interface ArtifactRowRepository extends CrudRepository<ArtifactRow, String> {

    /** The optimistic-lock step: moves the version from {@code expected} to {@code next}; returns 1 for the caller that won and 0 for a loser. */
    @Modifying
    @Query("UPDATE artifact SET version = :next WHERE id = :id AND version = :expected")
    int bumpVersion(@Param("id") String id, @Param("expected") long expected, @Param("next") long next);

    /**
     * Shrinks the reserved size of a download that has received no byte, in one statement (the version moves with it, so a concurrent step of the optimistic lock loses). It
     * changes one row when the artifact is such a download and the size is not above what it holds, and none otherwise.
     */
    @Modifying
    @Query("UPDATE artifact SET size_bytes = :size, version = version + 1 WHERE id = :id AND direction = 'DOWNLOAD' AND state = 'STAGING' AND received_bytes = 0 AND size_bytes >= :size")
    int narrowDownload(@Param("id") String id, @Param("size") long size);

    /**
     * Moves a row that is {@code STAGING} or {@code READY} to {@code DELETING} in one statement (the version moves with it, so a concurrent step of the optimistic lock loses).
     * It changes one row when the row exists in one of those states, and none otherwise (the row is gone, already {@code DELETING}, in use or consumed).
     */
    @Modifying
    @Query("UPDATE artifact SET state = 'DELETING', version = version + 1 WHERE id = :id AND state IN ('STAGING', 'READY')")
    int markDeleting(@Param("id") String id);

    /** What the rows charge now: a consumed row and an empty file charge one byte each (2.x {@code artifacts.mjs:18}). */
    @Query("SELECT COALESCE(SUM(CASE WHEN state = 'CONSUMED' THEN 1 WHEN size_bytes < 1 THEN 1 ELSE size_bytes END), 0) FROM artifact")
    long chargedBytes();

    Optional<ArtifactRow> findByPublicUuid(String publicUuid);

    Optional<ArtifactRow> findByOwnerIdAndOperationKey(String ownerId, String operationKey);

    /** The rows whose expiry has come and that the owner did not pin, oldest first (the order is by id inside one instant). */
    List<ArtifactRow> findByRetentionAndStateInAndExpiresAtLessThanEqualOrderByExpiresAtAscIdAsc(ArtifactRetention retention, Collection<ArtifactState> states,
                                                                                                  Instant now);

    /** The rows whose removal began and did not finish, in the order of their ids. */
    List<ArtifactRow> findByStateOrderByIdAsc(ArtifactState state);

    /** Every row except the consumed ones (their bytes are gone). */
    List<ArtifactRow> findByStateNot(ArtifactState state);
}
