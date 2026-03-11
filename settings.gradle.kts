pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "AndroidAutoGLM"
include(":app")

// Shizuku 内置：shizuku-starter 需先发布到 mavenLocal
// 修改 shizuku-starter 后运行: cd Shizuku && ./gradlew :shizuku-starter:publishToMavenLocal
includeBuild("Shizuku") {
    dependencySubstitution {
        substitute(module("com.sidhu.androidautoglm:shizuku-starter")).using(project(":shizuku-starter"))
        substitute(module("moe.shizuku:server")).using(project(":server"))
    }
}

