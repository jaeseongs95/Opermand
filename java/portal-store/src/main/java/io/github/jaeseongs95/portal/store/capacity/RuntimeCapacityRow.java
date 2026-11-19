package io.github.jaeseongs95.portal.store.capacity;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Table {@code runtime_capacity}: the single row (id 1) of the installation. {@code @Version} provides Spring Data JDBC built-in optimistic locking (SSH_PORTAL-D-0124).
 */
@Table("runtime_capacity")
public record RuntimeCapacityRow(@Id int id, int queueCapacity, int maxConcurrency, @Version long version) {
}
