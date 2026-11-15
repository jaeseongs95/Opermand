// Shared build of the 3.0.0 Java product (docs/migration/foundation-cases.md). Module-specific dependencies live in each module's build.gradle.kts;
// versions come from the Spring Boot BOM and the Spring AI BOM, and a build file names a version only for a library that neither BOM manages.
plugins {
    id("com.diffplug.spotless")
}

val springBootVersion = property("springBootVersion")

// The source header of every file (the three-product common additional terms text, revision 1; case S-LIC-05): Java in // lines, SQL in -- lines, YAML and
// properties in # lines. Documents (Markdown, HTML) carry no header; the License section of the README covers them. Not covered: native/freerdp-input and the Gradle
// wrapper (Apache-2.0, own notices; they are outside the targets), and generated code (excluded below).
// Each delimiter starts at the first line that is neither one of the three header lines nor empty (so that the blank line after the header does not make the step
// add a second one on every run, which Spotless reports as "does not converge"), so that a header is replaced and a comment that already stands at the top of a
// file (the SQL scripts and the properties file start with one) is kept.
// spotlessCheck is NOT part of check or build yet: enforceCheck = false. It is connected after spotlessApply has run once over the tree.
val headerLines = listOf(
    "SPDX-License-Identifier: AGPL-3.0-or-later",
    "Copyright (C) 2026 Jaeseong Song",
    "See ADDITIONAL-TERMS.md for the additional terms under AGPL section 7.",
)

fun header(comment: String) = headerLines.joinToString("\n", postfix = "\n\n") { "$comment $it" }

fun notHeader(comment: String) = "^(?!${Regex.escape(comment)} (SPDX-License-Identifier|Copyright \\(C\\) 2026|See ADDITIONAL-TERMS)|$)"

spotless {
    isEnforceCheck = false // a Kotlin DSL property of the Java getter isEnforceCheck()/setEnforceCheck(boolean)
    java {
        target("portal-*/src/**/*.java")
        targetExclude("**/generated/**", "**/build/**")
        licenseHeader(header("//"), notHeader("//"))
    }
    format("sql") {
        target("portal-*/src/**/*.sql")
        targetExclude("**/generated/**", "**/build/**")
        licenseHeader(header("--"), notHeader("--"))
    }
    format("yamlAndProperties") {
        target("portal-*/src/**/*.yaml", "portal-*/src/**/*.yml", "portal-*/src/**/*.properties")
        targetExclude("**/generated/**", "**/build/**")
        licenseHeader(header("#"), notHeader("#"))
    }
}

allprojects {
    group = "io.github.jaeseongs95.portal"
}

/** The variables the host agents set in their session (a test must not see them): the agent hosts' own, the governance suite's, and this product's BRIDGE_* / PORTAL_* settings. */
val hostSessionVariable = Regex("(AGENT_GOVERNANCE|AGS_|CLAUDE|CODEX|GEMINI|ANTHROPIC|OPENAI|BRIDGE_|PORTAL_).*", RegexOption.IGNORE_CASE)

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
    }

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
    // The folders are per task under the module's build folder, so modules that run at the same time never share a file.
    val isolatedFolders = layout.buildDirectory.dir("isolated")

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        environment.keys.removeIf { hostSessionVariable.matches(it) }
        val home = isolatedFolders.get().dir("$name/home").asFile
        val temporary = isolatedFolders.get().dir("$name/tmp").asFile
        mapOf(
            "HOME" to home, "USERPROFILE" to home, "APPDATA" to home.resolve("AppData/Roaming"), "LOCALAPPDATA" to home.resolve("AppData/Local"),
            "TEMP" to temporary, "TMP" to temporary, "TMPDIR" to temporary,
        ).forEach { (variable, folder) ->
            environment.keys.removeIf { it.equals(variable, ignoreCase = true) } // Windows names are case-insensitive
            environment(variable, folder.absolutePath)
        }
        // On Windows user.home and java.io.tmpdir come from the OS profile, not from the variables above.
        systemProperty("user.home", home.absolutePath)
        systemProperty("java.io.tmpdir", temporary.absolutePath)
        doFirst { listOf(home, home.resolve("AppData/Roaming"), home.resolve("AppData/Local"), temporary).forEach { it.mkdirs() } }
        // One CPU and a stated heap per test JVM (Principles 7.4); the forks are the only knob: -Pportal.test.forks=<n> (default 1).
        jvmArgs("-XX:ActiveProcessorCount=1")
        maxHeapSize = "512m"
        maxParallelForks = providers.gradleProperty("portal.test.forks").map { it.toInt() }.getOrElse(1)
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
