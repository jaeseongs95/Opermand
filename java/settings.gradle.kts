pluginManagement {
    // The one place that states the Spring Boot version (gradle.properties: springBootVersion); build.gradle.kts reads the same property for the BOM.
    // gradle.properties values are extra properties of the settings object ('val x: T by settings' is deprecated since Gradle 9.6, removal in 10).
    val springBootVersion = settings.extra["springBootVersion"] as String
    plugins {
        id("org.springframework.boot") version springBootVersion
    }
}

rootProject.name = "opermand"

// Module order is the dependency order (docs/migration/foundation-cases.md, rule A-DIR): domain <- app <- (store, ssh, desktop, web) <- server;
// client needs only domain; migrate needs only store and domain.
include(
    "opermand-domain", "opermand-app", "opermand-store", "opermand-ssh", "opermand-desktop", "opermand-web",
    "opermand-server", "opermand-client", "opermand-migrate", "opermand-arch-checks",
)
