// The 2.x SQLite -> 3.0 migration command, a jar of its own (docs/migration/portal-migrate.md). The only module that reads SQLite: the driver stays out of the server and the client,
// and nothing else depends on this module (the server does not know 2.x data formats; rule A-DIR lets migrate use only portal-store and portal-domain).
plugins {
    id("org.springframework.boot")
}

dependencies {
    api(project(":portal-store"))
    implementation(project(":portal-domain"))
    implementation("org.springframework.boot:spring-boot-starter")
    // The store's JsonMapper bean (OperationMapper) comes from Boot's Jackson auto-configuration; the data-JDBC starter does not bring that module, the web starters do.
    // Rule A-JSON forbids building a mapper by hand, so the starter is the way to have the one Boot mapper. The version is the Boot BOM's.
    implementation("org.springframework.boot:spring-boot-starter-jackson")
    // The version is the Boot BOM's (sqlite-jdbc.version). This is the one module that has the driver (approved for portal-migrate only).
    implementation("org.xerial:sqlite-jdbc")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "portal-migrate.jar"
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

// java-openssh.yml runs :portal-migrate:test on Windows (the job runs "check" for every module) but not on Linux, where only the arch-checks build this module's classes. Until the
// workflow lists this module's tests itself, a CI run (GitHub Actions sets CI) runs them after the classes are built, so that the tests of this module are run on both systems.
// A developer machine is not affected: it runs the tests it asks for.
if (providers.environmentVariable("CI").isPresent) {
    tasks.named("classes") {
        finalizedBy(tasks.named("test"))
    }
}
