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

// Hook into preBuild
tasks.named("preBuild") {
    dependsOn("downloadModel")
}

android {
    namespace = "com.sidhu.androidautoglm"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sidhu.androidautoglm"
        minSdk = 30
        targetSdk = 34
        versionCode = 7
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        
        buildConfigField("String", "DEFAULT_API_KEY", "\"$defaultApiKey\"")
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
    
    // Markdown
    implementation("com.github.jeziellago:compose-markdown:0.3.7")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("com.sidhu:autoinput")
    implementation(files("libs/sherpa-onnx-1.12.20.aar"))
}
