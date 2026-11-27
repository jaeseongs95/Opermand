package io.github.jaeseongs95.opermand.app.capacity;

import java.util.List;
import java.util.Objects;

import io.github.jaeseongs95.opermand.domain.capacity.Capacity;
import io.github.jaeseongs95.opermand.domain.id.TargetId;
import io.github.jaeseongs95.opermand.domain.target.CapacityOverride;

/**
 * The runtime capacity override: a monotonic optimistic-locking {@code version} (starting at 1), the installation values and the per-target overrides (SSH_PORTAL-D-0124).
 * An override of a target that is no longer configured is kept, so the list is not checked against the targets here.
 */
public record CapacityState(long version, Capacity queueCapacity, Capacity maxConcurrency, List<TargetCapacity> targets) {

    public CapacityState {
        Objects.requireNonNull(queueCapacity, "queueCapacity");
        Objects.requireNonNull(maxConcurrency, "maxConcurrency");
        targets = List.copyOf(targets);
        if (version < 1) {
            throw new IllegalArgumentException("A capacity version is 1 or greater");
        }
    }

    /** The override of one target; each value is inherited or explicit. */
    public record TargetCapacity(TargetId targetId, CapacityOverride queueCapacity, CapacityOverride maxConcurrency) {

        public TargetCapacity {
            Objects.requireNonNull(targetId, "targetId");
            Objects.requireNonNull(queueCapacity, "queueCapacity");
            Objects.requireNonNull(maxConcurrency, "maxConcurrency");
        }
    }
}
