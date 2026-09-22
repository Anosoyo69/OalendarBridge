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
            cancel(context)
            return JobScheduler.RESULT_SUCCESS
        }

        return try {
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
}
