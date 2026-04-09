plugins {
    kotlin("jvm") version "1.9.23"
    `java-library`
}

group = "sn.innolink.kyvshield"
version = "1.0.0"

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

dependencies {
    // JSON parsing — only external runtime dependency
    implementation("org.json:json:20240303")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

tasks.test {
    useJUnitPlatform()
    // Allow tests that connect to localhost:8080 to time out gracefully
    systemProperty("junit.jupiter.execution.timeout.default", "180s")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

// ─── Jar configuration ───────────────────────────────────────────────────────

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "KyvShield Kotlin SDK",
            "Implementation-Version" to project.version,
        )
    }
}

// Fat / uber jar with all dependencies bundled (optional — run `./gradlew fatJar`)
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Assembles a fat JAR with all runtime dependencies included."
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Implementation-Title" to "KyvShield Kotlin SDK (fat)")
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    with(tasks.jar.get())
}
