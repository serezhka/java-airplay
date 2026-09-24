plugins {
    id("airplay.java-library")
}

dependencies {
    implementation(projects.protocol)
    implementation(projects.player.support)
    implementation(projects.server)
    implementation(libs.slf4j.api)
    implementation(libs.dd.plist)
    implementation(libs.javacv)
    implementation(libs.ffmpeg.platform)

    testImplementation(libs.archunit.junit5)
}
