// App build file: app name/id, Android versions, and the C++ (whisper.cpp) build.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.asif.vmreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.asif.vmreader"
        minSdk = 29                 // Android 10+ (needed to decode WhatsApp .opus files)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }   // almost all modern phones
        externalNativeBuild {
            cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release") }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sign with the debug key so the APK installs without extra setup
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
