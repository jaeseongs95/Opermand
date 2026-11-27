// Assembly: the executable jar. Two web servers (MCP 39124, admin 39125) run as two child contexts of one parent context; the arrangement is chosen by
// the spike test PortsSpikeTest (docs/migration/foundation-cases.md, cases Q-*).
plugins {
    id("org.springframework.boot")
}

val springAiVersion = property("springAiVersion")

dependencies {
    implementation(project(":opermand-domain"))
    implementation(project(":opermand-app"))
    implementation(project(":opermand-store"))
    implementation(project(":opermand-ssh"))
    implementation(project(":opermand-desktop"))
    implementation(project(":opermand-web"))
    implementation(platform("org.springframework.ai:spring-ai-bom:$springAiVersion"))
    // No mock is used (Principles 7.1); see opermand-web/build.gradle.kts for the Mockito self-attach warning this avoids.
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.mockito")
    }
    // The use case tests (package server.usecase: dispatcher, intake, operations, read commands, recovery) run the real use cases of opermand-app on the real storage adapters of
    // opermand-store over the embedded H2 database, not on a fake store; they live here because this is the module that sees both (rule A-DIR keeps opermand-app from depending
    // on opermand-store, tests included). @DataJdbcTest is in the test module org.springframework.boot.data.jdbc.test.autoconfigure (as in opermand-store); the version is the BOM's.
    testImplementation("org.springframework.boot:spring-boot-data-jdbc-test")
    // The assembly tests (package server.assembly) run the whole product against the embedded SSH server of opermand-ssh (EmbeddedSshServer, ScriptedCommand), the test
    // fixtures of that project, used as they are and not copied (a copy of a 400 line server would drift). The fixtures bring MINA as their API. No class of this module's
    // tests imports MINA itself (rule A-MINA); the EdDSA provider comes with opermand-ssh at run time.
    testImplementation(testFixtures(project(":opermand-ssh")))
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "opermand-server.jar"
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}
