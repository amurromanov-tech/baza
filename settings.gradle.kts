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
        // 🆕 v21.0.8: JitPack нужен для code-scanner (ZXing) и PhotoView.
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 🆕 v21.0.8: JitPack для com.github.yuriy-budiyev:code-scanner:2.3.2
        // (и уже существующего com.github.chrisbanes:PhotoView).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "BAZA"
include(":app")
