pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // AndroidX publishes its Kotlin Multiplatform artifacts (Room, DataStore,
        // Paging, the Material icons) only here.
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
    }
}

rootProject.name = "Tryptify-Windows"
include(":app")
