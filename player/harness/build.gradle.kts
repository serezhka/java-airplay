import java.time.Duration

plugins {
    id("airplay.java-library")
}

sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output + configurations.testCompileClasspath.get()
        runtimeClasspath += output + compileClasspath + configurations.testRuntimeClasspath.get()
    }
}

configurations.named("integrationTestImplementation") {
    extendsFrom(configurations.testImplementation.get())
}
configurations.named("integrationTestRuntimeOnly") {
    extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
    implementation(libs.slf4j.api)
    implementation(libs.dd.plist)

    "integrationTestImplementation"(projects.protocol)
    "integrationTestImplementation"(projects.server)
    "integrationTestImplementation"(projects.player.ffmpeg)
    "integrationTestImplementation"(projects.player.gstreamer)
    "integrationTestImplementation"(projects.player.dump)
    "integrationTestImplementation"(libs.bundles.logging)
    "integrationTestImplementation"(libs.netty.all)
}

fun registerIntegrationTest(taskName: String, tag: String?, taskDescription: String) {
    tasks.register<Test>(taskName) {
        description = taskDescription
        group = "verification"
        testClassesDirs = sourceSets["integrationTest"].output.classesDirs
        classpath = sourceSets["integrationTest"].runtimeClasspath
        useJUnitPlatform {
            if (tag != null) {
                includeTags(tag)
            }
        }
        // Forward harness props from Gradle JVM (-D...) into the test worker.
        listOf(
            "airplay.harness.metrics",
            "airplay.harness.bench.seconds",
            "airplay.harness.bench.player",
            "airplay.harness.reportDir",
            "airplay.gst.appsink",
            "airplay.gst.cli",
            "gstreamer.path",
            "jna.library.path"
        ).forEach { key ->
            val value = System.getProperty(key)
            if (value != null) {
                systemProperty(key, value)
            }
        }
        // Prefer env from CI when -D was not set on the Gradle JVM.
        System.getenv("AIRPLAY_GST_CLI")?.let { systemProperty("airplay.gst.cli", it) }
        // Defaults for bench when not overridden
        if (tag == "bench") {
            // Never run benches in parallel on one machine (local or single runner).
            maxParallelForks = 1
            systemProperty("junit.jupiter.execution.parallel.enabled", "false")
            systemProperty(
                "airplay.harness.metrics",
                System.getProperty("airplay.harness.metrics", "true")
            )
            systemProperty(
                "airplay.harness.bench.seconds",
                System.getProperty("airplay.harness.bench.seconds", "300")
            )
            systemProperty(
                "airplay.harness.bench.player",
                System.getProperty("airplay.harness.bench.player", "ffmpeg")
            )
            val seconds = System.getProperty("airplay.harness.bench.seconds", "300").toLongOrNull() ?: 300L
            timeout.set(Duration.ofSeconds(seconds + 120))
        }
        if (tag == "ffmpeg" || tag == "gstreamer" || tag == "dump" || tag == "loopback") {
            timeout.set(Duration.ofMinutes(3))
        }
        outputs.upToDateWhen { false }
    }
}

registerIntegrationTest(
    "integrationTest",
    null,
    "Runs all external playback / loopback integration tests (not part of build or check)."
)
registerIntegrationTest(
    "ffmpegIntegrationTest",
    "ffmpeg",
    "Runs the FFmpeg/ffplay playback smoke test."
)
registerIntegrationTest(
    "gstreamerIntegrationTest",
    "gstreamer",
    "Runs the GStreamer playback smoke test."
)
registerIntegrationTest(
    "dumpIntegrationTest",
    "dump",
    "Runs the dump sidecar recording smoke test."
)
registerIntegrationTest(
    "loopbackIntegrationTest",
    "loopback",
    "Runs localhost client→server protocol scenarios."
)
registerIntegrationTest(
    "benchIntegrationTest",
    "bench",
    "Runs multi-minute playback benchmarks with HTML/JSON metrics."
)
