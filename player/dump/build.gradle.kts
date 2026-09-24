plugins {
    id("airplay.java-library")
}

dependencies {
    implementation(projects.protocol)
    implementation(projects.server)
    implementation(libs.slf4j.api)
    implementation(libs.dd.plist)
    implementation(libs.jakarta.annotation.api)

    testImplementation(libs.archunit.junit5)
}
