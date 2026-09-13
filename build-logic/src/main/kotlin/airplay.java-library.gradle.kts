plugins {
    id("java-library")
    id("io.freefair.lombok")
    id("com.adarshr.test-logger")
}

group = "com.github.serezhka"
version = "1.0.8"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencies {
    // Versions come from the consuming project's version catalog via forced resolution in modules.
    // Keep only JUnit wiring here so every library module can run tests out of the box.
    "testImplementation"(platform("org.junit:junit-bom:5.12.2"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}
