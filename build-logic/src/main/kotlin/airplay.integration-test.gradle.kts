import org.gradle.api.tasks.testing.Test
import java.time.Duration

plugins {
    java
}

sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output + configurations.getByName("testCompileClasspath")
    runtimeClasspath += output + compileClasspath + configurations.getByName("testRuntimeClasspath")
}

configurations.named("integrationTestImplementation") {
    extendsFrom(configurations.testImplementation.get())
}
configurations.named("integrationTestRuntimeOnly") {
    extendsFrom(configurations.testRuntimeOnly.get())
}

val forwardedProperties = listOf(
    "airplay.harness.metrics",
    "airplay.harness.bench.seconds",
    "airplay.harness.bench.player",
    "airplay.harness.reportDir",
    "airplay.gst.appsink",
    "airplay.gst.hls.headless",
    "airplay.gst.test.disposeOrphans",
    "airplay.ffmpeg.hls.headless",
    "airplay.mse.headless",
    "airplay.mse.browser",
    "airplay.mse.allow-file-hls",
    "gstreamer.path",
    "jna.library.path"
)

fun Test.wireIntegrationTest(tag: String?) {
    group = "verification"
    testClassesDirs = sourceSets.getByName("integrationTest").output.classesDirs
    classpath = sourceSets.getByName("integrationTest").runtimeClasspath
    useJUnitPlatform {
        if (tag != null) {
            includeTags(tag)
        }
    }
    forwardedProperties.forEach { key ->
        val value = System.getProperty(key)
        if (value != null) {
            systemProperty(key, value)
        }
    }
    System.getenv("AIRPLAY_GST_APPSINK")?.let { systemProperty("airplay.gst.appsink", it) }
    if (tag == "bench") {
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
        val seconds = System.getProperty("airplay.harness.bench.seconds", "300").toLongOrNull() ?: 300L
        timeout.set(Duration.ofSeconds(seconds + 120))
    } else {
        // Lifecycle suites (mirror ↔ HLS round-trip) need more than a quick smoke.
        timeout.set(Duration.ofMinutes(10))
    }
    outputs.upToDateWhen { false }
}

afterEvaluate {
    @Suppress("UNCHECKED_CAST")
    val tags = (extra["integrationTestTags"] as? Map<String, String>).orEmpty()
    tags.forEach { (tag, descriptionText) ->
        tasks.register<Test>("${tag}IntegrationTest") {
            description = descriptionText
            wireIntegrationTest(tag)
        }
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs this module's integration tests."
    wireIntegrationTest(null)
}
