# Release 发布流程

## 一次性准备

1. 在仓库外生成永久密钥，例如 `C:\Users\<user>\.android\bess-release.jks`，alias 固定为 `bess`。通过 `keytool -genkeypair -v -keystore <path> -alias bess -keyalg RSA -keysize 4096 -validity 10000` 交互式设置密码。
2. 将 keystore 和密码分别交由两名保管人保存，并制作至少两份离线备份。密钥、密码和真实路径不得提交到 Git、文档或聊天记录。
3. 使用 `keytool -list -v -keystore <path> -alias bess` 记录非秘密的证书 SHA-256 指纹。
4. 在 Android Developer Console 完成完整分发开发者验证，登记 `com.bess.salestrainer` 及正式签名证书。

## 每次发布

1. 在 `BessModuleConfig.kt` 中递增 `VERSION_CODE` 并更新 `VERSION_NAME`；禁止复用或降低 versionCode。
2. 为当前 PowerShell 会话配置 `BESS_KEYSTORE`、`BESS_STORE_PASSWORD`、`BESS_KEY_ALIAS`、`BESS_KEY_PASSWORD`。不要把密码写入仓库脚本。
3. 运行 `tools\release\build-release.ps1`。脚本会构建签名 APK，并生成统一的分发目录、SHA-256、安装说明和更新说明。
4. 用 `apksigner verify --print-certs` 和 `aapt dump badging` 核对签名、包名、版本、非调试状态和 arm64 ABI。
5. 在旧正式版上直接覆盖安装，验证学习记录、断点、语料和播放功能。
6. 更新发布台账后再通过内部可信渠道分发，禁止发送 Debug APK 或 unsigned APK。

密钥丢失或更换后，已有正式安装无法继续覆盖升级。遇到线上问题必须发布更高 versionCode 的修复版，不能要求用户降级或先卸载。
