# MxGram

> 一个基于 LSPosed modern API 100 的 Telegram Android 模块。

## 功能

| 功能                          | 说明                                                   |
| ----------------------------- | ------------------------------------------------------ |
| 🚫 禁用下拉跳转               | 在频道页下滑时不再跳转到下一个频道                     |
| 🚫 禁用双击 reaction          | 双击消息不再触发快速 reaction                          |
| 🚫 禁用 greeting sticker 发送 | 点击问候贴纸不再直接发送，仅保留展示                   |
| ➕ 消息 `+1` 重发             | 在消息菜单中新增 `+1` 选项，快速转发或按原回复关系重发 |

### `+1` 详细说明

当当前会话允许发消息时，长按消息弹出的菜单中会多一个 **`+1`** 按钮：

- **点按**：将消息以「带来源信息的转发」形式重新发送到当前会话
- **长按**（仅当该消息是回复消息时）：将消息内容以「回复同一条消息」的方式再发一次（不带转发来源信息）

## 作用域

模块默认只作用于官方 Telegram 包名：

- `org.telegram.messenger`

如需支持其他 Telegram 变体，可修改：

- `app/src/main/resources/META-INF/xposed/scope.list`
- `TelegramHooksModule.kt` 中的 `TARGET_PACKAGE` 常量

## 技术栈

- **LSPosed** — modern API 100
- **Kotlin** — AGP 9 内置支持，目标 Java 17
- **Nix** — Flake + direnv 提供可重现的开发环境

## 快速上手

### 1. 构建

```bash
# 方式一：使用 direnv（推荐）
direnv allow
./gradlew assembleDebug

# 方式二：不进入 shell
nix develop -c ./gradlew assembleDebug
```

产物路径：`app/build/outputs/apk/debug/app-debug.apk`

### 2. 安装

1. 将 APK 安装到设备
2. 在 LSPosed 中启用 MxGram 模块
3. 将作用域勾选到 Telegram（`org.telegram.messenger`）
4. 重启 Telegram 进程

## 项目结构

```text
.
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/dev/xyenon/mxgram/
│       │   ├── TelegramHooksModule.kt       # 主 hook 入口
│       │   ├── PlusOneForwarder.kt          # +1 转发逻辑
│       │   └── *Hooker.kt                   # 各 hook 实现
│       ├── res/values/strings.xml
│       └── resources/META-INF/xposed/       # Xposed 元数据
│           ├── java_init.list
│           ├── module.prop
│           └── scope.list
├── flake.nix
├── treefmt.nix
├── .envrc
├── gradlew
└── README.md
```

## 实现细节

模块以「禁用 Telegram 原生行为」为目标，而非重新实现手势逻辑。

### Hook 点一览

| Hook 位置                             | 目的                       | 策略                             |
| ------------------------------------- | -------------------------- | -------------------------------- |
| `ChatActivity.animateToNextChat()`    | 禁用下拉跳转               | 直接短路                         |
| `ChatPullingDownDrawable` update 方法 | 兜底清除跳转目标           | 清空 nextChat/nextTopic          |
| `ChatActivity.createView`             | 禁用双击 reaction          | 替换监听，禁用 `hasDoubleTap()`  |
| `ChatActivity.selectReaction`         | 双保险拦截双击             | `fromDoubleTap` 为 `true` 时拦截 |
| `ChatGreetingsView.setListener`       | 禁用 greeting sticker 发送 | 清空回调                         |
| `ChatActivity.fillMessageMenu`        | 插入 `+1` 菜单项           | 在菜单列表追加自定义选项         |
| `ChatActivity.processSelectedOption`  | 执行 `+1` 转发             | 调用内部 `forwardMessages()`     |
| `ChatActivity.createMenu`             | 为 `+1` 追加长按逻辑       | 回复消息时保留回复关系重发       |

主实现文件：`app/src/main/kotlin/dev/xyenon/mxgram/TelegramHooksModule.kt`

> 若 Telegram 升级后 hook 失效，优先检查以下类：
> `ChatActivity`、`ChatPullingDownDrawable`、`ChatGreetingsView`、`ChatActivity.selectReaction`

## 依赖说明

Gradle 通过 JitPack 引入 API 100 对应工件：

```text
io.github.libxposed:api:-100-ge2588ee-22@aar
```

使用 `@aar` 是因为该 JitPack 工件的 POM 元数据版本不规范，按普通 Maven 坐标解析会触发版本不一致错误。

## 开发环境

### direnv（推荐）

```bash
direnv allow
```

进入目录后自动加载。提供 JDK 21、Android SDK platform 35、build-tools 35/36。

> build-tools 36 是必需的：AGP 构建时会请求该版本，若不预装会向只读 Nix Store 写入并失败。

### 手动进入 nix shell

```bash
nix develop
```

### 格式化与检查

```bash
nix fmt          # 格式化（Nix/Java/Kotlin/XML/Markdown）
nix flake check  # CI 友好检查
```

## Xposed 元数据

- `minApiVersion=100`
- `targetApiVersion=100`
- `staticScope=true`

入口文件：`app/src/main/resources/META-INF/xposed/`
