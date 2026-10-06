plugins {
    id("airplay.spring-boot")
    id("airplay.integration-test")
}

dependencies {
    implementation(projects.player.common)
    implementation(libs.bundles.jna.full)
    implementation(libs.bundles.gstreamer)

    testImplementation(libs.archunit.junit6)
    "integrationTestImplementation"(testFixtures(projects.player.common))
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveBaseName.set("java-airplay-gstreamer")
}

springBoot {
    mainClass.set("com.github.serezhka.airplay.app.GstreamerPlayerApp")
}

tasks.named("bootRun") {
    description = "Run the GStreamer AirPlay receiver."
}

tasks.named<org.gradle.api.tasks.testing.Test>("test") {
    failOnNoDiscoveredTests = false
}

extra["integrationTestTags"] = mapOf(
    "gstreamer" to "Runs the GStreamer playback smoke test.",
    "bench" to "Runs the GStreamer playback benchmark."
)
