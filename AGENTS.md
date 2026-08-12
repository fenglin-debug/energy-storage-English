# AGENTS.md — 海外储能英语训练 App 协作入口

本文件是 Android 工程的快速协作入口。完整产品、技术和任务基线见：

- `../docs/android_app/01_产品需求文档_PRD.md`
- `../docs/android_app/02_技术设计文档_TDD.md`
- `../docs/android_app/03_Agent开发任务分解与协作规范.md`

发生冲突时，优先级为：用户当次明确指令 > PRD > TDD > Agent 任务文档 > 本文件。

## 产品铁律

- App 运行时完全离线；不得引入联网请求、云端模型、账号、同步或远程日志。
- 不申请 `INTERNET`、`RECORD_AUDIO`、`POST_NOTIFICATIONS` 权限。
- 不实现录音、ASR、转写、AI 评分、DeepSeek 或自动口语打分。
- 客户、词汇和例句语音来自语料包中的预生成本地音频。
- 客户英文、关键词和参考答案默认隐藏；揭示前不得进入 Compose 可访问性语义树。
- 词汇和情景练习必须保存精确断点，进程终止或设备重启后可续接。
- 禁用 `fallbackToDestructiveMigration`；语料导入失败时保留当前语料及学习状态。
- `../outputs/**/*.xlsx`、`../oversead_projects/**`、`../docs/**` 在 App 实施阶段均为只读输入。
- 不把客户名、联系人、报价、内部路径、来源定位或原始 Excel 打进 App。

## 文件所有权

| 所有者 | 独占范围 |
|---|---|
| 主 Agent | 根 Gradle 文件、`gradle/**`、`buildSrc/**`、`:app/**`、顶层导航/Hilt、冻结契约、数据库版本协调、Release 与端到端集成 |
| 子 Agent A | `core/model` 非冻结实现、`core/database/**`、`core/data` 非 API、`core/corpus/**`、`tools/corpus-packager/**`、语料覆盖层 |
| 子 Agent B | `core/designsystem/**`、`feature/home/**`、`feature/vocabulary/**`、`feature/scenario/**` |
| 子 Agent C | `core/audio/**`、`feature/settings/**`、本地音频播放、离线语料选择/导入 UI、离线合规扫描 |

冻结契约路径仅主 Agent 可改：

```text
core/model/src/main/java/com/bess/salestrainer/core/model/contract/**
core/model/src/main/java/com/bess/salestrainer/core/model/fake/**
```

仅主 Agent 可修改的共享文件：

- `settings.gradle.kts`
- 根 `build.gradle.kts`
- `gradle/libs.versions.toml`
- `gradle.properties`
- `buildSrc/**`
- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `MainActivity.kt`
- 顶层 Navigation 与 Hilt Module
- Room 数据库版本号、迁移注册
- Release 签名、R8、备份与安全配置

## 开发顺序

1. 主 Agent 删除旧联网、AI、录音和 ASR 契约，冻结新接口。
2. Gate 通过后，子 Agent A/B/C 按独占目录并行。
3. 主 Agent 集成真实 Repository、首启语料、导航、迁移和 Release。
4. 完成离线、断点恢复、隐藏内容、语料升级及回归验收。

未经主 Agent 宣布契约冻结，不得并行实现依赖公共接口的功能。

## 构建与检查

PowerShell：

```powershell
$env:JAVA_HOME = 'C:\Users\fengl\jdk-17'
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :core:model:test
.\gradlew.bat :app:testDebugUnitTest
```

提交前额外检查：

```powershell
rg -n "DeepSeek|AiCoach|RECORD_AUDIO|INTERNET|POST_NOTIFICATIONS|SpeechRecognizer|MediaRecorder|Retrofit|OkHttp" .
```

允许命中的仅限迁移说明、负向测试或明确的删除清单。

## 交接格式

```text
STATUS: DONE | BLOCKED
TASK_ID:
CHANGED:
PUBLIC_INTERFACE_IMPACT: NONE | 具体说明
TESTS:
KNOWN_RISKS:
HANDOFF:
```

任何公共接口、共享 Gradle、Manifest 或数据库版本变更，都必须在 `PUBLIC_INTERFACE_IMPACT` 中列明，并由主 Agent 合入。
