plugins {
    id("airplay.java-library")
}

dependencies {
    implementation(projects.protocol)
    implementation(projects.player.support)
    implementation(projects.server)
    implementation(libs.slf4j.api)
    implementation(libs.dd.plist)
    implementation(libs.bundles.jna.full)
    implementation(libs.bundles.gstreamer)

    testImplementation(libs.archunit.junit5)
}

tasks.named<Test>("test") {
    failOnNoDiscoveredTests = false
}
