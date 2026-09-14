// Root project: wrapper only. Plugin versions live in gradle/libs.versions.toml
// and are applied via build-logic convention plugins (not apply-false stubs here).

tasks.wrapper {
    gradleVersion = "9.7.1"
    distributionType = Wrapper.DistributionType.ALL
}
