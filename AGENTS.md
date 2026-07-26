# AGENTS.md — 模块所有权与协作边界（实际路径版）

源自 `docs/android_app/03_Agent开发任务分解与协作规范.md` §3.2，映射到本工程实际路径。

## 铁律
- `outputs/*.xlsx`、`../oversead_projects/`、`../docs/` 全程只读。
- 禁用 `fallbackToDestructiveMigration`。
- DeepSeek Key 不进入日志/备份/BuildConfig/DataStore/Room。
- 实战模式答案在场景完成前不得进入 UiState/语义树（sealed 类型隔离）。
- 搜索优先 `rg`；修改前保留他人未提交改动。

## 文件所有权

| 所有者 | 独占范围 |
|---|---|
| 主 Agent | 根 `build.gradle.kts`、`settings.gradle.kts`、`gradle/**`、`gradle.properties`、`buildSrc/**`、`:app/**`、顶层导航、顶层 Hilt、Release、契约目录、端到端集成测试 |
| 子 Agent A | `core/model` 非契约实现、`core/database/**`、`core/data` 非 API、`core/corpus` 非 API、`tools/corpus-packager/**` |
| 子 Agent B | `core/designsystem/**`、`feature/home/**`、`feature/vocabulary/**`、`feature/scenario/**` |
| 子 Agent C | `core/audio` 非 API、`core/network` 非 API、`feature/settings/**`、Key 安全实现 |

## 冻结契约路径（仅主 Agent 可改）
```
core/model/src/main/java/com/bess/salestrainer/core/model/contract/**
core/model/src/main/java/com/bess/salestrainer/core/model/fake/**   (Fake 由主 Agent 维护)
```

## 禁止并行修改的共享文件（仅主 Agent）
- `settings.gradle.kts`、根 `build.gradle.kts`、`gradle/libs.versions.toml`、`gradle.properties`、`buildSrc/**`
- `app/build.gradle.kts`、`app/src/main/AndroidManifest.xml`、`MainActivity.kt`、顶层 Navigation、顶层 Hilt Module、Release 签名/R8/backup/network-security 配置

## 构建
所有命令前缀：`JAVA_HOME="C:/Users/fengl/jdk-17" ./gradlew.bat`
- 构建：`./gradlew.bat :app:assembleDebug`
- 单元测试：JVM 模块 `./gradlew.bat :core:model:test`；Android 模块 `./gradlew.bat :app:testDebugUnitTest`

## 交接格式
完成任务或阻塞时按 03 文档 §5 输出 STATUS/TASK_ID/CHANGED/PUBLIC_INTERFACE_IMPACT/TESTS/KNOWN_RISKS/HANDOFF。
