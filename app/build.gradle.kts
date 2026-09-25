import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    // A2.2: Rust-ядро — .so в jniLibs и Kotlin-биндинги в generated (build-logic).
    id("plinth.rust")
}

// Подпись релиза (используется в H5). Ключ приходит либо из
// keystore.properties рядом с проектом, либо из переменных окружения в CI.
// Ни то ни другое в репозиторий не попадает; без них release собирается
// неподписанным, и это нормально для локальной проверки.
val keystoreProperties =
    Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) {
            file.inputStream().use { load(it) }
        }
    }

fun signingSecret(
    envName: String,
    propertyName: String,
): String? = System.getenv(envName) ?: keystoreProperties.getProperty(propertyName)

android {
    namespace = "io.github.puflik.plinth"
    compileSdk = 37
    // A2.2: NDK для Rust-ядра — им собирает cargo-ndk (плагин plinth.rust).
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "io.github.puflik.plinth"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"

        // A2.4: четыре ABI — под них собирается Rust-ядро (плагин plinth.rust
        // берёт список отсюда). Фильтр отсекает и лишнее из зависимостей: JNA
        // несёт ещё armeabi и mips, под которые ядра нет.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Приёмочные замеры (H) идут минуты — только по запросу: `-Pacceptance`.
        if (!project.hasProperty("acceptance")) {
            testInstrumentationRunnerArguments["notPackage"] = "io.github.puflik.plinth.acceptance"
        }
    }

    // A1.3 📌 Два flavor закладываются сразу: добавить их позже — значит
    // переделать конфигурацию сборки, CI, подпись релизов и доступ к ключам.
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
        }
        create("fdroid") {
            dimension = "distribution"
            versionNameSuffix = "-fdroid"
        }
    }

    val releaseStoreFile = signingSecret("PLINTH_KEYSTORE_FILE", "storeFile")
    if (releaseStoreFile != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseStoreFile)
            storePassword = signingSecret("PLINTH_KEYSTORE_PASSWORD", "storePassword")
            keyAlias = signingSecret("PLINTH_KEY_ALIAS", "keyAlias")
            keyPassword = signingSecret("PLINTH_KEY_PASSWORD", "keyPassword")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            // R8 (H5): сжатие и обфускация; mapping.txt уходит в релиз рядом с APK.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 12.12: список языков приложения собирает AGP из values-*; с ним Plinth
    // есть в «Язык приложения» настроек Android 13+. Язык values/ — в
    // res/resources.properties.
    androidResources {
        generateLocaleConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // B1.4: контрактные тесты и FakeAudioEngine лежат в общем наборе исходников.
    // Их обязаны прогонять оба набора: JVM-тесты (FakeAudioEngine) и
    // инструментальные (Media3Engine в B2 — Media3 тестируется на эмуляторе).
    sourceSets {
        getByName("test") { kotlin.srcDir("src/sharedTest/kotlin") }
        getByName("androidTest") { kotlin.srcDir("src/sharedTest/kotlin") }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// C3.1: схема каждой версии базы ложится в репозиторий. По этим файлам
// пишутся и проверяются миграции — без них схему прошлой версии не восстановить.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.jna) { artifact { type = "aar" } }

    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    // Тот же набор, что и у unit-тестов: общие исходники sharedTest
    // компилируются в обоих наборах.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
