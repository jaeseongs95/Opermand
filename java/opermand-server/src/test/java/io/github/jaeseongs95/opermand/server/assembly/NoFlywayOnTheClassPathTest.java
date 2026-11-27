package io.github.jaeseongs95.opermand.server.assembly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S-D0109-18 on the assembly: Flyway is not on the class path of the product with everything it brings in (runtime and tests), and the product has no Flyway property file
 * (the old {@code spring.flyway.locations} setting of {@code application.properties} is gone with it).
 */
class NoFlywayOnTheClassPathTest {

    private static final List<String> FLYWAY_CLASSES = List.of("org.flywaydb.core.Flyway", "org.flywaydb.database.postgresql.PostgreSQLDatabaseType",
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration", "org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer");

    @Test
    @DisplayName("S-D0109-18: no Flyway class can be loaded on the class path of the assembly, and no entry of it is named flyway")
    void noFlywayClass() throws IOException {
        for (String name : FLYWAY_CLASSES) {
            assertThatThrownBy(() -> Class.forName(name)).as(name).isInstanceOf(ClassNotFoundException.class);
            assertThat(getClass().getClassLoader().getResources(name.replace('.', '/') + ".class").hasMoreElements()).as(name + " as a resource").isFalse();
        }
        assertThat(System.getProperty("java.class.path", "").toLowerCase(Locale.ROOT)).as("class path entries").doesNotContain("flyway");
        for (java.net.URL url : java.util.Collections.list(getClass().getClassLoader().getResources("application.properties"))) {
            try (var in = url.openStream()) {
                assertThat(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).toLowerCase(Locale.ROOT)).as(url.toString()).doesNotContain("flyway");
            }
        }
    }
}
