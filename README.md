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
- **手机地址日志读取** — 同局域网访问手机 `http://IP:8976/` 即可查看当前执行日志，也支持 JSON 接口
- **远程配对中继** — 通过浏览器完成 ADB 无线配对，基于 channel 的 relay 服务
- **自定义 API 端点** — 支持任意 OpenAI 兼容的 API 服务

### 截图

<!-- TODO: 添加截图 -->

### 前置要求

- Android 9+（API 28+）
- OpenAI 兼容的 API Key

### 构建

1. 安装 Java 17
2. 安装 Android SDK（如 Gradle 无法自动发现，可创建 `local.properties` 并写入 `sdk.dir=/path/to/Android/sdk`）
3. 复制环境配置文件：
   ```bash
   cp .env.example .env
   ```
4. 编辑 `.env`，按需填写 `DEFAULT_API_ENDPOINT`、`DEFAULT_API_KEY`、`DEFAULT_RELAY_URL`；如果 relay 使用 `http://`，同时把主机名或 IP 填入 `CLEARTEXT_DOMAINS`
5. 构建 debug APK：
   ```bash
   ./gradlew assembleDebug
   ```
   说明：Gradle 默认会使用项目内的 `.gradle-user-home/` 作为缓存目录
   产物路径：`app/build/outputs/apk/debug/app-debug.apk`

### 中继服务器

`server/` 目录包含 Node.js 中继服务器，`worker/` 目录包含 Cloudflare Worker 版本。中继服务器用于浏览器端 ADB 无线配对。

本地启动 Node.js 中继服务器：

```bash
cd server
node server.js
```

### 读取手机执行日志

App 启动后会在手机上自动启动一个只读日志 HTTP 服务。在同一局域网中，可在设置页查看当前可访问地址，通常形如：

```text
http://192.168.x.x:8976/
```

可用接口：

- `/` 浏览器页面，自动刷新查看执行日志
- `/api/logs` JSON 日志快照，支持 `limit`、`sessionId`、`includeImages=1`
- `/api/sessions` 会话列表
- `/health` 服务健康状态

说明：该服务返回当前 App 进程内的执行日志，因此 App 运行期间可访问。

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
- **Read logs via phone address** — visit `http://PHONE_IP:8976/` on the same LAN to inspect execution logs, with JSON endpoints available
- **Remote pairing relay** — complete ADB wireless pairing via browser using a channel-based relay service
- **Custom API endpoints** — works with any OpenAI-compatible API service

### Screenshots

<!-- TODO: add screenshots -->

### Prerequisites

- Android 9+ (API 28+)
- OpenAI-compatible API key

### Build

1. Install Java 17
2. Install Android SDK. If Gradle cannot locate it automatically, create `local.properties` with `sdk.dir=/path/to/Android/sdk`
3. Copy the environment config:
   ```bash
   cp .env.example .env
   ```
4. Edit `.env` and fill in `DEFAULT_API_ENDPOINT`, `DEFAULT_API_KEY`, and `DEFAULT_RELAY_URL` as needed. If the relay uses `http://`, also add its hostname or IP to `CLEARTEXT_DOMAINS`
5. Build the debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
   Gradle uses the project-local `.gradle-user-home/` directory for its cache by default.
   Output: `app/build/outputs/apk/debug/app-debug.apk`

### Relay Server

The `server/` directory contains a Node.js relay server and `worker/` contains a Cloudflare Worker variant. The relay server facilitates ADB wireless pairing from a browser.

Run the local Node.js relay server:

```bash
cd server
node server.js
```

### Read Execution Logs From The Phone

When the app starts, it also starts a read-only HTTP log server on the phone. Open the address shown in Settings from another device on the same LAN, usually:

```text
http://192.168.x.x:8976/
```

Available endpoints:

- `/` browser view with auto-refresh
- `/api/logs` JSON snapshot, supports `limit`, `sessionId`, and `includeImages=1`
- `/api/sessions` session list
- `/health` health status

The log server exposes in-memory execution logs, so it is available while the app process is running.

### License

[MIT](LICENSE)
