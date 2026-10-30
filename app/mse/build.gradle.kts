plugins {
    id("airplay.spring-boot")
    id("airplay.integration-test")
}

dependencies {
    implementation(projects.app.common)
    implementation(libs.netty.all)
    // No javacv/ffmpeg-platform: mirror AAC-ELD/LC is decoded in-browser via fdk-aac WASM.

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.playwright)
    testImplementation(testFixtures(projects.app.common))
    "integrationTestImplementation"(testFixtures(projects.app.common))
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveBaseName.set("java-airplay-mse")
}

springBoot {
    mainClass.set("com.github.serezhka.airplay.app.mse.app.MsePlayerApp")
}

tasks.named("bootRun") {
    description = "Run the browser MSE AirPlay receiver (Chromium + hls.js + jmuxer)."
}

tasks.named<Test>("test") {
    // Playwright downloads its own Chromium on first run (~150MB).
    systemProperty("playwright.skip.browser.download", System.getProperty("playwright.skip.browser.download", "false"))
}

extra["integrationTestTags"] = mapOf(
    "mse" to "Runs the browser MSE playback lifecycle harness."
)
