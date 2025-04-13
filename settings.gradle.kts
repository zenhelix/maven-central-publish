enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "maven-central-publish"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
    }
}

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenLocal()
    }

    plugins {
        id("com.gradle.plugin-publish") version "1.3.1"
    }
}
