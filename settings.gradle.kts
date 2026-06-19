pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BlurabbitHdMapCollector"

include(":app")
include(":core:common")
include(":core:clock")
include(":domain")
include(":data")
include(":sensors")
include(":capture")
include(":perception")
include(":mapping")
include(":hdmap")
include(":export")
include(":upload")
include(":backend")
