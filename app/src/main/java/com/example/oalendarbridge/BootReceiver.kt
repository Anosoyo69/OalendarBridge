package com.example.oalendarbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        Diagnostics.log(
            context.applicationContext,
            "收到广播：${intent.action}"
        )

        when (intent.action) {

            Intent.ACTION_BOOT_COMPLETED -> {
                // 产品规则：手机重启后默认关闭。
                SyncEngine.disableSync(
                    context.applicationContext
                )
                CalendarSyncScheduler.cancel(
                    context.applicationContext
                )
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (
                    SyncEngine.isSyncEnabled(
                        context.applicationContext
                    )
                ) {
                    CalendarSyncScheduler.ensureScheduled(
                        context.applicationContext
                    )
                } else {
                    CalendarSyncScheduler.cancel(
                        context.applicationContext
                    )
                }
            }
        }
    }
}
