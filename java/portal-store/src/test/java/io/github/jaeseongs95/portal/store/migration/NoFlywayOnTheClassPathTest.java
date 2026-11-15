package io.github.jaeseongs95.portal.store.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S-D0109-18: Flyway is neither a runtime nor a test dependency of portal-store. Flyway keeps a list of the databases it knows and, from version 10, a module for each of them
 * (flyway-database-postgresql, flyway-mysql ...), so it ties the product to those databases; the product migrates with its own runner and ANSI SQL
 * (docs/migration/migration-runner.md). The classes are looked for by name in the class loader of the tests, which is the test class path with everything the runtime needs.
 */
class NoFlywayOnTheClassPathTest {

    private static final List<String> FLYWAY_CLASSES = List.of("org.flywaydb.core.Flyway", "org.flywaydb.database.postgresql.PostgreSQLDatabaseType",
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration", "org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer");

    @Test
    @DisplayName("S-D0109-18: no Flyway class (the core, a database module, Boot's integration) can be loaded, and no entry of the class path is named flyway")
    void noFlywayClass() throws IOException {
        for (String name : FLYWAY_CLASSES) {
            assertThatThrownBy(() -> Class.forName(name)).as(name).isInstanceOf(ClassNotFoundException.class);
            assertThat(getClass().getClassLoader().getResources(name.replace('.', '/') + ".class").hasMoreElements()).as(name + " as a resource").isFalse();
        }
        assertThat(System.getProperty("java.class.path", "").toLowerCase(Locale.ROOT)).as("class path entries").doesNotContain("flyway");
    }
}
