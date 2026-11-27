package io.github.jaeseongs95.opermand.app.history;

import java.util.Objects;

import io.github.jaeseongs95.opermand.domain.operation.Operation;

/** One listed operation and how its target stands now. */
public record HistoryEntry(Operation operation, TargetStatus targetStatus) {

    public HistoryEntry {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(targetStatus, "targetStatus");
    }
}
