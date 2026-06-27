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
        // ONNX Runtime Android + QNN EP and ObjectBox are pulled from Maven Central / Google.
        // If you switch to a vendored .aar (e.g. a custom QNN-enabled ORT build),
        // add: flatDir { dirs("app/libs") }
    }
}

rootProject.name = "Flow"
include(":app")
