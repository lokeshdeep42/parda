plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// llama.cpp is built from source by the NDK. It lives next to this repo by default.
val llamaDir: String = (findProperty("parda.llamaDir") as String?
    ?: rootProject.file("../llama.cpp").path).replace('\\', '/')
val abis: List<String> = (findProperty("parda.abis") as String? ?: "arm64-v8a").split(',').map { it.trim() }

android {
    namespace = "app.parda"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.parda"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // iQOO and every other current phone is arm64. Add x86_64 for an emulator:
        // -Pparda.abis=arm64-v8a,x86_64
        ndk { abiFilters += abis }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DLLAMA_DIR=$llamaDir",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_SHARED_LIBS=ON",
                    "-DLLAMA_BUILD_COMMON=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_BACKEND_DL=ON",
                    "-DGGML_CPU_ALL_VARIANTS=ON",
                    "-DGGML_LLAMAFILE=OFF",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    // ggml picks its CPU variant by dlopen-ing .so files from nativeLibraryDir, so they must be
    // extracted on install (matches android:extractNativeLibs in the manifest).
    packaging { jniLibs { useLegacyPackaging = true } }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))
    // PDF text extraction, fully offline.
    implementation(libs.pdfbox.android)
    // On-device OCR for the image Airlock. The bundled model ships in the APK: nothing is downloaded.
    implementation(libs.mlkit.text.recognition)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
