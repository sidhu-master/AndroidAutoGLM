我已详细分析了现有项目结构，并为您制定了开发 `AutoInput` 代理模块的完整方案。

由于 `AutoInput` 作为一个独立的通用能力（模拟软键盘输入），我们将其设计为一个独立的 Android Library 模块，方便未来迁移或独立维护，符合您提到的“封装成一个新的 model”的构想。

### 1. 模块化架构设计

我们将创建一个名为 `:autoinput` 的新模块，结构如下：

```text
MyAutoGLM/
├── app/ (现有应用)
│   └── build.gradle.kts (引用 :autoinput)
├── autoinput/ (新模块)
│   ├── build.gradle.kts
│   └── src/main/java/com/sidhu/autoinput/
│       ├── KeyboardAgent.kt      // 核心代理类
│       ├── KeyboardLayout.kt     // 键盘布局映射 (QWERTY坐标定义)
│       └── PinyinConverter.kt    // 汉字转拼音工具
└── settings.gradle.kts (包含 :autoinput)
```

### 2. 核心技术方案

针对“中文全键盘”模拟点击的需求，我们将采用以下策略：

* **汉字转拼音**: 引入轻量级库 `TinyPinyin`，将目标中文字符串（如“你好”）转换为拼音（"nihao"）。

* **坐标映射**:

   **使用截屏功能，识别出键盘的位置，并标注出按键中心坐标。**

  <br />

* **模拟点击**:

  * 利用 `AccessibilityService.dispatchGesture` 执行点击。

  * **选词策略**: 输入拼音后，使用文字的图片与截屏上候选词的对比进行候选词选择

### 3. 实施步骤

#### Phase 1: 基础设施搭建

1. 创建 `autoinput` 模块及其 `build.gradle.kts`，添加 `com.github.promeg:tinypinyin:2.0.3` 依赖。
2. 在 `settings.gradle.kts` 中注册新模块。
3. 在 `app/build.gradle.kts` 中添加对 `:autoinput` 的依赖。

#### Phase 2: 核心逻辑实现 (AutoInput 模块)

1. **`KeyboardLayout`**: 定义一个标准 26 键布局的相对坐标系统（0.0 - 1.0）。
2. **`KeyboardAgent`**: 实现 `type(text: String, service: AccessibilityService)` 方法。

   * 逻辑：解析文本 -> 转拼音 -> 计算坐标 -> 执行点击 -> 点击空格上屏。

#### Phase 3: 集成与兜底 (App 模块)

1. 修改 `TextInputHandler.kt`。
2. 在现有的 `ACTION_SET_TEXT` (直接赋值) 和 `ACTION_PASTE` (粘贴) 失败后，作为 **第三级兜底方案** 调用 `KeyboardAgent` 进行模拟输入。

此方案无需修改现有 AI 模型逻辑，纯本地运行，速度快且稳定。您是否同意开始执行？
