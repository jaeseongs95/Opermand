package io.github.jaeseongs95.portal.migrate.plan;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import io.github.jaeseongs95.portal.domain.id.Digest;
import io.github.jaeseongs95.portal.domain.id.OperationId;
import io.github.jaeseongs95.portal.domain.id.RequestKey;
import io.github.jaeseongs95.portal.domain.operation.Admission;
import io.github.jaeseongs95.portal.domain.operation.NewOperation;
import io.github.jaeseongs95.portal.domain.operation.OperationState;

/**
 * Everything needed to store one 2.x operation in 3.0, decided and checked before anything is written: the request that creates it, how the intake judged it, and the
 * transitions after that, which have already been run once in memory against the domain (so the plan cannot fail in the store for a domain reason). The id and the request
 * key are the 2.x ones; they are never changed.
 *
 * @param spec the request as 3.0 knows it
 * @param admission how the first state was decided
 * @param id the 2.x id
 * @param createdAt the 2.x creation time
 * @param steps the transitions after intake, in order
 * @param finalState the state the operation ends in (unknown for an operation that was in flight)
 * @param reinterpretation how the 3.0 state differs from the 2.x state and why, or null when they agree
 */
public record ImportPlan(NewOperation spec, Admission admission, OperationId id, Instant createdAt, List<Step> steps, OperationState finalState, String reinterpretation) {

    public ImportPlan {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        steps = List.copyOf(steps);
        Objects.requireNonNull(finalState, "finalState");
    }

    public RequestKey requestKey() {
        return spec.requestKey();
    }

    public Digest contractDigest() {
        return spec.contractDigest();
    }
}
