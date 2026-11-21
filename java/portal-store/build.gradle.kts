// Storage adapter: Spring Data JDBC entities and repositories, the ANSI migration scripts and the runner that applies them. The DataSource, pool and transaction manager come
// from Boot's auto-configuration and spring.datasource.* (Principles 4.1); the embedded H2 is the default database and a user's JDBC driver replaces it by configuration.
//
// Why there is no Flyway: the product runs on H2 by default and on the user's own JDBC driver, and the differences of the databases are absorbed by ANSI SQL and SqlBuilder.
// Flyway keeps a list of the databases it knows and, from version 10, has the support of each database in a module of its own (flyway-database-postgresql, flyway-mysql ...),
// so the product would have to carry a module for every database a user may choose, and a database without one could not be migrated: that ties the product to the vendors
// Flyway lists. The scripts (db/expand, db/contract) are applied by the runner of the package ...store.migration, the same runner and history table as the other two products
// of the owner (docs/migration/migration-runner.md). No JDBC driver other than H2 is a dependency of the product here and none is a test dependency: which drivers ship is the
// user's part (Principles 7.3: the support of other DBMSs is not tested).
dependencies {
    api(project(":portal-app"))
    api("org.springframework.boot:spring-boot-starter-data-jdbc")
    runtimeOnly("com.h2database:h2")

    // @DataJdbcTest lives in the test module spring-boot-data-jdbc-test, package org.springframework.boot.data.jdbc.test.autoconfigure
    // (https://docs.spring.io/spring-boot/appendix/test-auto-configuration/slices.html, https://docs.spring.io/spring-boot/reference/testing/test-modules.html);
    // the starter brings spring-boot-test (ApplicationContextRunner) and the other test libraries (https://docs.spring.io/spring-boot/reference/testing/test-scope-dependencies.html).
    testImplementation("org.springframework.boot:spring-boot-data-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// The cases tagged "zone" (D-0161: a moment is stored as UTC, whatever the zone of the JVM is) need a JVM whose default zone repeats an hour when daylight saving time ends. A test
// cannot give itself that zone: the default zone is read when the JVM starts, and H2 keeps what it read. The zoneTest task starts a second test JVM in such a zone (user.timezone is
// the JVM's own property, the same on every operating system) and runs those cases only; the test task leaves them out and is finalized by zoneTest, so that
// "./gradlew :portal-store:test" and "check" both run every case once.
val zoneTest by tasks.registering(Test::class) {
    description = "Runs the cases tagged zone in a JVM whose default time zone has daylight saving time."
    group = "verification"
    val tests = extensions.getByType<JavaPluginExtension>().sourceSets.named("test")
    testClassesDirs = tests.get().output.classesDirs
    classpath = tests.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("zone")
    }
    systemProperty("user.timezone", "America/Los_Angeles")
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("zone")
    }
    finalizedBy(zoneTest)
}
