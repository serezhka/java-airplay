plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.lombok.plugin)
    implementation(libs.test.logger.plugin)
    implementation(libs.spring.boot.gradle.plugin)
    implementation(libs.spring.dependency.management.plugin)
}
