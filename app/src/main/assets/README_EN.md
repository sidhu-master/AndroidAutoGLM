# AutoGLM Android Assistant User Guide

Welcome to **AutoGLM Android Assistant**! This is an intelligent agent application based on a multimodal large model that helps you automate various tasks on your phone.

## 1. Quick Start

### Step 1: Enable Wireless Debugging and Pair Shizuku
This version no longer relies on Accessibility permission. It uses Shizuku + wireless debugging for automation and screenshots.
1. Open **Settings** → **Developer options** and enable **Wireless debugging**.
2. Return to the app → **Settings** → **Shizuku**, tap **Pair device**.
3. Open **Wireless debugging**, tap **Pair device with pairing code**, and enter the code shown in the app dialog.
4. After pairing, tap **Start Shizuku** and allow the permission request if prompted.
5. On Android 13+, allow the **Nearby devices** permission to auto-discover the port.

### Step 2: Grant Overlay Permission
Enable **Overlay permission** in system settings so the floating window can show status.

### Step 3: Get and Configure API Key
This app is based on the Zhipu AI large model capabilities and requires an API Key to use.

1.  **Get API Key**:
    *   Visit [Zhipu AI Open Platform - API Key Management](https://bigmodel.cn/usercenter/proj-mgmt/apikeys).
    *   Log in to your account (supports mobile number or WeChat scan).
    *   Click "Create API Key" on the page or copy an existing Key.

2.  **Enter App Settings**:
    *   Return to the AutoGLM App.
    *   Click the **Settings** icon in the upper right corner.
    *   Click **"Enter API Key"** or **"Edit API Key"**.
    *   Paste the copied API Key into the input box and save.

### Step 4: Start Using
1.  Enter your instruction in the dialog box on the home page, for example: "Help me check tomorrow's weather", "Open TikTok, search for Xi De Hu AI Programming, and follow".
2.  Click the **Send** button.
3.  The app will automatically jump to the desktop or the corresponding app and display the current execution status via a **Floating Window**.

---

## 2. Floating Window Status Description

During task execution, a floating window will appear in the lower right corner of the screen to display the AI's current status.

### Status Details

**1. AutoGLM Ready (Green)**
*   **Meaning**: Indicates currently idle, waiting for instructions.
*   **Action**: Click the **"Return to App"** button to go back to the main interface to enter new instructions.

**2. AutoGLM Running (Gray)**
*   **Meaning**: Indicates the AI is thinking or executing actions (e.g., "Thinking...", "Clicking Screen").
*   **Action**: **Do not manually operate the screen** to avoid interfering with the AI. If you need to forcibly interrupt, you can click the **"Stop"** button on the floating window.

**3. AutoGLM Error (Red)**
*   **Meaning**: Indicates a problem occurred during task execution (e.g., timeout, element not recognized).
*   **Action**: You can click **"Stop"** or **"Return to App"** to view specific error messages.

### Common Status Text
*   **Thinking...**: The model is planning the next step.
*   **Clicking Screen / Swiping Screen**: Executing specific touch operations.
*   **Waiting**: Waiting for the page to load.
*   **Stopped**: The task was manually canceled by the user or ended naturally.

---

## 3. FAQ
*   **Why no action is performed?** Make sure Wireless debugging is enabled and Shizuku is started and authorized.
*   **API Key Invalid?** Please confirm if there are extra spaces in the Key, or if the account balance is sufficient.

Enjoy using it!

---

## Follow Me

If you like my work, you can follow me on TikTok:

![Xi De Hu AI Programming](file:///android_asset/sidhu.png)
