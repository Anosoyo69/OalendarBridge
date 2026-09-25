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

        /*
         * 诊断代码：只记录"系统到底有没有叫醒我们、带来了什么变化"。
         * 不影响下面任何同步判断。
         */
        val triggeredUris =
            try {
                params.triggeredContentUris
                    ?.joinToString(",") { it.toString() }
            } catch (_: Exception) {
                null
            } ?: "-"

        val triggeredAuthorities =
            try {
                params.triggeredContentAuthorities
                    ?.joinToString(",")
            } catch (_: Exception) {
                null
            } ?: "-"

        Diagnostics.log(
            applicationContext,
            "作业被系统叫醒：authorities=$triggeredAuthorities uris=$triggeredUris"
        )

        if (!SyncEngine.isSyncEnabled(applicationContext)) {
            Diagnostics.log(
                applicationContext,
                "作业退出：自动同步是 OFF"
            )
            CalendarSyncScheduler.cancel(applicationContext)
            return false
        }

        stopped = false
        replacingSelf = false

        executor.execute {

            val outcome =
                try {
                    SyncEngine.checkAndMigrateNewEvents(
                        applicationContext
                    )
                } catch (e: Exception) {
                    "异常 ${e.javaClass.simpleName}"
                }

            Diagnostics.log(
                applicationContext,
                "作业执行结果：$outcome"
            )

            if (stopped) {
                Diagnostics.log(
                    applicationContext,
                    "作业在执行途中被停止，本轮不再重新注册"
                )
                return@execute
            }

            mainHandler.post {

                if (stopped) {
                    Diagnostics.log(
                        applicationContext,
                        "作业结束：本轮未重新注册"
                    )
                    return@post
                }

                if (!SyncEngine.isSyncEnabled(applicationContext)) {
                    Diagnostics.log(
                        applicationContext,
                        "作业结束：自动同步已关闭，取消调度"
                    )
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
                    Diagnostics.log(
                        applicationContext,
                        "作业重新注册失败 result=$result，交回系统重试"
                    )
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

        val reschedule =
            !replacingSelf &&
                    SyncEngine.isSyncEnabled(
                        applicationContext
                    )

        Diagnostics.log(
            applicationContext,
            "作业被系统停止：stopReason=" +
                    Diagnostics.stopReasonName(
                        params.stopReason
                    ) +
                    "，是否交给系统重试=" +
                    reschedule
        )

        return reschedule
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
