package io.github.jaeseongs95.opermand.store.journal;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import io.github.jaeseongs95.opermand.domain.operation.DecisionKind;
import io.github.jaeseongs95.opermand.domain.operation.DecisionReason;
import io.github.jaeseongs95.opermand.domain.operation.ReviewReason;

/**
 * Table {@code operation_decision}: the rule-based approval decision recorded at intake ({@code DecisionRecord.Decided}, defect D-0119, D-0122).
 */
@Table("operation_decision")
public record OperationDecisionRow(@Id String operationId, DecisionKind decision, DecisionReason reasonCode, ReviewReason reviewReason, long policyRevision) {
}
