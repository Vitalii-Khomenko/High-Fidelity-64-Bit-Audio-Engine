import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

kapt {
    useBuildCache = false
}

// Load signing properties from local.properties
val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
}

android {
    namespace = "com.aiproject.musicplayer"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.aiproject.musicplayer"
        minSdk = 24
        targetSdk = 35
        versionCode = 1400
        versionName = "0.14.0"

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments("-DANDROID_STL=c++_shared")
            }
        }

        ndk {
            // Phones and tablets; x86_64 for the emulator.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("audit") {
            storeFile = file(localProps.getProperty("AUDIT_KEYSTORE_FILE", "../signing/hifi-audit.jks"))
            storePassword = localProps.getProperty("AUDIT_KEYSTORE_PASSWORD", "")
            keyAlias = localProps.getProperty("AUDIT_KEY_ALIAS", "hifi-audit")
            keyPassword = localProps.getProperty("AUDIT_KEY_PASSWORD", "")
        }
        create("release") {
            storeFile     = file(localProps.getProperty("KEYSTORE_FILE", "../hifi-player.jks"))
            storePassword = localProps.getProperty("KEYSTORE_PASSWORD", "")
            keyAlias      = localProps.getProperty("KEY_ALIAS", "hifi")
            keyPassword   = localProps.getProperty("KEY_PASSWORD", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        create("audit") {
            initWith(getByName("release"))
            applicationIdSuffix = ".audit"
            versionNameSuffix = "-audit"
            resValue("string", "app_name", "HiFi Player Audit")
            signingConfig = signingConfigs.getByName("audit")
            matchingFallbacks += "release"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        // liblog is supplied by Android. Never bundle the NDK link-time stub.
        jniLibs.excludes += "**/liblog.so"
    }

    buildFeatures {
        compose = true
        prefab = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        disable += "NullSafeMutableLiveData"
    }
}

dependencies {
    implementation("androidx.media:media:1.6.0")
    implementation("androidx.room:room-runtime:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.03"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.10")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("com.google.oboe:oboe:1.9.3")
}
