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
*   **No action performed?** Check if Accessibility Service was killed by the system. Re-enable it.
*   **API Key Invalid?** Check for extra spaces or account balance.

---

## ❤️ Follow Me

If you like this project, please give it a **Star** 🌟!

**🦄 TikTok: Xi De Hu AI Programming**

<img src="app/src/main/assets/sidhu.png" width="200" alt="Xi De Hu AI Programming">
