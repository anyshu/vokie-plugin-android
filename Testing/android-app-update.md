# Android 应用升级来源规范

## 目标

应用升级检查支持两个 manifest 来源，并在主来源不可用时自动切换到备用来源。

版本升级测试应使用相同签名的 `0.3.7`（`versionCode 10`）作为基线，验证升级到
`0.3.8`（`versionCode 11`）。测试 manifest 的 `versionCode`、`versionName`、
`downloadUrl` 和 `sha256` 必须与实际 APK 一致。

## 来源顺序

1. 主来源：`https://xiguasay.echooai.com/vokie/android/latest.json`
2. 备用来源：GitHub Releases 的 `latest.json`

地址分别由 `UPDATE_MANIFEST_URL` 和 `UPDATE_MANIFEST_BACKUP_URL` 注入
`BuildConfig`。升级检查按上述顺序请求；HTTP 非 200、连接/读取超时、响应过大或
manifest 校验失败，都会继续尝试下一个来源。两个来源都失败时，才显示检查失败。

两个来源返回的 manifest 必须使用相同格式，并提供相同版本的 `versionCode`、
`versionName`、`downloadUrl`、`sha256`、`forceUpdate` 和 `releaseNotes` 字段。
APK 下载地址仍由 manifest 的 `downloadUrl` 指定，下载后会校验 SHA-256、包名、版本号
和签名。
