plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Ð”Ð¾Ð±Ð°Ð²ÑŒ Ð¿Ð»Ð°Ð³Ð¸Ð½ Compose Compiler, ÐµÑÐ»Ð¸ Ð¸ÑÐ¿Ð¾Ð»ÑŒÐ·ÑƒÐµÑˆÑŒ Kotlin 2.0+
    // id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.aiproject.musicplayer"
    compileSdk = 35 // Ð ÐµÐºÐ¾Ð¼ÐµÐ½Ð´ÑƒÐµÑ‚ÑÑ 35 Ð´Ð»Ñ Ð°ÐºÑ‚ÑƒÐ°Ð»ÑŒÐ½Ñ‹Ñ… Ð±Ð¸Ð±Ð»Ð¸Ð¾Ñ‚ÐµÐº

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

    // Ð’ ÑÐ¾Ð²Ñ€ÐµÐ¼ÐµÐ½Ð½Ñ‹Ñ… Ð¿Ñ€Ð¾ÐµÐºÑ‚Ð°Ñ… (Kotlin 2.0+) Ð±Ð»Ð¾Ðº composeOptions Ð±Ð¾Ð»ÑŒÑˆÐµ Ð½Ðµ Ð½ÑƒÐ¶ÐµÐ½.
    // Ð•ÑÐ»Ð¸ Ñƒ Ñ‚ÐµÐ±Ñ Kotlin Ð½Ð¸Ð¶Ðµ 2.0, Ð¾ÑÑ‚Ð°Ð²ÑŒ ÐºÐ°Ðº ÐµÑÑ‚ÑŒ, Ð½Ð¾ Ð¾Ð±Ð½Ð¾Ð²Ð¸ Ð²ÐµÑ€ÑÐ¸ÑŽ:
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15" // Ð”Ð»Ñ Kotlin 1.9.25
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
    implementation("androidx.media:media:1.6.0")

    // Ð˜ÑÐ¿Ð¾Ð»ÑŒÐ·ÑƒÐµÐ¼ Ð¿Ñ€ÑÐ¼Ð¾Ð¹ Ð²Ñ‹Ð·Ð¾Ð² platform Ð±ÐµÐ· ÑÐ¾Ñ…Ñ€Ð°Ð½ÐµÐ½Ð¸Ñ Ð² Ð¿ÐµÑ€ÐµÐ¼ÐµÐ½Ð½ÑƒÑŽ
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))

    // Ð‘Ð°Ð·Ð¾Ð²Ñ‹Ðµ Ð·Ð°Ð²Ð¸ÑÐ¸Ð¼Ð¾ÑÑ‚Ð¸ (Ð¸ÑÐ¿Ð¾Ð»ÑŒÐ·ÑƒÐ¹Ñ‚Ðµ Ð°ÐºÑ‚ÑƒÐ°Ð»ÑŒÐ½Ñ‹Ðµ Ð²ÐµÑ€ÑÐ¸Ð¸ Ð´Ð»Ñ 2026 Ð³Ð¾Ð´Ð°)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // ÐœÐ¾Ð´ÑƒÐ»Ð¸ Compose (Ð²ÐµÑ€ÑÐ¸Ð¸ ÑƒÐ¿Ñ€Ð°Ð²Ð»ÑÑŽÑ‚ÑÑ Ñ‡ÐµÑ€ÐµÐ· BOM Ð²Ñ‹ÑˆÐµ)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Ð¢ÐµÑÑ‚Ð¸Ñ€Ð¾Ð²Ð°Ð½Ð¸Ðµ Ð¸ Ð¾Ñ‚Ð»Ð°Ð´ÐºÐ°
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Oboe
    implementation("com.google.oboe:oboe:1.8.1")
}
