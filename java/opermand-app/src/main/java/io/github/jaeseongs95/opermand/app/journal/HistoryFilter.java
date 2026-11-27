package io.github.jaeseongs95.opermand.app.journal;

import java.util.Objects;
import java.util.Set;

import io.github.jaeseongs95.opermand.domain.operation.OperationState;

/** Which operations a history page lists: the states and the target. An empty state set means every state. */
public record HistoryFilter(Set<OperationState> states, TargetFilter target) {

    public HistoryFilter {
        states = Set.copyOf(states);
        Objects.requireNonNull(target, "target");
    }
}
