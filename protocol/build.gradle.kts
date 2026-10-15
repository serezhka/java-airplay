plugins {
    id("airplay.java-library")
}

dependencies {
    implementation(libs.slf4j.api)
    implementation(libs.dd.plist)
    implementation(libs.eddsa)
    implementation(libs.curve25519)
}
