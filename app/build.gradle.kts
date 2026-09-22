import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
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

    defaultConfig {
        applicationId = "io.github.puflik.plinth"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-alpha01"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            // Минификация включается в эпике H вместе с правилами R8.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    // Тот же набор, что и у unit-тестов: общие исходники sharedTest
    // компилируются в обоих наборах.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
