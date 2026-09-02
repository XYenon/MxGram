pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            metadataSources {
                artifact()
            }
            content {
                includeGroup("io.github.libxposed")
            }
        }
    }
}

rootProject.name = "MxGram"
include(":app")
