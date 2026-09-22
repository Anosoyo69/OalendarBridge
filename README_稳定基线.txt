OalendarBridge v1 STABLE
=======================

这是当前已经实机跑通的稳定基线。

【核心架构】

源：
OPPO / ColorOS Local Calendar

目标：
用户选择的 DAVx⁵ 可写 Calendar
例如 iCloud Calendar。

程序不依赖固定 Calendar ID。

保存并验证：
- calendar ID
- account type
- account name
- calendar display name

如果 Android 将同一个日历重新分配到新的 ID，
OalendarBridge 会通过账户和日历身份重新定位。

如果原目标日历已经不存在，
程序不得擅自选择另一个日历。


【自动同步总开关】

默认：OFF

ON：
只处理开关开启期间发生的日历变化。

OFF：
关闭期间发生的新增和修改必须忽略。

重新开启后：
不得补同步关闭期间的变化。

手机重新启动：
自动同步恢复为 OFF。


【新建日程】

OPPO 新建：
Local Event

OalendarBridge：
读取 Local Event
→ 在 DAVx⁵ Calendar INSERT 新 Event
→ 复制 Reminder / Attendee
→ 删除 Local 原 Event

不能再使用直接修改 CALENDAR_ID 的旧方案。


【修改日程】

已迁移 DAVx⁵ Event 在 OPPO 中修改后：

ColorOS 会修改内容，
但实机确认不会正确产生 DIRTY=1。

OalendarBridge：
检测 Event 内容指纹变化
→ 如果内容变化且 DIRTY=0
→ 对当前 Event 做一次普通 ContentResolver 同值 update
→ CalendarProvider 自动产生 DIRTY=1
→ DAVx⁵ 自动上传
→ iCloud 更新。


【删除日程】

OalendarBridge 不处理删除。

实机已经确认：

OPPO 删除 DAVx⁵ Event
→ CalendarProvider
→ DAVx⁵
→ iCloud

可以原生正确同步删除。


【ColorOS 必要权限】

- 允许自启动
- 允许后台运行 / 后台活动
- 允许后台弹出界面

其中“允许后台弹出界面”对后台 JobScheduler 唤醒非常关键。


================================
最终回归测试
================================

TEST 1：日历选择
----------------
关闭自动同步。

确认：
[ ] 源日历下拉框存在
[ ] 目标日历下拉框存在
[ ] 可以选择 Local 源日历
[ ] 可以选择 DAVx⁵ 目标日历

开启自动同步后：

[ ] 两个选择框锁定


TEST 2：关闭期间新增
-------------------
自动同步 OFF。

新建：
OB_OFF_NEW

等待约 10 秒。

确认：

[ ] 不进入 iCloud

然后重新开启自动同步。

等待约 10 秒。

确认：

[ ] OB_OFF_NEW 仍然不会补同步


TEST 3：开启期间新增
-------------------
自动同步 ON。

新建：
OB_ON_NEW

确认：

[ ] 自动出现在 DAVx⁵ / iCloud
[ ] OPPO 日历中只有一条事件
[ ] 没有重复事件


TEST 4：开启期间修改标题
-----------------------
把：

OB_ON_NEW

修改为：

OB_ON_NEW_EDIT

确认：

[ ] iCloud 自动变成 OB_ON_NEW_EDIT
[ ] 不需要手动打开 OalendarBridge
[ ] 不需要手动点 DAVx⁵ 同步


TEST 5：修改时间
---------------
修改 OB_ON_NEW_EDIT 的开始/结束时间。

确认：

[ ] iCloud 时间自动更新


TEST 6：后台划掉 App
-------------------
保持自动同步 ON。

从最近任务中划掉 OalendarBridge。

然后新建：

OB_BACKGROUND

确认：

[ ] App 不需要重新打开
[ ] OB_BACKGROUND 自动进入 iCloud

再修改：

OB_BACKGROUND_EDIT

确认：

[ ] iCloud 自动更新


TEST 7：删除
-----------
删除：

OB_BACKGROUND_EDIT

确认：

[ ] iCloud 自动删除

此功能由 DAVx⁵ 原生处理，
不是 OalendarBridge 自己实现。


TEST 8：关闭期间修改
-------------------
先确保一条已同步事件存在：

OB_OFF_EDIT

关闭自动同步。

把它改成：

OB_OFF_EDIT_CHANGED

确认：

[ ] iCloud 不应立即跟随这个关闭期间修改

重新开启自动同步。

确认：

[ ] OalendarBridge 不应把这个关闭期间修改作为历史补同步


TEST 9：重新打开 App
-------------------
保持自动同步 ON。

关闭界面后重新打开。

确认：

[ ] 源日历仍然正确
[ ] 目标日历仍然正确
[ ] 自动同步状态仍然正确
[ ] 没有产生重复事件


TEST 10：手机重启
----------------
重启 OPPO 手机。

打开 OalendarBridge。

确认：

[ ] 自动同步为 OFF

这是设计行为，不是 Bug。


================================
通过标准
================================

以上全部通过：

OalendarBridge v1 核心功能可以冻结。

后续 UI、日志、版本号等改动，
不得修改当前稳定同步算法。
