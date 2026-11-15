package io.github.jaeseongs95.portal.store.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S-D0109-22 (D-0103): the product does not test other DBMSs (Principles 7.3: "other DBMS support is not tested; the driver is the user's part"). The tests run on the
 * embedded H2 database only: there is no PostgreSQL driver on the test class path, no external-DBMS test support, and no test launcher listener or environment
 * post-processor that points the tests at another database.
 */
class ExternalDbmsRemovedTest {

    @Test
    @DisplayName("S-D0109-22: no external-DBMS test class, no PostgreSQL driver, no session listener or environment post-processor of the external run, no portal.test.dbms setting")
    void theExternalRunIsGone() throws IOException {
        for (String name : List.of("io.github.jaeseongs95.portal.store.external.ExternalDbms", "io.github.jaeseongs95.portal.store.external.ExternalDbmsEnvironment",
            "io.github.jaeseongs95.portal.store.external.ExternalDbmsSchemaSession", "io.github.jaeseongs95.portal.store.external.ExternalDbmsTest", "org.postgresql.Driver")) {
            assertThatThrownBy(() -> Class.forName(name)).as(name).isInstanceOf(ClassNotFoundException.class);
        }
        for (String resource : List.of("META-INF/services/org.junit.platform.launcher.LauncherSessionListener", "META-INF/spring.factories")) {
            for (URL url : Collections.list(getClass().getClassLoader().getResources(resource))) {
                try (var in = url.openStream()) {
                    assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).as(url.toString()).doesNotContain("ExternalDbms");
                }
            }
        }
        for (String property : List.of("portal.test.dbms.url", "portal.test.dbms.user", "portal.test.dbms.password-file", "portal.test.dbms.schema")) {
            assertThat(System.getProperty(property)).as(property).isNull();
        }
    }
}
