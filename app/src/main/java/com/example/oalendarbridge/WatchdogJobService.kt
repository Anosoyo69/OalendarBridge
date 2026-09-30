package com.example.oalendarbridge

import android.app.job.JobParameters
import android.app.job.JobService
import java.util.concurrent.Executors

/*
 * 看门狗作业。
 *
 * 它是主作业（内容触发器）之外的一个极低频兜底：
 *
 * 1. 自动同步为 OFF 时什么都不做，并取消全部调度。
 * 2. 主作业还在队列里时，什么都不做（不重复检查）。
 * 3. 主作业已经被系统丢掉时，补注册它，并立刻补一次检查，
 *    让丢失期间新建的日程也能被带走。
 *
 * 它不改变任何同步语义：
 * - 不写 DIRTY
 * - 不改变 fingerprint / snapshot 组成字段
 * - 不做 OFF 期间的 backfill（检查逻辑与打开 App 时
 *   调用的完全是同一个入口）
 */
class WatchdogJobService : JobService() {

    private val executor =
        Executors.newSingleThreadExecutor()

    override fun onStartJob(
        params: JobParameters
    ): Boolean {

        val context = applicationContext

        if (!SyncEngine.isSyncEnabled(context)) {
            Diagnostics.log(
                context,
                "watchdog：同步 OFF，一并取消调度"
            )
            CalendarSyncScheduler.cancel(context)
            return false
        }

        val primaryPending =
            CalendarSyncScheduler.isPrimaryJobPending(context)

        Diagnostics.log(
            context,
            if (primaryPending) {
                "watchdog：主作业在队列中，不做任何事"
            } else {
                "watchdog：主作业不在队列中，补注册并补一次检查"
            }
        )

        CalendarSyncScheduler.ensureScheduled(context)

        if (primaryPending) {
            return false
        }

        executor.execute {

            val outcome =
                try {
                    SyncEngine.checkAndMigrateNewEvents(context)
                } catch (e: Exception) {
                    "异常 ${e.javaClass.simpleName}"
                }

            Diagnostics.log(
                context,
                "watchdog：补检查结果：$outcome"
            )

            jobFinished(params, false)
        }

        return true
    }

    override fun onStopJob(
        params: JobParameters
    ): Boolean {

        return false
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
