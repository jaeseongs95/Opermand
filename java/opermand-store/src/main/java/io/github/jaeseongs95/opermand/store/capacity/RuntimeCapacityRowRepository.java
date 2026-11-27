package io.github.jaeseongs95.opermand.store.capacity;

import org.springframework.data.repository.CrudRepository;

/** Spring Data JDBC repository of {@code runtime_capacity}; optimistic locking is enforced by {@link RuntimeCapacityRow#version()} ({@code @Version}). */
interface RuntimeCapacityRowRepository extends CrudRepository<RuntimeCapacityRow, Integer> {
}
