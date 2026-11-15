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
