plugins {
    id("airplay.spring-boot")
}

dependencies {
    implementation(projects.app.common)
    implementation(libs.netty.all)
    implementation(libs.javacv)
    implementation(libs.ffmpeg.platform)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.playwright)
    testImplementation(testFixtures(projects.app.common))
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
