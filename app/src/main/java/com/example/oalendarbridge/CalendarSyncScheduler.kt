package com.example.oalendarbridge

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.provider.CalendarContract

object CalendarSyncScheduler {

    const val JOB_ID = 26090101
    private const val OLD_JOB_ID = 26090102

    fun ensureScheduled(context: Context): Int {

        if (!SyncEngine.isSyncEnabled(context)) {
            Diagnostics.log(
                context,
                "ensureScheduled：同步 OFF，取消调度"
            )
            cancel(context)
            return JobScheduler.RESULT_SUCCESS
        }

        return try {
            val scheduler = context.getSystemService(
                JobScheduler::class.java
            )

            scheduler.cancel(OLD_JOB_ID)

            if (scheduler.getPendingJob(JOB_ID) != null) {
                Diagnostics.log(
                    context,
                    "ensureScheduled：作业已在队列中"
                )
                JobScheduler.RESULT_SUCCESS
            } else {
                Diagnostics.log(
                    context,
                    "ensureScheduled：作业不在队列中，重新注册"
                )
                scheduleJob(context)
            }
        } catch (e: Exception) {
            Diagnostics.log(
                context,
                "ensureScheduled 异常：${e.javaClass.simpleName}"
            )
            JobScheduler.RESULT_FAILURE
        }
    }

    fun rescheduleAfterRun(context: Context): Int {

        if (!SyncEngine.isSyncEnabled(context)) {
            return JobScheduler.RESULT_FAILURE
        }

        return try {
            scheduleJob(context)
        } catch (e: Exception) {
            Diagnostics.log(
                context,
                "rescheduleAfterRun 异常：${e.javaClass.simpleName}"
            )
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
            Diagnostics.log(context, "cancel：已取消作业调度")
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

        val result =
            scheduler.schedule(job)

        Diagnostics.log(
            context,
            "schedule：注册结果=$result（0=成功），等待日历内容变化"
        )

        return result
    }
}
