# Self Control

> [English](#english) | [中文](#中文)

---

## 中文

AI 驱动的 Android 手机自动化工具，通过语音指令控制手机，无需 root，无需电脑。

### 功能特性

- **语音驱动** — 说出你想做的事，AI 自动操作手机
- **设备端 ADB** — 基于 libadb-android，通过 localhost 无线调试直接在手机上执行 ADB 命令，无需 root 或 PC
- **17 种工具调用** — 点击、滑动、输入、截图、启动应用、返回、等待等，使用 OpenAI 兼容的 function calling API
- **九宫格点击系统** — 屏幕覆盖 9 区域网格，实现精准定位点击
- **上下文压缩** — 长对话自动压缩（30 条消息触发），保持会话连贯
- **应用技能学习** — SkillStore 按应用记录操作技巧，越用越聪明
- **悬浮气泡** — 始终置顶的悬浮入口，随时唤起
- **远程配对中继** — 通过浏览器完成 ADB 无线配对，基于 channel 的 relay 服务
- **自定义 API 端点** — 支持任意 OpenAI 兼容的 API 服务

### 截图

<!-- TODO: 添加截图 -->

### 前置要求

- Android 9+（API 28+）
- OpenAI 兼容的 API Key

### 构建

1. 安装 Java 17
2. 复制环境配置文件：
   ```bash
   cp .env.example .env
   ```
3. 编辑 `.env`，填入 relay server URL 等配置
4. 构建 debug APK：
   ```bash
   JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
   ```
   产物路径：`app/build/outputs/apk/debug/app-debug.apk`

### 中继服务器

`server/` 目录包含 Node.js 中继服务器，`worker/` 目录包含 Cloudflare Worker 版本。中继服务器用于浏览器端 ADB 无线配对。

### 许可证

[MIT](LICENSE)

---

## English

AI-driven Android phone automation — control your phone by voice, no root, no PC required.

### Features

- **Voice-driven** — describe what you want, AI operates your phone
- **On-device ADB** — powered by libadb-android, executes ADB commands locally via localhost wireless debugging, no root or PC needed
- **17 tool calls** — tap, swipe, type, screenshot, launch app, back, wait, and more via OpenAI-compatible function calling API
- **Zone-based tap system** — 9-grid screen overlay for precise click targeting
- **Context compression** — auto-compresses long conversations (triggers at 30 messages) to maintain session coherence
- **Per-app skill learning** — SkillStore records per-app tips, gets smarter over time
- **Floating bubble** — always-on-top overlay for instant access
- **Remote pairing relay** — complete ADB wireless pairing via browser using a channel-based relay service
- **Custom API endpoints** — works with any OpenAI-compatible API service

### Screenshots

<!-- TODO: add screenshots -->

### Prerequisites

- Android 9+ (API 28+)
- OpenAI-compatible API key

### Build

1. Install Java 17
2. Copy the environment config:
   ```bash
   cp .env.example .env
   ```
3. Edit `.env` with your relay server URL and other settings
4. Build the debug APK:
   ```bash
   JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug
   ```
   Output: `app/build/outputs/apk/debug/app-debug.apk`

### Relay Server

The `server/` directory contains a Node.js relay server and `worker/` contains a Cloudflare Worker variant. The relay server facilitates ADB wireless pairing from a browser.

### License

[MIT](LICENSE)
