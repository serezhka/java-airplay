plugins {
    id("airplay.spring-boot")
}

dependencies {
    implementation(projects.app.common)
    implementation(libs.netty.all)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
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
