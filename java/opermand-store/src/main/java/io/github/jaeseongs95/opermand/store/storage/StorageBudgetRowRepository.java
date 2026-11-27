package io.github.jaeseongs95.opermand.store.storage;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JDBC repository for {@code storage_budget} (SSH_PORTAL-D-0058). All quota-charging mutations take this row lock as the first write of the transaction
 * (Lock Order #1) via a single atomic conditional {@code UPDATE}, eliminating read-then-insert oversubscription and AB-BA deadlocks without dummy lock tables.
 */
public interface StorageBudgetRowRepository extends CrudRepository<StorageBudgetRow, String> {

    String ARTIFACT_SCOPE = "ARTIFACT";
    String OUTPUT_SCOPE = "OUTPUT";

    /**
     * Atomically reserves {@code deltaBytes} and {@code deltaCount} within {@code maxBytes} when the scope is bounded by bytes only ({@code ARTIFACT}).
     * Overflow-safe: callers ensure {@code deltaBytes <= maxBytes} before binding {@code maxAllowed = maxBytes - deltaBytes}.
     */
    @Modifying
    @Query("UPDATE storage_budget SET charged_bytes = charged_bytes + :deltaBytes, item_count = item_count + :deltaCount "
        + "WHERE scope = :scope AND charged_bytes <= :maxAllowed")
    int reserveBytes(@Param("scope") String scope, @Param("deltaBytes") long deltaBytes, @Param("deltaCount") long deltaCount, @Param("maxAllowed") long maxAllowed);

    /**
     * Atomically reserves {@code deltaBytes} and {@code 1} item within both {@code maxCount} and {@code maxBytes} ({@code OUTPUT}).
     * Callers ensure {@code deltaBytes <= maxBytes} and bind {@code maxAllowed = maxBytes - deltaBytes}.
     */
    @Modifying
    @Query("UPDATE storage_budget SET charged_bytes = charged_bytes + :deltaBytes, item_count = item_count + 1 "
        + "WHERE scope = :scope AND item_count < :maxCount AND charged_bytes <= :maxAllowed")
    int reserveBytesAndCount(@Param("scope") String scope, @Param("deltaBytes") long deltaBytes, @Param("maxAllowed") long maxAllowed, @Param("maxCount") long maxCount);

    /**
     * Unconditionally adjusts charged bytes and item count for settlement, shrink, tombstone (+1B), release, or compensating rollback transitions.
     * Non-negative {@code CHECK} constraints on {@code storage_budget} reject underflow.
     */
    @Modifying
    @Query("UPDATE storage_budget SET charged_bytes = charged_bytes + :deltaBytes, item_count = item_count + :deltaCount WHERE scope = :scope")
    int adjust(@Param("scope") String scope, @Param("deltaBytes") long deltaBytes, @Param("deltaCount") long deltaCount);

    /** Startup reconciliation: synchronizes the {@code ARTIFACT} budget row with the live rows of {@code artifact}. */
    @Modifying
    @Query("UPDATE storage_budget SET "
        + "charged_bytes = (SELECT COALESCE(SUM(CASE WHEN state IN ('DELETING', 'CONSUMING') THEN 0 WHEN state = 'CONSUMED' THEN 1 WHEN size_bytes < 1 THEN 1 ELSE size_bytes END), 0) FROM artifact), "
        + "item_count = (SELECT COUNT(*) FROM artifact) "
        + "WHERE scope = 'ARTIFACT'")
    int reconcileArtifact();

    /** Startup reconciliation: synchronizes the {@code OUTPUT} budget row with the live rows of {@code command_output}. */
    @Modifying
    @Query("UPDATE storage_budget SET "
        + "charged_bytes = (SELECT COALESCE(SUM(charge_bytes), 0) FROM command_output), "
        + "item_count = (SELECT COUNT(*) FROM command_output) "
        + "WHERE scope = 'OUTPUT'")
    int reconcileOutput();
}
