plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Добавь плагин Compose Compiler, если используешь Kotlin 2.0+
    // id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.aiproject.musicplayer"
    compileSdk = 35 // Рекомендуется 35 для актуальных библиотек

    defaultConfig {
        applicationId = "com.aiproject.musicplayer"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments("-DANDROID_STL=c++_shared")
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        prefab = true
    }

    // В современных проектах (Kotlin 2.0+) блок composeOptions больше не нужен.
    // Если у тебя Kotlin ниже 2.0, оставь как есть, но обнови версию:
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15" // Для Kotlin 1.9.25
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Используем прямой вызов platform без сохранения в переменную
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))

    // Базовые зависимости (используйте актуальные версии для 2026 года)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Модули Compose (версии управляются через BOM выше)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Тестирование и отладка
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Oboe
    implementation("com.google.oboe:oboe:1.8.1")
}