package com.example.oalendarbridge

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.provider.CalendarContract

object CalendarSyncScheduler {

    const val JOB_ID = 26090101
    private const val OLD_JOB_ID = 26090102

    /*
     * 看门狗作业。
     *
     * 它不参与任何同步判断，只负责确认主作业
     * （内容触发器）还在队列里。
     *
     * 为什么需要它：
     * 主作业只在这三个时机被注册 —— App 界面加载、
     * 拨动同步开关、应用升级（MY_PACKAGE_REPLACED）。
     * 一旦它在别的时刻被系统取消或丢掉，在用户下次
     * 打开 App 之前没有任何机制把它补回来，此时新建
     * 的日程不会被同步。
     *
     * 周期性作业只会被 JobScheduler.cancel 取消，所以
     * 关闭总开关、手机重启（产品规则：重启后默认关闭）
     * 时会随主作业一起被取消。
     */
    private const val WATCHDOG_JOB_ID = 26090103
    private const val WATCHDOG_INTERVAL_MS = 60 * 60 * 1000L

    fun ensureScheduled(context: Context): Int {

        if (!SyncEngine.isSyncEnabled(context)) {
            cancel(context)
            return JobScheduler.RESULT_SUCCESS
        }

        val result =
            try {
                val scheduler = context.getSystemService(
                    JobScheduler::class.java
                )

                scheduler.cancel(OLD_JOB_ID)

                if (scheduler.getPendingJob(JOB_ID) != null) {
                    JobScheduler.RESULT_SUCCESS
                } else {
                    scheduleJob(context)
                }
            } catch (_: Exception) {
                JobScheduler.RESULT_FAILURE
            }

        ensureWatchdogScheduled(context)

        return result
    }

    fun rescheduleAfterRun(context: Context): Int {

        if (!SyncEngine.isSyncEnabled(context)) {
            return JobScheduler.RESULT_FAILURE
        }

        return try {
            scheduleJob(context)
        } catch (_: Exception) {
            JobScheduler.RESULT_FAILURE
        }
    }

    fun cancel(context: Context) {
        try {
            val scheduler = context.getSystemService(
                JobScheduler::class.java
            )
            scheduler.cancel(JOB_ID)
            scheduler.cancel(OLD_JOB_ID)
            scheduler.cancel(WATCHDOG_JOB_ID)
        } catch (_: Exception) {
        }
    }

    private fun scheduleJob(context: Context): Int {

        val scheduler = context.getSystemService(
            JobScheduler::class.java
        )

        val component = ComponentName(
            context,
            CalendarSyncJobService::class.java
        )

        val trigger = JobInfo.TriggerContentUri(
            CalendarContract.Events.CONTENT_URI,
            JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
        )

        val job = JobInfo.Builder(
            JOB_ID,
            component
        )
            .addTriggerContentUri(trigger)
            .setTriggerContentUpdateDelay(1000L)
            .setTriggerContentMaxDelay(5000L)
            .build()

        return scheduler.schedule(job)
    }

    /*
     * 注册看门狗作业（已存在时不重复注册）。
     */
    private fun ensureWatchdogScheduled(context: Context) {

        try {
            val scheduler = context.getSystemService(
                JobScheduler::class.java
            )

            if (scheduler.getPendingJob(WATCHDOG_JOB_ID) != null) {
                return
            }

            val component = ComponentName(
                context,
                WatchdogJobService::class.java
            )

            val job = JobInfo.Builder(
                WATCHDOG_JOB_ID,
                component
            )
                .setPeriodic(WATCHDOG_INTERVAL_MS)
                .build()

            scheduler.schedule(job)
        } catch (_: Exception) {
        }
    }

    /*
     * 主作业是否仍在队列中。
     *
     * 只用于看门狗判断需不需要补注册，
     * 不参与任何同步逻辑。
     */
    fun isPrimaryJobPending(context: Context): Boolean {

        return try {
            context
                .getSystemService(JobScheduler::class.java)
                .getPendingJob(JOB_ID) != null
        } catch (_: Exception) {
            false
        }
    }
}
