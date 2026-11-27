// Shared build of the 3.0.0 Java product (docs/migration/foundation-cases.md). Module-specific dependencies live in each module's build.gradle.kts;
// versions come from the Spring Boot BOM and the Spring AI BOM, and a build file names a version only for a library that neither BOM manages.

plugins {
    jacoco
    id("org.sonarqube") version "7.5.0.8588"
}

// A Gradle service a build script takes by injection, for the work a task does when it runs (FileSystemOperations: the isolated test folders are emptied before a test run, D-0111).
interface InjectedFileSystem {
    @get:javax.inject.Inject
    val fileSystem: FileSystemOperations
}

val springBootVersion = property("springBootVersion")

allprojects {
    group = "io.github.jaeseongs95.opermand"
    repositories {
        mavenCentral()
    }
}

sonar {
    properties {
        property("sonar.projectKey", "opermand")
        property("sonar.projectName", "Opermand")
        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.coverage.jacoco.xmlReportPaths", layout.buildDirectory.file("reports/jacoco/root/jacocoRootReport.xml").get().asFile.absolutePath)
    }
}

// The sizes of the test JVMs are Gradle's own unless a run states them: this build fixes no heap, no processor count and no fork count. Development Principles 7.4: "the Gradle
// parallelism (--max-workers), the number of test forks, the heap and the CPUs per test JVM are values the project sets in the execution command of its own work (or in a
// setting dedicated to that work) only; they are not set globally in a setting or folder that several sessions or projects share." A computer states them for itself, on
// the command line or in the gradle.properties of the Gradle user home it dedicates to this project: -Pportal.test.cpus=<n> (-XX:ActiveProcessorCount), -Pportal.test.heap=<size>
// (maxHeapSize, for example 512m or 1g) and -Pportal.test.forks=<n> (maxParallelForks). Without them the JVM and Gradle defaults apply. A value that is not a positive
// whole number (or, for the heap, a whole number with an optional k, m or g) stops the build while it is configured (cases S-D0111-01 to -05). The modules that must not
// run their tests in parallel (the tasks realOpenSshTest of opermand-ssh and externalDbmsTest of opermand-store: a shared account on one server, a shared schema) say so in their own
// build file. A count (cpus, forks) that the pattern accepts but that is larger than an int is refused here too, with the property name and the value; the smallest heap is
// the JVM's to refuse, with its own message.
fun statedTestSize(name: String, valid: Regex, expected: String, isCount: Boolean = false): String? = providers.gradleProperty("portal.test.$name").orNull?.also {
    if (!valid.matches(it) || (isCount && it.toIntOrNull() == null)) {
        throw GradleException("-Pportal.test.$name must be $expected, but is '$it'")
    }
}

val testCpus: String? = statedTestSize("cpus", Regex("[1-9][0-9]*"), "a positive whole number that fits an int (the processors a test JVM sees)", isCount = true)
val testHeap: String? = statedTestSize("heap", Regex("[1-9][0-9]*[kKmMgG]?"), "a positive whole number with an optional k, m or g, such as 512m (the heap of a test JVM)")
val testForks: String? = statedTestSize("forks", Regex("[1-9][0-9]*"), "a positive whole number that fits an int (the test JVMs Gradle runs at the same time)", isCount = true)

// The file-system abilities a run requires of its machine (case S-D0117-09): -Pportal.test.require=posix,links says that the run must be able to make permission bits (posix) and
// symbolic links (links), and -Pportal.test.require=acl that it must have access control lists. A test that needs such an ability and does not find it is skipped on a machine
// without the property (a Windows user token cannot make a symbolic link, an NTFS volume has no permission bits); with the property the same test fails, so a CI step that states what its
// runner offers can never pass by skipping. The build only checks the names and hands the list to the test JVM as the system property portal.test.require, which the test code reads.
val testRequire: String? = providers.gradleProperty("portal.test.require").orNull?.also { value ->
    val allowed = setOf("posix", "links", "acl")
    // The names are compared as they are given, not trimmed: the test code compares the same text, so a name with a blank around it (or an empty name) is refused here, as a size
    // property with a blank is (D-0111 rules).
    val names = value.split(',')
    if (names.any { it !in allowed }) {
        throw GradleException("-Pportal.test.require must be a comma separated list of ${allowed.sorted()} without blanks, but is '$value'")
    }
}

/** The variables the host agents set in their session (a test must not see them): the agent hosts' own, the governance suite's, and this product's BRIDGE_* / PORTAL_* / OPERMAND_* settings. */
val hostSessionVariable = Regex("(AGENT_GOVERNANCE|AGS_|CLAUDE|CODEX|GEMINI|ANTHROPIC|OPENAI|BRIDGE_|PORTAL_|OPERMAND_).*", RegexOption.IGNORE_CASE)

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    dependencies {
        "implementation"(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
        "testImplementation"(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.assertj:assertj-core")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // Every javac warning is a failure (Principles 7.4). -parameters: Spring (@ConfigurationProperties records, @McpTool) names parameters from the bytecode.
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters"))
    }

    // Isolation of a test run from the machine and the session that starts Gradle (Test.environment, Test.systemProperty:
    // https://docs.gradle.org/current/dsl/org.gradle.api.tasks.testing.Test.html). A forked JVM inherits the environment of the Gradle process, so without
    // this a test could reach the MCP server of the agent session that ran the build, this product's own BRIDGE_* settings and the real home folder.
    // The folders are per task under the module's build folder, so working copies and modules that run at the same time never share a file.
    // The block is the same as in the other products of the family.
    val isolatedFolders = layout.buildDirectory.dir("isolated")

    fun <T> T.isolateFromHost() where T : Task, T : JavaForkOptions {
        environment.keys.removeIf { hostSessionVariable.matches(it) }
        // The JVM reads these two on its own start and puts what they hold (-Xmx, -XX:ActiveProcessorCount ...) next to the test JVM's arguments; a host value must not size a test
        // JVM behind the run's back (case S-D0111-09). Case-insensitive like the other removals: Windows names are.
        environment.keys.removeIf { it.equals("JAVA_TOOL_OPTIONS", ignoreCase = true) || it.equals("JDK_JAVA_OPTIONS", ignoreCase = true) }
        val home = isolatedFolders.get().dir("$name/home").asFile
        val temporary = isolatedFolders.get().dir("$name/tmp").asFile
        val folders = mapOf(
            "HOME" to home, "USERPROFILE" to home, "APPDATA" to home.resolve("AppData/Roaming"), "LOCALAPPDATA" to home.resolve("AppData/Local"),
            "TEMP" to temporary, "TMP" to temporary, "TMPDIR" to temporary,
        )
        folders.forEach { (variable, folder) ->
            environment.keys.removeIf { it.equals(variable, ignoreCase = true) } // Windows names are case-insensitive
            environment(variable, folder.absolutePath)
        }
        // The isolated folders lie inside this checkout, whose .git would make git see them as part of the repository. A git ceiling stops the search for a repository at the
        // isolated folders (https://git-scm.com/docs/git#Documentation/git.txt-codeGITCEILINGDIRECTORIEScode).
        environment.keys.removeIf { it.equals("GIT_CEILING_DIRECTORIES", ignoreCase = true) }
        environment("GIT_CEILING_DIRECTORIES", isolatedFolders.get().asFile.absolutePath)
        // HOME above hides the user's git configuration, but not the system one (Git for Windows keeps core.autocrlf there), which would reach every temporary repository a test makes.
        // GIT_CONFIG_NOSYSTEM stops git from reading it (https://git-scm.com/docs/git#Documentation/git.txt-codeGITCONFIGNOSYSTEMcode).
        environment.keys.removeIf { it.equals("GIT_CONFIG_NOSYSTEM", ignoreCase = true) }
        environment("GIT_CONFIG_NOSYSTEM", "1")
        // On Windows user.home and java.io.tmpdir come from the OS profile, not from the variables above.
        systemProperty("user.home", home.absolutePath)
        systemProperty("java.io.tmpdir", temporary.absolutePath)
        // The folders of an earlier run are emptied before this one starts: a test run that left its temporary files behind would otherwise add them to the next run's, and the folder
        // would grow by every run. The work is Gradle's injectable FileSystemOperations.delete (a file it cannot delete fails the task, it is not skipped; it also deletes the read-only
        // files git makes under .git/objects, which Kotlin's File.deleteRecursively does not on Windows). The lambda holds this service and local files, no project object, which the
        // configuration cache could not store
        // (https://docs.gradle.org/current/userguide/configuration_cache_requirements.html, "Requirements" > "Using the injected services").
        val fileSystem = objects.newInstance<InjectedFileSystem>().fileSystem
        val ownFolders = listOf(home, temporary)
        val allFolders = folders.values.toList()
        doFirst {
            fileSystem.delete { delete(ownFolders) }
            allFolders.forEach { it.mkdirs() }
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        isolateFromHost()
        // A test run is run again and not replayed from the build cache (org.gradle.caching): the tests start real processes and read the machine, and their point is that they ran
        // here and now. Task.outputs.doNotCacheIf: https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/TaskOutputs.html
        outputs.doNotCacheIf("a test run is run, not replayed: it starts processes and reads the machine") { true }
        // The sizes are the run's to state (see the top of this file); without a statement the JVM and Gradle defaults apply.
        testCpus?.let { jvmArgs("-XX:ActiveProcessorCount=$it") }
        testHeap?.let { maxHeapSize = it }
        testForks?.let { maxParallelForks = it.toInt() }
        // What the run stated, handed to the tests that check the JVM they run in (TestJvmSizeTest: cases S-D0111-01 to -03; the fork count is read from the run's log, S-D0111-04); left out when not stated.
        testCpus?.let { systemProperty("portal.test.stated.cpus", it) }
        testHeap?.let { systemProperty("portal.test.stated.heap", it) }
        testForks?.let { systemProperty("portal.test.stated.forks", it) }
        // The abilities the run requires (see testRequire above); left out when not stated, and the tests that need an ability then skip without it.
        testRequire?.let { systemProperty("portal.test.require", it) }
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required.set(true)
            html.required.set(false)
        }
    }
}

val jacocoRootReport = tasks.register<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoRootReport") {
    description = "Aggregates JaCoCo coverage across all modules from every executed test task."
    group = "verification"
    val javaSubprojects = subprojects.filter { it.name != "opermand-arch-checks" }
    dependsOn(javaSubprojects.map { it.tasks.named("classes") })
    mustRunAfter(subprojects.map { it.tasks.withType<Test>() })
    additionalSourceDirs.from(javaSubprojects.map { it.extensions.getByType<JavaPluginExtension>().sourceSets.named("main").map { ss -> ss.allSource.srcDirs } })
    sourceDirectories.from(javaSubprojects.map { it.extensions.getByType<JavaPluginExtension>().sourceSets.named("main").map { ss -> ss.allSource.srcDirs } })
    classDirectories.from(javaSubprojects.map { it.extensions.getByType<JavaPluginExtension>().sourceSets.named("main").map { ss -> ss.output.classesDirs } })
    executionData.from(subprojects.map { sp -> sp.fileTree(sp.layout.buildDirectory.dir("jacoco")) { include("*.exec") } })
    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/root/jacocoRootReport.xml"))
        html.required.set(false)
    }
}

tasks.named("sonar") {
    dependsOn(subprojects.map { it.tasks.named("classes") })
    dependsOn(subprojects.map { it.tasks.named("testClasses") })
    mustRunAfter(jacocoRootReport)
}
