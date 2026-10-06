plugins {
    id("airplay.spring-library")
    id("java-test-fixtures")
    id("airplay.integration-test")
}

dependencies {
    api(projects.protocol)
    api(projects.server)
    api(libs.slf4j.api)
    api(libs.dd.plist)
    api("org.springframework.boot:spring-boot-starter")

    implementation(libs.jakarta.annotation.api)
    implementation(libs.system.tray)

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation(libs.archunit.junit6)
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter)
    "integrationTestImplementation"(testFixtures(project()))
}

extra["integrationTestTags"] = mapOf(
    "loopback" to "Runs localhost playback callbacks without a native player.",
    "dump" to "Runs the dump sidecar recording smoke test."
)
