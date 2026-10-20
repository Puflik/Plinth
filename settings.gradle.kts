pluginManagement {
    // Логика сборки: Rust-ядро (A2.2).
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor и его nanojson живут только на JitPack (Ю3б): репозиторий
        // отвечает за одну группу, остальное берётся из Google и Maven Central.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.TeamNewPipe") }
        }
    }
}

rootProject.name = "Plinth"
include(":app")
