plugins {
    id("airplay.spring-boot")
    id("airplay.integration-test")
}

dependencies {
    implementation(projects.player.common)
    implementation(libs.javacv)
    implementation(libs.ffmpeg.platform)

    testImplementation(libs.archunit.junit5)
    "integrationTestImplementation"(testFixtures(projects.player.common))
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveBaseName.set("java-airplay-ffmpeg")
}

tasks.named("bootRun") {
    description = "Run the FFmpeg AirPlay receiver."
}

extra["integrationTestTags"] = mapOf(
    "ffmpeg" to "Runs the FFmpeg playback smoke test.",
    "bench" to "Runs the FFmpeg playback benchmark."
)
