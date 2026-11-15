package io.github.jaeseongs95.portal.store.artifact;

import java.util.List;
import java.util.Objects;

import io.github.jaeseongs95.portal.domain.id.ArtifactId;

/**
 * What {@link ArtifactStartupCheck#reconcile()} did: the payload files without metadata that went to quarantine, the files cut back to the acknowledged length,
 * the rows in {@code DELETING} that were finished, and the rows in {@code CONSUMING} that were finished with the reason they recorded.
 */
public record ArtifactReconciliation(List<ArtifactId> quarantined, List<ArtifactId> truncated, List<ArtifactId> deleted, List<ArtifactId> finishedConsumptions) {

    public ArtifactReconciliation {
        quarantined = List.copyOf(Objects.requireNonNull(quarantined, "quarantined"));
        truncated = List.copyOf(Objects.requireNonNull(truncated, "truncated"));
        deleted = List.copyOf(Objects.requireNonNull(deleted, "deleted"));
        finishedConsumptions = List.copyOf(Objects.requireNonNull(finishedConsumptions, "finishedConsumptions"));
    }
}
