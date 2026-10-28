plugins {
    kotlin("jvm")
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(rootProject)

    implementation("org.jetbrains.kotlin:kotlin-scripting-common")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host")
    // For @file:DependsOn and @file:Repository in scripts
    implementation("org.jetbrains.kotlin:kotlin-scripting-dependencies")
    implementation("org.jetbrains.kotlin:kotlin-scripting-dependencies-maven")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // The library only compiles against these, so they are bundled here for scripts to use. Scripts needing other JDBC
    // drivers or libraries can pull them in with @file:DependsOn.
    runtimeOnly("org.jooq:jooq:3.21.9")
    runtimeOnly("com.mysql:mysql-connector-j:9.1.0")
    runtimeOnly("org.jsoup:jsoup:1.23.2")
    runtimeOnly("org.xerial:sqlite-jdbc:3.53.4.0")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(24)
}

application {
    mainClass.set("org.example.cli.MainKt")
    applicationName = "micdrop"
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "-Dorg.jooq.no-logo=true",
        "-Dorg.jooq.no-tips=true",
    )
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// A self-contained build: a native launcher, a trimmed Java runtime and the jars, in one directory that can be unpacked
// anywhere. `./gradlew :cli:appImage` creates build/jpackage/micdrop; `:cli:appImageTar` archives it. jpackage can only
// build for the operating system it runs on, so build on Linux for a Linux bundle (see docker/build-linux-bundle.sh).
val appImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Bundles the runner with its own Java runtime using jpackage"
    dependsOn(tasks.installDist)

    val installLib = layout.buildDirectory.dir("install/micdrop/lib")
    val outputDir = layout.buildDirectory.dir("jpackage")
    val mainJar = tasks.jar.flatMap { it.archiveFileName }
    val jpackage = javaToolchains.launcherFor(java.toolchain).map {
        it.metadata.installationPath.file("bin/jpackage").asFile.path
    }
    inputs.dir(installLib)
    outputs.dir(outputDir)

    doFirst {
        delete(outputDir)
        commandLine(
            jpackage.get(),
            "--type", "app-image",
            "--name", "micdrop",
            "--input", installLib.get().asFile.path,
            "--main-jar", mainJar.get(),
            "--main-class", "org.example.cli.MainKt",
            // The Kotlin compiler uses a good deal of the JDK reflectively, so don't let jlink guess what is needed
            "--add-modules", "java.se,jdk.unsupported",
            "--java-options", "--enable-native-access=ALL-UNNAMED",
            "--java-options", "-Dorg.jooq.no-logo=true",
            "--java-options", "-Dorg.jooq.no-tips=true",
            "--dest", outputDir.get().asFile.path,
        )
    }
}

tasks.register<Tar>("appImageTar") {
    group = "distribution"
    description = "Archives the jpackage bundle"
    dependsOn(appImage)
    from(layout.buildDirectory.dir("jpackage"))
    compression = Compression.GZIP
    archiveFileName.set("micdrop-bundle.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}
