import dev.detekt.gradle.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

allprojects {
    apply(plugin = "dev.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt.yml"))
        parallel = true
        // По умолчанию detekt смотрит только src/main и src/test — flavor,
        // sharedTest (контракт и FakeAudioEngine) и androidTest выпадали
        // из проверки молча. Берём исходники Kotlin всех наборов.
        source.setFrom(
            fileTree("src") {
                include("*/kotlin/**/*.kt")
            },
        )
    }

    // Правила форматирования ktlint читает из .editorconfig —
    // здесь только то, чего там не выразить.
    extensions.configure<KtlintExtension> {
        android.set(true)
        ignoreFailures.set(false)
    }
}
