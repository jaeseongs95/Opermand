package io.github.jaeseongs95.opermand.migrate.run;

import io.github.jaeseongs95.opermand.domain.id.Digest;
import io.github.jaeseongs95.opermand.domain.id.OperationId;
import io.github.jaeseongs95.opermand.domain.id.RequestKey;
import io.github.jaeseongs95.opermand.domain.operation.OperationState;

/**
 * The part of a planned operation that the checks against the 3.0 store need (the plan itself is made again when the row is written, so that a long history is never held in
 * memory): where it came from, its id and request key, the digest of its contract and the state it ends in.
 */
public record PlannedRow(String reference, OperationId id, RequestKey key, Digest contract, OperationState finalState) {
}
