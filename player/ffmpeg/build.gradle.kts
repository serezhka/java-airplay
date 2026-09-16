plugins {
    id("airplay.java-library")
}

dependencies {
    implementation(projects.lib)
    implementation(projects.server)
    implementation(libs.slf4j.api)
    implementation(libs.dd.plist)
    implementation(libs.javacv)
    implementation(libs.ffmpeg.platform)
}
