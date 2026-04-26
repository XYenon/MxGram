# MxGram

MxGram 是一个针对 Telegram Android 的 LSPosed modern API 100 模块。

当前模块只做四件事：

- 禁用频道页下滑后跳转到下一个频道
- 禁用双击消息直接发送 reaction
- 禁用点击 greeting sticker 直接发送贴纸
- 在消息菜单中增加 `+1`：将消息“带引用转发”到当前会话

## 当前行为

模块启用后：

- 在频道页面继续下滑，不会再触发 Telegram 原生的“跳到下一个频道”
- 双击消息，不会再触发 Telegram 原生的快速 reaction
- 在空白聊天页或 greeting 场景里点击问候贴纸，不会再直接发送 sticker
- 当当前会话允许发消息时，长按/点按消息弹出的菜单里会多一个 `+1`，点它会把这条消息以“转发（带来源信息）”的形式重新发到当前会话

模块默认只作用于官方 Telegram 包名：`org.telegram.messenger`

## 实现思路

当前实现以“禁用 Telegram 原有逻辑”为目标，而不是重新实现手势。

主要 hook 点：

- `org.telegram.ui.ChatActivity.animateToNextChat()`
  - 直接短路，阻止真正发生频道切换
- `org.telegram.ui.ChatPullingDownDrawable.updateDialog()`
- `org.telegram.ui.ChatPullingDownDrawable.updateTopic()`
  - 在 Telegram 计算“下一个目标频道/话题”后，将目标清空，作为兜底
- `org.telegram.ui.ChatActivity.createView(Context)`
  - 在消息列表创建后，替换 `RecyclerListView.OnItemClickListenerExtended`
  - 让 `hasDoubleTap()` 恒为 `false`，从事件入口禁用双击 reaction
- `org.telegram.ui.Components.ChatGreetingsView.setListener(Listener)`
  - 直接清空 greeting sticker 的发送回调，让点击只保留展示，不再发 sticker
- `org.telegram.ui.ChatActivity.selectReaction(..., fromDoubleTap, ...)`
  - 当 `fromDoubleTap == true` 时直接拦截，作为双保险

主实现文件：`app/src/main/java/dev/xyenon/mxgram/TelegramHooksModule.java`

新增 `+1` 的 hook 点：

- `org.telegram.ui.ChatActivity.fillMessageMenu(...)`
  - 在 Telegram 生成消息菜单选项列表后，插入一个自定义的 `+1`
- `org.telegram.ui.ChatActivity.processSelectedOption(int)`
  - 拦截自定义选项并调用 Telegram 内部的 `forwardMessages(...)`，把选中消息转发到当前会话

## 项目结构

```text
.
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/dev/xyenon/mxgram/TelegramHooksModule.java
│       ├── res/values/strings.xml
│       └── resources/META-INF/xposed/
│           ├── java_init.list
│           ├── module.prop
│           └── scope.list
├── flake.nix
├── treefmt.nix
├── .envrc
├── gradlew
└── README.md
```

## 技术栈

- Android Gradle Plugin
- Java 17 源码目标
- LSPosed modern API 100
- Nix Flake + direnv 开发环境

## 开发环境

推荐直接使用仓库自带的 Nix Flake。

### 使用 direnv

首次进入仓库目录后执行：

```bash
direnv allow
```

之后进入目录会自动加载开发环境。

### 手动进入 nix shell

```bash
nix develop
```

开发环境会提供：

- JDK 21
- Android SDK platform 35
- Android build-tools 35 / 36
- `JAVA_HOME`
- `ANDROID_HOME`
- `ANDROID_SDK_ROOT`

之所以同时带上 build-tools 36，是因为当前 Android Gradle Plugin 在构建时会请求该版本；如果不预装，Gradle 会尝试向只读的 Nix Store 写入 SDK 并失败。

## 构建

进入开发环境后执行：

```bash
./gradlew assembleDebug
```

产物位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

如果不想手动进入 shell，也可以直接这样构建：

```bash
nix develop -c ./gradlew assembleDebug
```

## 代码格式化 / Lint

本项目使用 treefmt-nix 统一管理格式化与部分 lint（Nix/Java/Kotlin/XML/Markdown 等）。

格式化：

```bash
nix fmt
```

检查（CI 友好）：

```bash
nix flake check
```

## 安装与使用

1. 构建出 `app-debug.apk`
2. 安装到设备
3. 在 LSPosed 中启用模块
4. 将作用域勾选到 `org.telegram.messenger`
5. 重启 Telegram 进程

## Xposed 元数据

模块相关入口位于：

- `app/src/main/resources/META-INF/xposed/java_init.list`
- `app/src/main/resources/META-INF/xposed/module.prop`
- `app/src/main/resources/META-INF/xposed/scope.list`

当前声明为：

- `minApiVersion=100`
- `targetApiVersion=100`
- `staticScope=true`

## 依赖说明

Gradle 通过 JitPack 引入 API 100 对应工件：

```text
io.github.libxposed:api:-100-ge2588ee-22@aar
```

这里显式使用 `@aar`，是因为该 API 100 工件在 JitPack 上的 POM 元数据版本并不规范，直接按普通 Maven 坐标解析时会触发版本不一致错误。

## 已验证项

已验证以下流程可用：

- `nix develop`
- `direnv allow`
- `direnv exec . bash -lc 'echo $JAVA_HOME'`
- `nix develop -c ./gradlew assembleDebug`
- `nix fmt`
- `nix flake check`

## 后续扩展

如果要支持更多 Telegram 变体，可以修改：

- `app/src/main/resources/META-INF/xposed/scope.list`
- `TelegramHooksModule.java` 中的 `TARGET_PACKAGE`

如果 Telegram 升级后导致 hook 失效，优先重新检查以下类：

- `org.telegram.ui.ChatActivity`
- `org.telegram.ui.ChatPullingDownDrawable`
- `org.telegram.ui.Components.ChatGreetingsView`
- `org.telegram.ui.Components.RecyclerListView`
