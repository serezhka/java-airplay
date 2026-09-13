plugins {
    alias(libs.plugins.lombok) apply false
    alias(libs.plugins.test.logger) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

tasks.wrapper {
    gradleVersion = "9.7.1"
    distributionType = Wrapper.DistributionType.ALL
}
