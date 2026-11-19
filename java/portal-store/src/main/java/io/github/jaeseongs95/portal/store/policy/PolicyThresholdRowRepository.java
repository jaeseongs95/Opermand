package io.github.jaeseongs95.portal.store.policy;

import java.util.List;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

/** Spring Data JDBC repository of the thresholds (composite key: only the statements below are used). */
interface PolicyThresholdRowRepository extends CrudRepository<PolicyThresholdRow, String> {

    /** Every threshold, ordered by the key. */
    @Query("SELECT * FROM command_policy_threshold ORDER BY target_id, provider")
    List<PolicyThresholdRow> findAllOrdered();

    /** The thresholds of one target, ordered by provider. */
    @Query("SELECT * FROM command_policy_threshold WHERE target_id = :targetId ORDER BY provider")
    List<PolicyThresholdRow> findByTargetIdOrdered(@Param("targetId") String targetId);

    /** Deletes every threshold. */
    @Modifying
    @Query("DELETE FROM command_policy_threshold")
    int deleteAllThresholds();
}
