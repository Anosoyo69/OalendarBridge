package com.example.oalendarbridge

import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

class CalendarSyncJobService : JobService() {

    private val executor =
        Executors.newSingleThreadExecutor()

    private val mainHandler =
        Handler(Looper.getMainLooper())

    @Volatile
    private var stopped = false

    @Volatile
    private var replacingSelf = false

    override fun onStartJob(
        params: JobParameters
    ): Boolean {

        if (!SyncEngine.isSyncEnabled(applicationContext)) {
            CalendarSyncScheduler.cancel(applicationContext)
            return false
        }

        stopped = false
        replacingSelf = false

        executor.execute {

            try {
                SyncEngine.checkAndMigrateNewEvents(
                    applicationContext
                )
            } catch (_: Exception) {
            }

            if (stopped) {
                return@execute
            }

            mainHandler.post {

                if (stopped) {
                    return@post
                }

                if (!SyncEngine.isSyncEnabled(applicationContext)) {
                    CalendarSyncScheduler.cancel(
                        applicationContext
                    )
                    jobFinished(params, false)
                    return@post
                }

                replacingSelf = true

                val result =
                    CalendarSyncScheduler.rescheduleAfterRun(
                        applicationContext
                    )

                if (result != JobScheduler.RESULT_SUCCESS) {
                    replacingSelf = false
                    jobFinished(params, true)
                }
            }
        }

        return true
    }

    override fun onStopJob(
        params: JobParameters
    ): Boolean {

        stopped = true

        return !replacingSelf &&
                SyncEngine.isSyncEnabled(
                    applicationContext
                )
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
