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

rootProject.name = "hubidubitask-apps"
// -Phubidubitask.desktopOnly=true builds the desktop app without the Android
// SDK, as the Linux build container does.
if (providers.gradleProperty("hubidubitask.desktopOnly").orNull == "true") include(":shared", ":desktop")
else include(":shared", ":android", ":desktop")
