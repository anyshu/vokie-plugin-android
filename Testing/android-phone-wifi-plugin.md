# Android Phone Wi-Fi Plugin 方案

## 范围

`plugins/vokie-phone` 是基于 Vokie Plugin Creator 规范生成的独立 Plugin 包。它只实现
Android Phone 的 Wi-Fi 传输，不包含 BLE。Worker 接收现有 Phone v2 TCP 长度帧协议，完成
配对认证、控制报文解析和 PCM 分片重组，再通过 Vokie Plugin WebSocket 发送标准会话。

## 录音模式映射

| Android `recordingMode` | Plugin `session_start.mode` |
| --- | --- |
| `ptt` | `ptt` |
| `handsfree` | `handsfree-ptt` |
| `long` | `recording` |

音频统一声明为 16 kHz、单声道、`pcm_s16le`，并在收到 `session_accepted` 后发送。

## 配对和当前限制

Worker 启动后生成与 Android `MainActivity` 兼容的 `vokie://pair?...` URI，并通过
Plugin state 的 `extensions.pairingInvite` 字段交给自带 UI；UI 使用自带的浏览器 QR 编码器显示二维码。
手机 APP 扫描该二维码后直接连接 Worker 的动态 TCP 端口。

Worker 入口必须带 Node shebang 并具有可执行权限，因为 PC Supervisor 会直接 spawn
manifest 中的 `worker/index.mjs` 路径。

插件主页包含连接状态、已连接设备、二维码、扫码步骤和支持功能说明。Host 需要在
`ExternalDeviceState` 和 state merge 中保留不透明的 `extensions`，UI SDK 同时兼容 Host 将响应
字段放在顶层或 `payload` 中的两种格式；否则页面只能显示静态标题，无法显示二维码。
由于当前 Host bridge 没有 state push 订阅，主页打开期间每秒轮询一次 `get_state`，确保
Worker 从 `starting` 切换到 `ready` 后二维码能及时刷新。

Vokie Plugin 标准协议当前没有“请求宿主配对确认”事件，也没有“宿主录音结束通知
Plugin”的事件。因此：

- 首次配对默认自动批准（二维码由本机 Plugin UI 展示）；如部署环境要求人工批准，设置
  `VOKIE_PHONE_REQUIRE_APPROVAL=1`；
- 已保存 Phone token 的后续认证可正常工作；
- PC 主动结束录音时，Worker 无法像内置 Phone Server 一样向手机发送
  `recording_stopped`，需要后续扩展 Host API；
- `open_vokie` 和 `forget_device` 在 Phone TCP 层可响应，但标准 Plugin Host 没有对应
  宿主命令，前者不会真正唤起窗口，后者只会撤销 Worker 本地凭据。

当前包版本为 `0.1.1`。升级后需要在 Vokie Plugin 设置中重新安装该目录，使已安装副本
刷新 UI、SDK 和 Worker 文件。

## 验证

```bash
node --test plugins/vokie-phone/test/plugin.test.mjs
node --check plugins/vokie-phone/worker/index.mjs
node --check plugins/vokie-phone/worker/phone-server.mjs
node --check plugins/vokie-phone/worker/frame.mjs
node --check plugins/vokie-phone/worker/pairing.mjs
```

将包复制到 `xiguashuo-pc` 后，使用 PC Plugin 包校验器检查 `vokie.plugin.json`、Worker、
UI 和 icon 路径；再通过 `VOKIE_PLUGIN_WS_URL`、`VOKIE_PLUGIN_TOKEN` 启动 Worker 做握手测试。
