plugins {
    id("airplay.java-library")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
// Boot BOM pins an older Netty; keep catalog netty.* as the source of truth.
extra["netty.version"] = libs.findVersion("netty").get().requiredVersion
