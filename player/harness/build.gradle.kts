plugins {
    id("airplay.java-library")
}

sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output + configurations.testCompileClasspath.get()
        runtimeClasspath += output + compileClasspath + configurations.testRuntimeClasspath.get()
    }
}

val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
val integrationTestRuntimeOnly by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
    implementation(libs.slf4j.api)
    implementation(libs.dd.plist)

    "integrationTestImplementation"(projects.lib)
    "integrationTestImplementation"(projects.server)
    "integrationTestImplementation"(projects.client)
    "integrationTestImplementation"(projects.player.ffmpeg)
    "integrationTestImplementation"(projects.player.gstreamer)
    "integrationTestImplementation"(projects.player.dump)
    "integrationTestImplementation"(projects.player.vlc)
    "integrationTestImplementation"(libs.bundles.logging)
    "integrationTestImplementation"(libs.eddsa)
    "integrationTestImplementation"(libs.curve25519)
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
            "gstreamer.path",
            "jna.library.path"
        ).forEach { key ->
            val value = System.getProperty(key)
            if (value != null) {
                systemProperty(key, value)
            }
        }
        // Defaults for bench when not overridden
        if (tag == "bench") {
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
    "vlcIntegrationTest",
    "vlc",
    "Runs the VLC playback smoke test."
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
