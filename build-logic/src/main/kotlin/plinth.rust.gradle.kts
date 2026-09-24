// Сборка Rust-ядра для :app (A2.2): `plugins { id("plinth.rust") }`.
//
// Две задачи, обе запускают cargo в core/:
//   cargoNdkBuild — libplinth_ffi.so под четыре ABI → jniLibs APK;
//   uniffiKotlin  — Kotlin-биндинги → generated-исходники Kotlin.
// Обе библиотеки собираются из одних исходников в одном запуске Gradle, а
// сгенерированный код при загрузке сверяет контрольные суммы API с .so.
//
// Что нужно на машине — docs/BUILD.md.

import com.android.build.api.variant.ApplicationAndroidComponentsExtension

val androidComponents = extensions.getByType<ApplicationAndroidComponentsExtension>()
val core = rootProject.layout.projectDirectory.dir("core")

/** Исходники ядра — всё в core/, кроме результатов сборки. */
val rustSources = fileTree(core) { exclude("target/**") }

val cargoNdkBuild =
    tasks.register<CargoNdkBuild>("cargoNdkBuild") {
        description = "Собирает libplinth_ffi.so под все ABI через cargo-ndk."
        sources.from(rustSources)
        workspace.set(core)
        ndkPath.set(androidComponents.sdkComponents.ndkDirectory.map { it.asFile.absolutePath })
        outputDir.set(layout.buildDirectory.dir("rust/jniLibs"))
    }

val uniffiKotlin =
    tasks.register<UniffiKotlin>("uniffiKotlin") {
        description = "Генерирует Kotlin-биндинги UniFFI для ядра."
        sources.from(rustSources)
        workspace.set(core)
        outputDir.set(layout.buildDirectory.dir("generated/uniffi/kotlin"))
    }

// ABI и minSdk — из DSL приложения: ядро собирается ровно под то, что попадёт в APK,
// и под minSdk приложения — ниже неё библиотеку не загрузят.
androidComponents.finalizeDsl { android ->
    val filters = android.defaultConfig.ndk.abiFilters.toList()
    check(filters.isNotEmpty()) { "plinth.rust: задайте android.defaultConfig.ndk.abiFilters — под них собирается ядро" }
    cargoNdkBuild.configure {
        abis.set(filters)
        minSdk.set(android.defaultConfig.minSdk)
    }
}

androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoNdkBuild, CargoNdkBuild::outputDir)
    variant.sources.kotlin?.addGeneratedSourceDirectory(uniffiKotlin, UniffiKotlin::outputDir)
}
