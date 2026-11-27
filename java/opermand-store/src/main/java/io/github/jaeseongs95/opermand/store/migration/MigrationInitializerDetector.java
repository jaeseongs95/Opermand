package io.github.jaeseongs95.opermand.store.migration;

import java.util.Set;

import org.springframework.boot.sql.init.dependency.AbstractBeansOfTypeDatabaseInitializerDetector;

/**
 * Tells Spring Boot that {@link ExpandOnStart} initialises the database, so that the beans that use it (JdbcTemplate, JdbcClient and what Spring Data JDBC builds on them) are
 * created after it. This is Boot's documented extension for a database initializer of one's own: a {@code DatabaseInitializerDetector} registered in
 * {@code META-INF/spring.factories} (https://docs.spring.io/spring-boot/how-to/data-initialization.html, "Depend Upon an Initialized Database"); Flyway's own integration is
 * registered the same way.
 */
public final class MigrationInitializerDetector extends AbstractBeansOfTypeDatabaseInitializerDetector {

    @Override
    protected Set<Class<?>> getDatabaseInitializerBeanTypes() {
        return Set.of(ExpandOnStart.class);
    }
}
