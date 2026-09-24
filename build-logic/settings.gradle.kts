// Логика сборки, общая для модулей (A2.2): сейчас — сборка Rust-ядра.
// Подключается из settings.gradle.kts корня через includeBuild.

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Версии — из того же каталога, что и у приложения.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
