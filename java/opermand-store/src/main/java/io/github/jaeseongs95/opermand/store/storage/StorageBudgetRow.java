package io.github.jaeseongs95.opermand.store.storage;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Domain quota accounting row in {@code storage_budget} (SSH_PORTAL-D-0058). Each scope ({@code ARTIFACT} or {@code OUTPUT}) tracks live charged bytes and item count
 * updated atomically as the first write of every reserving, resizing, consuming, or deleting transaction.
 */
@Table("storage_budget")
public record StorageBudgetRow(@Id String scope, long chargedBytes, long itemCount) {
}
