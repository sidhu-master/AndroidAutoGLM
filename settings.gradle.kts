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
        maven { url = uri("https://raw.githubusercontent.com/sidhu-master/AutoInput-Repo/main") }
        // maven { url = uri("../maven_repo") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "AndroidAutoGLM"
include(":app")

// 使用 composite build 引入 AutoInput
//includeBuild("../AutoInput") {
//    dependencySubstitution {
//        substitute(module("com.sidhu:autoinput")).using(project(":"))
//    }
//}

