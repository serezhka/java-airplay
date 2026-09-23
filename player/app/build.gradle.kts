plugins {
    id("airplay.spring-boot")
}

dependencies {
    implementation(projects.lib)
    implementation(projects.server)
    implementation(projects.player.gstreamer)
    implementation(projects.player.dump)
    implementation(projects.player.ffmpeg)

    implementation(libs.slf4j.api)
    implementation(libs.dd.plist)
    implementation("org.springframework.boot:spring-boot-starter")
    implementation(libs.system.tray)

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveBaseName.set("java-airplay-server")
}
