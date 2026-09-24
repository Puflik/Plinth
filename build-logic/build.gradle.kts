plugins {
    `kotlin-dsl`
}

dependencies {
    // Только API: сам AGP в сборку уже принёс корневой build.gradle.kts.
    compileOnly(libs.android.gradle.api)
}
