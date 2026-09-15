plugins {
    id("airplay.java-library")
}

dependencies {
    implementation(projects.lib)
    implementation(projects.server)
    implementation(libs.slf4j.api)
    implementation(libs.dd.plist)
    implementation(libs.bundles.jna.full)
    implementation(libs.vlcj)
    implementation(libs.spf4j.core)
    implementation(libs.flatlaf)
    implementation(libs.avro)
}
