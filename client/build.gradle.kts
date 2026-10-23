plugins {
    id("airplay.java-application")
}

application {
    // Manual tools (HapMirrorApp, LegacyMirror*, PairDumpCredsApp) live under src/test.
    // Prefer dedicated tasks below; -PmainClass=… still works with test classpath via run.
    mainClass = providers.gradleProperty("mainClass")
        .orElse("com.github.serezhka.airplay.client.legacy.LegacyMirrorAudioApp")
}

tasks.named<JavaExec>("run") {
    classpath = sourceSets["test"].runtimeClasspath
}

dependencies {
    implementation(projects.protocol)
    implementation(libs.slf4j.api)
    implementation(libs.dd.plist)
    implementation(libs.jmdns)
    implementation(libs.netty.all)
    implementation(libs.eddsa)
    implementation(libs.curve25519)
    implementation(libs.bundles.logging)

    testImplementation(libs.archunit.junit6)
}

/** Manual LAN smokes live under {@code src/test} so they are not packaged into release jars. */
fun JavaExec.configureCastSmoke(main: String) {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set(main)
    standardInput = System.`in`
}

tasks.register<JavaExec>("castConnectSmoke") {
    configureCastSmoke("com.github.serezhka.airplay.client.cast.CastConnectSmokeMain")
    description = "LAN cast connect smoke (manual)"
}

tasks.register<JavaExec>("castAutoConnectSmoke") {
    configureCastSmoke("com.github.serezhka.airplay.client.cast.CastAutoConnectSmokeMain")
    description = "LAN cast auto-connect / PIN path smoke (manual)"
}

tasks.register<JavaExec>("castDiscoverSmoke") {
    configureCastSmoke("com.github.serezhka.airplay.client.cast.CastDiscoverMain")
    description = "LAN AirPlay discovery smoke (manual)"
}

tasks.register<JavaExec>("hapMirror") {
    configureCastSmoke("com.github.serezhka.airplay.client.hap.HapMirrorApp")
    description = "HAP mirror client (manual; test classpath)"
}

tasks.register<JavaExec>("legacyMirror") {
    configureCastSmoke("com.github.serezhka.airplay.client.legacy.LegacyMirrorApp")
    description = "Legacy FairPlay mirror client (manual; test classpath)"
}

tasks.register<JavaExec>("legacyMirrorAudio") {
    configureCastSmoke("com.github.serezhka.airplay.client.legacy.LegacyMirrorAudioApp")
    description = "Legacy FairPlay mirror + AAC audio client (manual; test classpath)"
}

tasks.register<JavaExec>("pairDumpCreds") {
    configureCastSmoke("com.github.serezhka.airplay.client.hap.PairDumpCredsApp")
    description = "Dump HAP pairing credentials (manual; test classpath)"
}
