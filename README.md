# BESS 储能英语训练 · 安卓基础版

离线英语训练 App，面向海外储能销售场景。本仓库是 **Android 基础版** 源码，当前版本 **0.4.1**（`versionCode` 6），包名 `com.bess.salestrainer`。

应用运行时完全离线：无账号、无云同步、无远程日志。客户、词汇和例句语音来自内置语料包中的预生成本地音频。

## 要求

- Android 8.0+（API 26），仅 `arm64-v8a`
- JDK 17
- Android SDK（Compile/Target SDK 36）

## 构建

```powershell
$env:JAVA_HOME = '<JDK 17 安装目录>'
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :core:model:test
.\gradlew.bat :app:testDebugUnitTest
```

Debug 包使用假数据仓库，applicationId 带 `.debug` 后缀。正式签名包不要用 Debug 产物分发。

正式发布需要本机密钥和环境变量（不要把密钥或密码提交进 Git）：

```text
BESS_KEYSTORE
BESS_STORE_PASSWORD
BESS_KEY_ALIAS
BESS_KEY_PASSWORD
```

然后运行 `tools\release\build-release.ps1`。流程见 `docs/release/RELEASE_PROCESS.md`。

## 安装

用户安装与升级说明见：

- `docs/安卓版使用必看.txt`
- `docs/release/INSTALL.md`
- `docs/release/RELEASE_NOTES.md`

升级时直接覆盖安装，不要卸载旧版或清除应用数据。

## 不包含

- 签名密钥、`local.properties`、APK
- 会员版、鸿蒙、iOS、Windows 工程
