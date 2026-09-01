# Vokie Phone for Android

[English](README.md)

Vokie Phone 是 Android companion app，用于通过局域网将手机连接到 Vokie
桌面应用。本项目是独立的 Gradle Android 应用。更多信息请访问
[vokie.com](https://vokie.com)。

## 构建

使用 Android Studio 打开本仓库，或在仓库根目录执行 Gradle 任务。需要使用
Android Gradle Plugin 8.7.3 支持的 JDK：

```bash
./gradlew :app:test
./gradlew :app:assembleDebug
```

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。当前 Android
应用版本为 `0.3.11`（`versionCode 14`）。

发布签名时，将 `keystore.properties.example` 复制为 `keystore.properties`，并填写
本地 keystore 凭据。凭据文件和 keystore 目录已加入 Git 忽略列表。

## GitHub Releases

推送例如 `v0.3.5` 的版本标签会触发 `.github/workflows/android.yml`。Pull Request
会运行单元测试；版本标签会运行测试、构建签名版本、生成发布清单并发布 GitHub Release。
发布前需要配置以下仓库 Secrets：

`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、
`ANDROID_KEY_PASSWORD`。

## 与 Vokie PC 的关系

Android App 使用 Vokie PC 已实现的手机配对和录音协议。协议变更需要在两个仓库之间协调，
并记录到 PC 仓库的手机集成规范中。

## 项目变化

Android 手机 Wi-Fi 服务现在也以独立 Vokie Plugin 的形式提供，目录为
[`plugins/vokie-phone`](plugins/vokie-phone)。Plugin Worker 负责手机 TCP 服务器、二维码
配对、认证、录音模式映射和 PCM 音频转发；Android App 协议保持不变。当前 Plugin 支持
Wi-Fi，BLE 不在本项目范围内。

这是 Vokie 官方支持的集成方式。Vokie 提供 Plugin 运行时、生命周期、会话协议、音频管线
和 UI bridge；手机相关实现保留在本仓库中，可以独立于 Vokie PC 演进。Plugin 也可以在遵守
标准生命周期和 WebSocket 合约的前提下，自由实现更多设备行为、命令、传输方式和不透明的
`extensions`。

当前 Plugin 包版本为 `0.1.1`。Plugin UI 使用 Android App 的 launcher 图标，并通过 Plugin
state 的 `extensions.pairingInvite` 显示实时配对二维码。

## Plugin 生成方式

使用 `vokie-plugin-creator` skill（Vokie Plugin Create）生成或更新 Plugin。在 Codex 中直接
说明设备类型和输出目录，例如：

```text
使用 vokie-plugin-creator skill，为 <device> 创建一个使用 Wi-Fi 的 Vokie Plugin，
并将生成的包放到 plugins/<plugin-id>。
```

该 skill 会读取 Plugin 协议规范，生成 manifest、Worker、自定义 UI、资源和针对性测试，并
执行包校验。不要把现有的手机 Plugin 目录当作其他设备的模板。

Plugin 包必须自包含，至少包括：

```text
plugins/vokie-phone/
├── vokie.plugin.json       # Plugin ID、版本和能力声明
├── worker/index.mjs        # Host WebSocket Worker
├── ui/index.html           # Plugin 页面
└── assets/                 # SDK、二维码编码器和图标
```

manifest 声明传输方式、能力、UI 入口、Worker 入口和包内图标路径。手机协议实现全部放在
Plugin 内部。验证命令：

```bash
node --test plugins/vokie-phone/test/plugin.test.mjs
node --check plugins/vokie-phone/worker/index.mjs
```

更多协议细节和限制见 [`plugins/vokie-phone/README.md`](plugins/vokie-phone/README.md) 和
[`Testing/android-phone-wifi-plugin.md`](Testing/android-phone-wifi-plugin.md)。

## 将 Plugin 加载到 Vokie

1. 准备完整的 `plugins/vokie-phone` 目录，保持 manifest、Worker、UI 和资源的相对路径。
2. 在 Vokie PC 的 Plugin 设置中选择 **Install Plugin**，然后选择该目录；也可以复制到
   Vokie PC 配置的本地 Plugin 目录。
3. Vokie 校验 `vokie.plugin.json` 和资源路径，并将 Plugin 加入可用列表。
4. 在列表中启用或重载 **Vokie Phone Wi-Fi**，Vokie PC 会自动启动 Worker 并提供认证连接。
5. 打开 Plugin 详情页使用内置 UI。修改文件后重新安装或重载，以刷新已安装副本。

Plugin 加载完成后，Worker 会按照 Vokie Plugin 标准生命周期运行，UI 会显示手机配对二维码。
