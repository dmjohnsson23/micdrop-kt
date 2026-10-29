plugins {
    kotlin("jvm") version "2.2.21"
}

group = "micdrop"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.apache.commons:commons-csv:1.14.0")

    // Optional: only needed to use the database extension (micdrop.ext.jooq). Compiled against, but not passed
    // on to projects using this library, which add jOOQ and a JDBC driver themselves if they use it.
    compileOnly("org.jooq:jooq:3.21.9")
    // Likewise optional: only needed for the HTML/XML extension (micdrop.ext.jsoup)
    compileOnly("org.jsoup:jsoup:1.23.2")

    testImplementation(kotlin("test"))
    testImplementation("org.jooq:jooq:3.21.9")
    testImplementation("org.jsoup:jsoup:1.23.2")
    testImplementation("org.xerial:sqlite-jdbc:3.53.4.0")
}

kotlin {
    jvmToolchain(24)
}

tasks.test {
    useJUnitPlatform()
    // Quiet the SQLite driver's native library warning and jOOQ's startup banner
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("org.jooq.no-logo", "true")
    systemProperty("org.jooq.no-tips", "true")
}

// The examples (examples/kotlin, with their data in examples/data) are a separate source set: they run against the
// library, with the optional dependencies it can use, but are not part of it. Run them with, for instance,
// `./gradlew runCsvsToSqlExample`; the tests also run them.
val examples: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[examples.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[examples.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

kotlin.sourceSets.named("examples") {
    kotlin.srcDir("examples/kotlin")
}

dependencies {
    "examplesImplementation"("org.jooq:jooq:3.21.9")
    "examplesImplementation"("org.jsoup:jsoup:1.23.2")
    "examplesRuntimeOnly"("org.xerial:sqlite-jdbc:3.53.4.0")
}

sourceSets.test {
    compileClasspath += examples.output
    runtimeClasspath += examples.output
}

mapOf(
    "runCsvsToSqlExample" to "micdrop.examples.CsvsToSql",
    "runJsonFilesToSqlExample" to "micdrop.examples.JsonFilesToSql",
    "runXmlToSqlExample" to "micdrop.examples.XmlToSql",
).forEach { (taskName, mainClassName) ->
    tasks.register<JavaExec>(taskName) {
        group = "examples"
        description = "Runs the $mainClassName example, writing examples/test.db"
        javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
        classpath = examples.runtimeClasspath
        mainClass.set(mainClassName)
        workingDir = file("examples")
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        systemProperty("org.jooq.no-logo", "true")
        systemProperty("org.jooq.no-tips", "true")
    }
}
