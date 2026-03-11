import java.util.Properties
import java.net.URL
import java.io.FileOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp") version "1.9.20-1.0.14"
}

// Load local.properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}
val defaultApiKey = localProperties.getProperty("ZHIPU_API_KEY") ?: ""
val minimaxApiKey = localProperties.getProperty("MINIMAX_API_KEY") ?: ""

// Tasks
tasks.register("downloadModel") {
    val modelUrl = "https://github.com/sidhu-master/AndroidAutoGLM/releases/download/SherpaModel/model_backup.onnx"
    val outputDir = project.file("src/main/assets/sherpa-model")
    val outputFile = File(outputDir, "model.onnx")

    doLast {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        if (!outputFile.exists()) {
            println("Downloading model.onnx from $modelUrl...")
            try {
                val url = URL(modelUrl)
                val connection = url.openConnection()
                connection.connect()
                connection.getInputStream().use { inputStream ->
                    FileOutputStream(outputFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                println("Download complete: ${outputFile.absolutePath}")
            } catch (e: Exception) {
                println("Error downloading model: ${e.message}")
                throw e
            }
        } else {
            println("Model file already exists. Skipping download.")
        }
    }
}

// 从 Shizuku manager 构建产物复制 libshizuku.so、libadb.so 到 jniLibs（用于无线调试启动和配对）
tasks.register("copyShizukuLibs") {
    doLast {
        val shizukuRoot = rootProject.file("Shizuku")
        // AGP 8.x: mergeDebugNativeLibs/out/lib/arm64-v8a；旧版: debug/out/lib/arm64-v8a
        val paths = listOf(
            "manager/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a",
            "manager/build/intermediates/merged_native_libs/debug/out/lib/arm64-v8a"
        )
        val managerBuild = paths.map { shizukuRoot.resolve(it) }.firstOrNull { it.exists() }
        val dest = file("src/main/jniLibs/arm64-v8a")
        if (managerBuild != null) {
            dest.mkdirs()
            listOf("libshizuku.so", "libadb.so").forEach { lib ->
                val src = managerBuild.resolve(lib)
                if (src.exists()) {
                    src.copyTo(dest.resolve(lib), overwrite = true)
                    println("Copied $lib to jniLibs")
                } else {
                    println("Warning: $lib not found. Build Shizuku manager: cd Shizuku && ./gradlew :manager:assembleDebug")
                }
            }
        } else {
            println("Shizuku manager build not found. Run: cd Shizuku && ./gradlew :manager:assembleDebug")
        }
    }
}

// Hook into preBuild
tasks.named("preBuild") {
    dependsOn("downloadModel", "copyShizukuLibs")
}

android {
    namespace = "com.sidhu.androidautoglm"
    compileSdk = 35

    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.sidhu.androidautoglm"
        minSdk = 30
        targetSdk = 34
        versionCode = 8
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        
        buildConfigField("String", "DEFAULT_API_KEY", "\"$defaultApiKey\"")
        buildConfigField("String", "MINIMAX_API_KEY", "\"$minimaxApiKey\"")

        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            buildConfigField("Boolean", "AUTO_INPUT_DEV_MODE", "false")
        }
        release {
            buildConfigField("Boolean", "AUTO_INPUT_DEV_MODE", "false")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf("**/libonnxruntime.so")
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            pickFirsts += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
}

dependencies {

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.appcompat:appcompat:1.7.0")
    
    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")
    
    // Retrofit
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    
    // Gson
    implementation("com.google.code.gson:gson:2.10.1")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // DataStore (Preferences)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Shizuku API (系统级控制：WiFi/蓝牙/音量等)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Shizuku 内置启动器（无线调试，通过 includeBuild 引入）
    implementation("com.sidhu.androidautoglm:shizuku-starter:1.0.0")
    implementation("moe.shizuku:server")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation(files("libs/sherpa-onnx-1.12.20.aar"))
}
