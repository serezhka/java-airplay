plugins {
    id("airplay.java-library")
    id("io.spring.dependency-management")
}

val springBootVersion = extensions.getByType<VersionCatalogsExtension>()
    .named("libs")
    .findVersion("springBoot")
    .get()
    .requiredVersion

extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
    }
}
