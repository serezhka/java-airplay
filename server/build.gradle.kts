plugins {
    id("airplay.java-library")
}

dependencies {
    implementation(projects.protocol)
    implementation(libs.jmdns)
    implementation(libs.slf4j.api)
    implementation(libs.dd.plist)
    implementation(libs.netty.all)
    implementation(libs.eddsa)
    implementation(libs.m3u8.parser)

    testImplementation(libs.archunit.junit5)
}
