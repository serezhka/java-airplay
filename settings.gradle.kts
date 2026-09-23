pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "java-airplay"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include("lib")
include("server")
include("player:app")
include("player:gstreamer")
include("player:dump")
include("player:ffmpeg")
include("player:harness")
