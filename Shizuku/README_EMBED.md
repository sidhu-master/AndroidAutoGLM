# Shizuku 内置移植说明

本目录包含 [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) 源码，用于将无线调试启动 Shizuku 的能力内置到 AndroidAutoGLM 应用中。

## 当前状态

- ✅ Shizuku 源码已下载并放入 `AndroidAutoGLM/Shizuku`
- ✅ Shizuku-API 子模块已合并到 `Shizuku/api`
- ✅ `hidden-api-stub` 已注释（使用 Maven libs.hidden.stub）
- ⏳ 待完成：构建 Shizuku manager 并集成到主应用

## 构建 Shizuku

### 前置条件

1. **Java 17+**：Shizuku 使用 `jvmTarget = "21"`
2. **Android SDK**：compileSdk 36, buildTools 36.0.0
3. **NDK**：version 29.0.13113456
4. **CMake 3.31+**

### 构建命令

```bash
cd Shizuku
./gradlew :manager:assembleDebug
```

构建产物：
- `manager/build/outputs/apk/debug/shizuku-*.apk` - Manager APK
- `manager/src/main/jniLibs/` - libshizuku.so, libadb.so（构建时生成到 nativeLibraryDir）

### 依赖说明

Shizuku manager 依赖：
- **libsu** (com.github.topjohnwu.libsu) - Root shell
- **boringssl** (io.github.vvb2060.ndk:boringssl) - TLS
- **libcxx** (org.lsposed.libcxx) - C++ 标准库
- **BouncyCastle** - 证书/密钥
- **rikka.*** - Rikka 系列 UI 库

## 集成方案

### 方案 A：Composite Build（推荐尝试）

在 `AndroidAutoGLM/settings.gradle.kts` 中添加：

```kotlin
includeBuild("Shizuku") {
    dependencySubstitution {
        // 若 Shizuku 提供 library 变体可在此替换
    }
}
```

需修改 Shizuku 的 manager 模块，使其可输出 library 供主应用依赖。

### 方案 B：预构建产物 + 代码移植

1. 单独构建 Shizuku manager，提取：
   - `libshizuku.so`, `libadb.so` → `app/src/main/jniLibs/`
   - adb 包 Kotlin 代码 → 复制到 `com.sidhu.androidautoglm.shizuku.adb`
   - starter 逻辑 → 复制并适配

2. 主应用添加「启动 Shizuku」入口，调用 `AdbClient` + `Starter.internalCommand`

### 方案 C：双 APK 模式

构建 Shizuku manager 为独立 APK，修改 applicationId 为 `com.sidhu.androidautoglm.shizuku`，与主应用一起发布。用户安装后，主应用检测并引导启动。

## 关键代码路径

| 功能 | 路径 |
|-----|------|
| 无线 ADB 连接 | `manager/.../adb/AdbClient.kt` |
| 配对 | `manager/.../adb/AdbPairingClient.kt` |
| mDNS 发现端口 | `manager/.../adb/AdbMdns.kt` |
| 启动命令 | `manager/.../starter/Starter.kt` |
| 原生 starter | `manager/src/main/jni/starter.cpp` |

## 许可证

Shizuku 采用 Apache 2.0 许可证。注意：
- 不得使用 `moe.shizuku.privileged.api` 作为 applicationId
- 不得使用 Shizuku 作为应用名称
- 不得使用 `manager/src/main/res/mipmap*/ic_launcher*.png`
