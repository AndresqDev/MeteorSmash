pluginManagement {
    repositories {
        maven { url = uri("libs/maven") }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = uri("libs/maven") }
        mavenCentral()
    }
}

rootProject.name = "meteorsmash"
include("java-plugin")
