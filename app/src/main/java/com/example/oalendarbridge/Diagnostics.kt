package com.example.oalendarbridge

import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 纯诊断模块。
 *
 * 只做两件事：
 *
 * 1. 记录后台唤醒链路的时间线（作业是否被注册、是否真的被触发、
 *    被系统停止的原因、执行结果）。
 *
 * 2. 读取系统侧的作业状态（是否在队列里、为什么没跑、待机分桶、
 *    电池优化白名单）。
 *
 * 本文件不参与任何同步逻辑，不读写 SyncEngine 的任何状态。
 */
object Diagnostics {

    const val TAG = "OalendarBridgeDiag"

    const val BUILD_LABEL = "诊断版 diag3"

    private const val PREFS = "oalendar_bridge_diagnostics"
    private const val KEY_LOG = "log_lines"
    private const val MAX_LINES = 400

    /*
     * 写入一行诊断日志。
     *
     * 同时写 logcat（Tag: TAG）和本地环形缓冲，
     * 这样即使不连电脑也能在 App 界面里看到。
     */
    fun log(
        context: Context,
        message: String
    ) {

        try {
            Log.i(TAG, message)
        } catch (_: Exception) {
        }

        try {

            val prefs =
                context.applicationContext.getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                )

            val lines =
                (prefs.getString(KEY_LOG, "") ?: "")
                    .split("\n")
                    .filter { it.isNotBlank() }
                    .toMutableList()

            lines.add(
                stamp() + "  " + message
            )

            while (lines.size > MAX_LINES) {
                lines.removeAt(0)
            }

            prefs.edit()
                .putString(KEY_LOG, lines.joinToString("\n"))
                .apply()

        } catch (_: Exception) {
        }
    }

    /*
     * 返回日志，最新的在最前面。
     */
    fun readLog(
        context: Context,
        limit: Int = 40
    ): List<String> {

        val raw =
            try {
                context.applicationContext
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_LOG, "")
            } catch (_: Exception) {
                null
            } ?: ""

        return raw
            .split("\n")
            .filter { it.isNotBlank() }
            .reversed()
            .take(limit)
    }

    fun clearLog(
        context: Context
    ) {
        try {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LOG)
                .apply()
        } catch (_: Exception) {
        }
    }

    /*
     * 系统侧的作业状态。
     *
     * 关键判断：
     *
     * - 作业是否还在 JobScheduler 队列里
     * - 如果还在，系统为什么还没让它跑
     */
    fun jobSummary(
        context: Context
    ): String {

        val appContext =
            context.applicationContext

        val scheduler =
            try {
                appContext.getSystemService(
                    Context.JOB_SCHEDULER_SERVICE
                ) as? JobScheduler
            } catch (_: Exception) {
                null
            }

        if (scheduler == null) {
            return "作业状态：无法获取 JobScheduler"
        }

        val jobId =
            CalendarSyncScheduler.JOB_ID

        val builder =
            StringBuilder()

        val pending =
            try {
                scheduler.getPendingJob(jobId)
            } catch (_: Exception) {
                null
            }

        if (pending == null) {

            builder.append(
                "作业：未注册（队列里没有 $jobId）"
            )

        } else {

            builder.append(
                "作业：已注册，等待触发"
            )

            if (Build.VERSION.SDK_INT >= 36) {

                try {

                    val reasons =
                        scheduler.getPendingJobReasons(jobId)

                    if (reasons != null && reasons.isNotEmpty()) {

                        builder.append("\n待执行原因：")
                        builder.append(
                            reasons.joinToString("、") {
                                reasonName(it)
                            }
                        )
                    }

                } catch (_: Exception) {
                }

                try {

                    val history =
                        scheduler.getPendingJobReasonsHistory(jobId)

                    if (history != null && history.isNotEmpty()) {

                        builder.append("\n原因历史：")

                        history.takeLast(3).forEach { info ->

                            builder.append("\n  ")
                            builder.append(
                                stampAt(info.timestampMillis)
                            )
                            builder.append(" ")
                            builder.append(
                                info.pendingJobReasons
                                    .joinToString("、") {
                                        reasonName(it)
                                    }
                            )
                        }
                    }

                } catch (_: Exception) {
                }
            }

            if (Build.VERSION.SDK_INT >= 37) {

                try {

                    val stats =
                        scheduler.getPendingJobReasonStats(jobId)

                    if (stats != null && stats.isNotEmpty()) {

                        builder.append("\n原因累计时长：")

                        builder.append(
                            stats.entries.joinToString("、") {
                                reasonName(it.key) +
                                        " " +
                                        it.value.toMinutes() +
                                        "m"
                            }
                        )
                    }

                } catch (_: Exception) {
                }
            }
        }

        try {
            val total =
                scheduler.allPendingJobs.size

            builder.append("\n队列作业总数：")
            builder.append(total)
            builder.append("（本应用自己的作业）")

        } catch (_: Exception) {
        }

        return builder.toString()
    }

    /*
     * 环境状态：这决定了系统愿不愿意及时叫醒我们。
     */
    fun environmentSummary(
        context: Context
    ): String {

        val appContext =
            context.applicationContext

        val builder =
            StringBuilder()

        builder.append("系统：Android ")
        builder.append(Build.VERSION.RELEASE)
        builder.append("（API ")
        builder.append(Build.VERSION.SDK_INT)
        builder.append("）")
        builder.append(" · ")
        builder.append(Build.MANUFACTURER)
        builder.append(" ")
        builder.append(Build.MODEL)

        builder.append("\n自动同步开关：")
        builder.append(
            if (SyncEngine.isSyncEnabled(appContext)) "ON" else "OFF"
        )

        if (Build.VERSION.SDK_INT >= 28) {

            try {

                val usage =
                    appContext.getSystemService(
                        Context.USAGE_STATS_SERVICE
                    ) as? UsageStatsManager

                val bucket =
                    usage?.appStandbyBucket

                builder.append("\n待机分桶：")
                builder.append(bucketName(bucket))

            } catch (_: Exception) {
            }
        }

        try {

            val power =
                appContext.getSystemService(
                    Context.POWER_SERVICE
                ) as? PowerManager

            if (power != null) {

                builder.append("\n电池优化白名单：")
                builder.append(
                    if (
                        power.isIgnoringBatteryOptimizations(
                            appContext.packageName
                        )
                    ) {
                        "已豁免"
                    } else {
                        "未豁免"
                    }
                )

                builder.append("\n设备空闲（Doze）：")
                builder.append(
                    if (power.isDeviceIdleMode) "是" else "否"
                )
            }

        } catch (_: Exception) {
        }

        /*
         * 系统已开机时长：用来判断"夜间是否重启过手机"。
         * 重启会让自动同步按产品规则变为 OFF，需要能和手动关闭区分开。
         */
        try {

            val totalMinutes =
                SystemClock.elapsedRealtime() / 60000L

            builder.append("\n系统已开机时长：")
            builder.append(totalMinutes / (60 * 24))
            builder.append("天")
            builder.append((totalMinutes % (60 * 24)) / 60)
            builder.append("小时")
            builder.append(totalMinutes % 60)
            builder.append("分")

        } catch (_: Exception) {
        }

        return builder.toString()
    }

    fun stopReasonName(
        reason: Int
    ): String = when (reason) {

        JobParameters.STOP_REASON_UNDEFINED ->
            "UNDEFINED"

        JobParameters.STOP_REASON_CANCELLED_BY_APP ->
            "CANCELLED_BY_APP"

        JobParameters.STOP_REASON_PREEMPT ->
            "PREEMPT"

        JobParameters.STOP_REASON_TIMEOUT ->
            "TIMEOUT"

        JobParameters.STOP_REASON_DEVICE_STATE ->
            "DEVICE_STATE"

        JobParameters.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW ->
            "CONSTRAINT_BATTERY_NOT_LOW"

        JobParameters.STOP_REASON_CONSTRAINT_CHARGING ->
            "CONSTRAINT_CHARGING"

        JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY ->
            "CONSTRAINT_CONNECTIVITY"

        JobParameters.STOP_REASON_CONSTRAINT_DEVICE_IDLE ->
            "CONSTRAINT_DEVICE_IDLE"

        JobParameters.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW ->
            "CONSTRAINT_STORAGE_NOT_LOW"

        JobParameters.STOP_REASON_QUOTA ->
            "QUOTA"

        JobParameters.STOP_REASON_BACKGROUND_RESTRICTION ->
            "BACKGROUND_RESTRICTION"

        JobParameters.STOP_REASON_APP_STANDBY ->
            "APP_STANDBY"

        JobParameters.STOP_REASON_USER ->
            "USER"

        JobParameters.STOP_REASON_SYSTEM_PROCESSING ->
            "SYSTEM_PROCESSING"

        JobParameters.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED ->
            "APP_LAUNCH_TIME_CHANGED"

        JobParameters.STOP_REASON_TIMEOUT_ABANDONED ->
            "TIMEOUT_ABANDONED"

        else ->
            "UNKNOWN($reason)"
    }

    private fun reasonName(
        reason: Int
    ): String = when (reason) {

        JobScheduler.PENDING_JOB_REASON_UNDEFINED ->
            "UNDEFINED"

        JobScheduler.PENDING_JOB_REASON_APP ->
            "APP"

        JobScheduler.PENDING_JOB_REASON_APP_STANDBY ->
            "APP_STANDBY（待机分桶）"

        JobScheduler.PENDING_JOB_REASON_BACKGROUND_RESTRICTION ->
            "BACKGROUND_RESTRICTION（后台受限）"

        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_BATTERY_NOT_LOW ->
            "CONSTRAINT_BATTERY_NOT_LOW"

        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CHARGING ->
            "CONSTRAINT_CHARGING"

        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY ->
            "CONSTRAINT_CONNECTIVITY"

        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONTENT_TRIGGER ->
            "CONSTRAINT_CONTENT_TRIGGER（正在等日历变化）"

        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_DEADLINE ->
            "CONSTRAINT_DEADLINE"

        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_DEVICE_IDLE ->
            "CONSTRAINT_DEVICE_IDLE"

        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_MINIMUM_LATENCY ->
            "CONSTRAINT_MINIMUM_LATENCY"

        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_PREFETCH ->
            "CONSTRAINT_PREFETCH"

        JobScheduler.PENDING_JOB_REASON_CONSTRAINT_STORAGE_NOT_LOW ->
            "CONSTRAINT_STORAGE_NOT_LOW"

        JobScheduler.PENDING_JOB_REASON_DEVICE_STATE ->
            "DEVICE_STATE"

        JobScheduler.PENDING_JOB_REASON_EXECUTING ->
            "EXECUTING（正在执行）"

        JobScheduler.PENDING_JOB_REASON_INVALID_JOB_ID ->
            "INVALID_JOB_ID（作业不存在）"

        JobScheduler.PENDING_JOB_REASON_JOB_SCHEDULER_OPTIMIZATION ->
            "JOB_SCHEDULER_OPTIMIZATION（系统省电优化）"

        JobScheduler.PENDING_JOB_REASON_QUOTA ->
            "QUOTA（超出运行配额）"

        JobScheduler.PENDING_JOB_REASON_USER ->
            "USER"

        else ->
            "UNKNOWN($reason)"
    }

    private fun bucketName(
        bucket: Int?
    ): String = when (bucket) {

        UsageStatsManager.STANDBY_BUCKET_ACTIVE ->
            "ACTIVE（活跃）"

        UsageStatsManager.STANDBY_BUCKET_WORKING_SET ->
            "WORKING_SET（工作集）"

        UsageStatsManager.STANDBY_BUCKET_FREQUENT ->
            "FREQUENT（常用）"

        UsageStatsManager.STANDBY_BUCKET_RARE ->
            "RARE（很少用）"

        UsageStatsManager.STANDBY_BUCKET_RESTRICTED ->
            "RESTRICTED（受限）"

        /*
         * 以下两个是隐藏常量，只能写字面值：
         *
         * 5  = STANDBY_BUCKET_EXEMPTED（不受待机限制，最宽松）
         * 50 = STANDBY_BUCKET_NEVER（几乎不给运行机会）
         */
        5 ->
            "EXEMPTED（已豁免待机限制）"

        50 ->
            "NEVER（基本不给运行机会）"

        else ->
            "其他($bucket)"
    }

    private fun stamp(): String =
        SimpleDateFormat(
            "MM-dd HH:mm:ss",
            Locale.US
        ).format(Date())

    private fun stampAt(
        millis: Long
    ): String =
        SimpleDateFormat(
            "MM-dd HH:mm:ss",
            Locale.US
        ).format(Date(millis))
}
