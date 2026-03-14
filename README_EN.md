# ✨ AutoGLM Android Assistant | Your Autonomous Phone Agent 🤖

## Language
- [中文 (Chinese)](README.md)
- [English](README_EN.md)

## 📱 System Requirements
> ⚠️ **Important**:
> **This project requires Android 11 (API 30) or higher.**
> 
> While the app may install on Android 8.0+, the core **screen recognition** feature relies on the native Accessibility Screenshot API introduced in Android 11.
> On devices running Android 10 or lower, the AI will not be able to "see" the screen.

---

This is a **Standalone Android Version** of AutoGLM! 🚀

Based on the original AutoGLM, this project translates the core logic to native Android code. It replaces ADB commands with direct **Android Accessibility Service** calls. 
**No PC required. No ADB setup. No Python scripts.** Just install and run!

---

## 🌟 Why Choose This Version? (vs Original AutoGLM)

### ❌ Original AutoGLM:
*   💻 **Requires PC**: Must be connected to a computer to run.
*   🐍 **Complex Setup**: Needs Python, ADB, and dependency management.
*   🔌 **Unstable Connection**: ADB cables or wireless debugging often disconnect.
*   🐢 **High Latency**: Screen capture -> PC -> Model -> ADB Command loop is slow.

### ✅ My Native Android Version:
*   📱 **Fully Independent**: **Just install the APK!** The phone is the brain. Run it anywhere. 🏃‍♂️
*   ⚡️ **Zero Configuration**: No environment setup. No code. Works out of the box! 🎉
*   🖐️ **Native Control**: Uses Android Accessibility Service for smooth clicks and swipes.
*   🗣️ **Voice Interaction**: Built-in voice recognition. Just speak your commands! 🎙️
*   👀 **Real-time Feedback**: **Floating Window** shows exactly what the AI is thinking and doing.

---

## 1. Quick Start

### Step 1: Grant Necessary Permissions
When opening the app for the first time, grant these two key permissions:
*   **Accessibility Service**: Allows AI to click, swipe, and read screen content. 👆
*   **Overlay Permission**: Displays the AI status floating window over other apps. 💬

### Step 2: Configure API Key
This app uses Zhipu AI's vision model. You need an API Key:
1.  Get a key from [Zhipu AI Open Platform](https://bigmodel.cn/usercenter/proj-mgmt/apikeys).
2.  Go to App **Settings** -> **Enter API Key** -> Paste and Save. ✅

### Step 3: Start Using
*   **Text Command**: Type "Open YouTube and search for funny cats" 🔍
*   **Voice Command**: Hold the mic button, speak, and release!
*   Click **Send**, then **hands off the screen** and watch it work! 😎

---

## 2. Floating Window Status

*   🟢 **Green (Ready)**: Idle and waiting for commands.
*   ⚪ **Gray (Running)**: Thinking or executing actions. **Do not touch the screen!** 🤫
*   🔴 **Red (Error)**: Something went wrong. Click "Stop" to reset.

---

## 3. FAQ
*   **No action performed?** Check if Shizuku was killed by the system. Re-enable it in Settings.
*   **API Key Invalid?** Check for extra spaces or account balance.

---

## 📜 License & Permissions

### Copyright & Usage

This project (AutoGLM Android Assistant) is developed by **Xi De Hu AI Programming** and all rights are reserved.

- **Personal / Educational / Non-commercial use**: You may freely use, modify, and share the source code and builds.
- **Commercial use**: Any commercial use (including but not limited to: commercial software, paid services, enterprise use, or commercial derivative works) **requires prior written authorization from the author**. Unauthorized commercial use is prohibited.

**Authorization contact**: Reach out via TikTok **"Xi De Hu AI Programming"** (喜德胡AI编程) for commercial licensing inquiries.

### Third-Party Licenses

This project uses the following open-source components under their respective licenses:

| Component | License |
|-----------|---------|
| [Shizuku](https://github.com/RikkaApps/Shizuku) | Apache License 2.0 |
| [llama.cpp](https://github.com/ggerganov/llama.cpp) | MIT License |
| Other dependencies | See each component's notice |

### App Permissions

| Permission | Purpose |
|------------|---------|
| **Network** (INTERNET) | Call Zhipu AI and other cloud APIs |
| **Overlay** (SYSTEM_ALERT_WINDOW) | Show AI status floating window |
| **Microphone** (RECORD_AUDIO) | Voice input and wake word |
| **Foreground Service** | Keep AI service running in background |
| **Nearby devices** (NEARBY_WIFI_DEVICES) | Discover wireless debugging for Shizuku pairing |
| **Notifications** | Pairing and status notifications |
| **Ignore battery optimization** | Reduce background kill by system |
| **Camera** (CAMERA) | Optional, for extended features |

**Shizuku authorization**: Required for touch simulation and screenshots. No Root needed.

---

## ❤️ Follow Me

If you like this project, please give it a **Star** 🌟!

**🦄 TikTok: Xi De Hu AI Programming**

<img src="app/src/main/assets/sidhu.png" width="200" alt="Xi De Hu AI Programming">
