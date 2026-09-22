plugins {
    id("airplay.java-application")
}

application {
    // Override: -PmainClass=com.github.serezhka.airplay.client.legacy.LegacyMirrorApp
    mainClass = providers.gradleProperty("mainClass")
        .orElse("com.github.serezhka.airplay.client.App")
}

dependencies {
    implementation(projects.lib)
    implementation(libs.slf4j.api)
    implementation(libs.dd.plist)
    implementation(libs.jmdns)
    implementation(libs.netty.all)
    implementation(libs.eddsa)
    implementation(libs.curve25519)
    implementation(libs.bundles.jna.full)
    implementation(libs.bundles.gstreamer)
    implementation(libs.bundles.logging)
}
