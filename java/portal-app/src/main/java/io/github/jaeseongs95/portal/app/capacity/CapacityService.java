package io.github.jaeseongs95.portal.app.capacity;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import io.github.jaeseongs95.portal.app.config.AvailableCpus;
import io.github.jaeseongs95.portal.app.maintenance.MaintenanceStore;
import io.github.jaeseongs95.portal.domain.capacity.CapacityDefaults;
import io.github.jaeseongs95.portal.domain.error.CapacityErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.domain.id.TargetId;
import io.github.jaeseongs95.portal.domain.target.Target;

/**
 * The administrator's override of the queue and concurrency limits (cases RC-B-410 and its neighbours). A change is one optimistic-locking update on the single monotonic
 * {@code version} the administrator saw (SSH_PORTAL-D-0124). It is refused while a maintenance reservation holds the installation ({@code MAINTENANCE_ACTIVE}), for a stale
 * version ({@code CAPACITY_REVISION_CONFLICT}), or for a duplicate/unknown target, and no refusal changes the stored value.
 */
@Service
public class CapacityService {

    private static final long INITIAL_VERSION = 1L;

    private final RuntimeCapacityStore store;
    private final MaintenanceStore maintenance;
    private final ConfiguredTargets targets;
    private final AvailableCpus cpus;

    public CapacityService(RuntimeCapacityStore store, MaintenanceStore maintenance, ConfiguredTargets targets, AvailableCpus cpus) {
        this.store = store;
        this.maintenance = maintenance;
        this.targets = targets;
        this.cpus = cpus;
    }

    /** The stored limits; the first call of a new installation stores the defaults (two first writers: one wins, the other reads). */
    public CapacityState current() {
        return store.read().orElseGet(() -> store.initialize(defaults()));
    }

    /**
     * Replaces the limits with {@code proposed}, under the version the administrator saw. The version of {@code proposed} is ignored: the store advances the version by 1. A
     * refusal changes nothing.
     */
    public CapacityState update(long expectedVersion, CapacityState proposed) {
        Objects.requireNonNull(proposed, "proposed");
        if (maintenance.occupied()) {
            throw new RequestRefusedException(RequestErrorCode.MAINTENANCE_ACTIVE);
        }
        CapacityState stored = current();
        if (stored.version() != expectedVersion) {
            throw new RequestRefusedException(CapacityErrorCode.CAPACITY_REVISION_CONFLICT);
        }
        requireKnownTargets(stored, proposed.targets());
        return store.compareAndSet(expectedVersion, proposed);
    }

    private void requireKnownTargets(CapacityState stored, List<CapacityState.TargetCapacity> proposed) {
        Set<TargetId> seen = new HashSet<>();
        Set<TargetId> known = new HashSet<>();
        stored.targets().forEach(target -> known.add(target.targetId()));
        targets.entries().forEach(target -> known.add(target.id()));
        for (CapacityState.TargetCapacity override : proposed) {
            if (!seen.add(override.targetId())) {
                throw new RequestRefusedException(CapacityErrorCode.INVALID_CAPACITY_TARGETS);
            }
            if (!known.contains(override.targetId())) {
                throw new RequestRefusedException(CapacityErrorCode.UNKNOWN_CAPACITY_TARGET);
            }
        }
    }

    private CapacityState defaults() {
        CapacityDefaults installation = new CapacityDefaults(cpus.count());
        List<CapacityState.TargetCapacity> own = targets.entries().stream().map(this::overrideOf).toList();
        return new CapacityState(INITIAL_VERSION, installation.queueCapacity(), installation.maxConcurrency(), own);
    }

    private CapacityState.TargetCapacity overrideOf(Target target) {
        return new CapacityState.TargetCapacity(target.id(), target.queueCapacity(), target.maxConcurrency());
    }
}
