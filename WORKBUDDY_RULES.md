# OalendarBridge — WorkBuddy 开发规则

> 每个新的 WorkBuddy 对话 / 开发任务开始时都先阅读本文件。  
> 项目背景、稳定行为和 OPPO / ColorOS 实机结论，以 `PROJECT_HANDOFF_OalendarBridge.md` 为准。

## 1. 新对话启动规则

先阅读：

1. `WORKBUDDY_RULES.md`
2. `PROJECT_HANDOFF_OalendarBridge.md`

如果本次只是阅读、分析、检查或解释：
- 不需要创建 branch。
- 不修改任何文件。
- 发现问题只报告，不自行修复。

如果本次会修改代码、配置或资源：
1. 检查 Git 状态。
2. 确认 `main` 工作区 clean。
3. 确认稳定 tag `v1.0-stable-coloros` 存在。
4. 不得直接在 `main` 上开发。
5. 从当前 `main` 新建 feature / fix branch，或独立 worktree。
6. 创建后先不要改代码。
7. 先向用户报告方案，等待用户确认后再修改。

一个“新对话”不一定等于一个新 branch；只有需要修改项目时才创建 branch / worktree。

## 2. 修改前必须报告

开始修改前必须先告诉用户：

- 当前 branch / worktree；
- Git 状态是否 clean；
- 准备修改哪些文件；
- 为什么必须修改这些文件；
- 实现方案；
- 是否会触及已经通过 OPPO / ColorOS 真机验证的稳定核心；
- 哪些内容修改后仍需要 OPPO 真机回归。

未经用户确认，不要开始修改。

## 3. v1 稳定核心：未经明确授权不得修改或替换

### Local → DAVx⁵ 迁移

必须保持：

`Local Event → INSERT 新 DAVx⁵ Event → 复制字段 / Reminders / Attendees → 成功后删除 Local 源 Event`

必须保持 clone-then-delete。

禁止：
- 通过修改已有 Local Event 的 `CALENDAR_ID` 搬移；
- 复制 `_SYNC_ID`；
- 复制 `DIRTY`；
- 复制 sync-adapter / server-owned identity 字段。

### OPPO fingerprint / DIRTY 修复

如果 managed DAVx⁵ event 内容变化，且：
- `DIRTY == 0`
- event 未删除

则通过普通 `ContentResolver.update()` 对 TITLE 或其他安全字段做 same-value update，让 CalendarProvider 自己完成 dirty bookkeeping。

**禁止直接设置 `DIRTY = 1`。**

未经授权，不得重写 fingerprint / snapshot 机制或静默扩大 fingerprint 字段范围。

### 自动同步 ON / OFF

OFF 不是“暂停后补同步”。

OFF 期间发生的：
- Local 新建事件；
- managed DAVx⁵ event 修改；

必须永久忽略。

重新 ON 时必须重新建立 baseline / snapshot，不得 backfill OFF 期间变化。

ON → OFF 时现有“关闭前最后一次检查”的调用顺序不得随意改变。

### JobScheduler

稳定架构：

`JobScheduler + CalendarProvider Events content trigger`

未经授权不得替换为：
- WorkManager；
- permanent foreground service；
- permanent notification；
- 自定义轮询。

### 重启 / App 升级

`BOOT_COMPLETED`：
- 自动同步必须 OFF；
- JobScheduler job 必须取消 / 不恢复。

`MY_PACKAGE_REPLACED`：
- 与 reboot 不同；
- persisted sync state 仍为 ON 时可以恢复 scheduling。

### 删除

DAVx⁵ managed event 删除交给 CalendarProvider / DAVx⁵ 原生处理。

未经新的真机 bug 证明必要，不得添加自定义 deletion synchronization。

### Calendar 身份

不得硬编码 Calendar ID。

身份解析依赖：
- `calendarId`
- `accountType`
- `accountName`
- `displayName`

原 ID 失效时，只能在完整 identity 唯一匹配时重新绑定。

找不到或存在歧义：
- 不猜测；
- fail closed；
- 关闭自动同步；
- 要求用户重新选择。

Source：`accountType == LOCAL`

Target：`accountType == bitfire.at.davdroid`，并满足当前源码定义的可写权限。

同步 ON 时不得更改 source / target calendar。

## 4. 高风险文件

- `SyncEngine.kt`（最高风险）
- `CalendarSelectionStore.kt`
- `CalendarSyncScheduler.kt`
- `CalendarSyncJobService.kt`
- `BootReceiver.kt`
- `MainActivity.kt`
- `AndroidManifest.xml`

不得整体重写 `SyncEngine.kt`，也不得做与当前任务无关的重构。

如果任务能在不碰稳定核心的情况下完成，优先采用该方案。

## 5. 修改范围

只修改完成当前任务所必需的内容。

禁止顺便：
- 重构；
- 改命名；
- 格式化整个项目；
- 升级依赖；
- 替换架构；
- 修改无关文件；
- 因“最佳实践”改变已通过实机验证的行为。

发现无关问题时，只报告，不自行处理。

## 6. Git 规则

`main` 是稳定主干，不直接做新功能实验。

历史稳定锚点：

- tag：`v1.0-stable-coloros`
- commit：`061b00011d44fa818a1ed19b759f93876a0c0ae1`

该 tag 不得移动或重写。

新功能使用：
- `feature/<name>`

Bug 修复使用：
- `fix/<name>`

较大或高风险任务优先使用独立 worktree。

未经用户授权，不得：
- force push；
- rewrite history；
- 删除稳定 tag；
- reset / rebase `main` 到其他版本；
- 自动 merge 回 `main`；
- 删除用户已有 branch；
- 执行不可逆 Git 操作。

## 7. Build 要求

每次代码修改后至少运行：

```bash
./gradlew :app:assembleDebug
```

较大修改或稳定候选版本运行：

```bash
./gradlew clean :app:assembleDebug
```

Gradle 编译结果才是 Build 是否成功的依据。

Build 失败时先修复编译问题，不得把未通过编译的版本报告为完成。

## 8. Build 通过 ≠ OPPO 真机通过

涉及以下区域时，即使 Build 成功也必须明确标记“需要 OPPO / ColorOS 真机回归”：

- Local → DAVx⁵ migration
- fingerprint / DIRTY repair
- JobScheduler / background behavior
- ON / OFF baseline
- calendar identity
- boot behavior
- DAVx⁵ synchronization
- deletion behavior

不得声称未执行的真机测试已经通过。

## 9. ADB 安全

不要随意执行 `adb uninstall`。

卸载 / 重装可能清除：
- app preferences；
- ColorOS auto-start 权限；
- background running 权限；
- background popup 权限。

测试 APK 时优先保留现有 app data。

## 10. 每个任务完成后的报告

必须报告：

- 当前 branch / worktree；
- `git status`；
- 修改了哪些文件；
- 每个文件改了什么；
- 为什么这样改；
- 哪些稳定核心没有变化；
- `./gradlew :app:assembleDebug` 是否通过；
- 是否运行 clean build；
- 哪些行为仍需 OPPO / ColorOS 真机验证；
- 是否存在未提交改动；
- 是否进行了 commit / merge。

## 11. 合并回 main

功能完成后不得自动 merge。

合并前必须：
1. Build 通过；
2. 涉及核心行为时，由用户确认真机测试结果；
3. 用户明确同意 merge；
4. 才能合并回 `main`。

## 12. 标准流程

```text
读取 WORKBUDDY_RULES.md
        ↓
读取 PROJECT_HANDOFF_OalendarBridge.md
        ↓
检查 Git / main clean
        ↓
如果需要改代码：创建 feature / fix branch 或 worktree
        ↓
先分析需求
        ↓
报告计划修改文件和风险
        ↓
等待用户确认
        ↓
开始修改
        ↓
Gradle Build
        ↓
报告结果
        ↓
必要时 OPPO 真机测试
        ↓
用户确认后才允许 merge main
```

## 最重要的一句话

**不要为了代码看起来更标准、更简洁或更现代，而改变已经经过 OPPO / ColorOS 真机验证的稳定行为。**
