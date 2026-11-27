// The 2.x SQLite -> 3.0 migration command, a jar of its own (docs/migration/portal-migrate.md). The only module that reads SQLite: the driver stays out of the server and the client,
// and nothing else depends on this module (the server does not know 2.x data formats; rule A-DIR lets migrate use only opermand-store and opermand-domain).
plugins {
    id("org.springframework.boot")
}

dependencies {
    api(project(":opermand-store"))
    implementation(project(":opermand-domain"))
    implementation("org.springframework.boot:spring-boot-starter")
    // The store's JsonMapper bean (OperationMapper) comes from Boot's Jackson auto-configuration; the data-JDBC starter does not bring that module, the web starters do.
    // Rule A-JSON forbids building a mapper by hand, so the starter is the way to have the one Boot mapper. The version is the Boot BOM's.
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    // The version is the Boot BOM's (sqlite-jdbc.version). This is the one module that has the driver (approved for opermand-migrate only).
    implementation("org.xerial:sqlite-jdbc")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "opermand-migrate.jar"
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}
