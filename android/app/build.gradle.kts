plugins {
    id("com.android.application")
}

android {
    namespace = "com.deivid22srk.hellsgate"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.deivid22srk.hellsgate"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // arm64-v8a only: the recompiled guest code, NEON paths and the
        // Vulkan backend are arm64-first; x86_64 emulator GPUs lack the
        // required Vulkan features.
        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DREX_PROJECT_ROOT=${project.projectDir.parentFile.parentFile.absolutePath}",
                )
                cppFlags += listOf("-std=c++23")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // Keep symbols useful for tombstone triage.
            isJniDebuggable = true
        }
    }

    packaging {
        // Real extracted libs: the GPU plugin loader dlopens
        // librexgpu-xenos.so from nativeLibraryDir by absolute path.
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
