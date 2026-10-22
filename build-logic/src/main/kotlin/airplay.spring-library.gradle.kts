plugins {
    id("airplay.java-library")
    id("io.spring.dependency-management")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val springBootVersion = libs.findVersion("springBoot").get().requiredVersion
// Boot BOM pins an older Netty; keep catalog netty.* as the source of truth.
extra["netty.version"] = libs.findVersion("netty").get().requiredVersion

extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
    }
}
