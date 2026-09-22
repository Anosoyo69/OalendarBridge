package com.example.oalendarbridge

import android.content.Context
import android.provider.CalendarContract

private const val PREFS = "oalendar_bridge_prefs"
private const val KEY_SYNC_ENABLED = "sync_enabled"
private const val SOURCE_PREFIX = "selected_source"
private const val TARGET_PREFIX = "selected_target"

data class CalendarSelection(
    val source: CalendarInfo?,
    val target: CalendarInfo?
)

object CalendarSelectionStore {

    fun sourceCandidates(calendars: List<CalendarInfo>): List<CalendarInfo> =
        calendars
            .filter { it.accountType == "LOCAL" }
            .sortedWith(
                compareByDescending<CalendarInfo> { it.isPrimary }
                    .thenBy { it.displayName }
                    .thenBy { it.id }
            )

    fun targetCandidates(calendars: List<CalendarInfo>): List<CalendarInfo> =
        calendars
            .filter {
                it.accountType == "bitfire.at.davdroid" &&
                    it.accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
            }
            .sortedWith(
                compareBy<CalendarInfo> { it.accountName }
                    .thenBy { it.displayName }
                    .thenBy { it.id }
            )

    fun currentSelection(
        context: Context,
        calendars: List<CalendarInfo>
    ): CalendarSelection = CalendarSelection(
        source = resolveSource(context, calendars),
        target = resolveTarget(context, calendars)
    )

    fun resolveSource(
        context: Context,
        calendars: List<CalendarInfo>
    ): CalendarInfo? {
        val candidates = sourceCandidates(calendars)
        resolveStored(context, SOURCE_PREFIX, candidates)?.let { return it }

        if (!hasStoredIdentity(context, SOURCE_PREFIX) && candidates.size == 1) {
            val only = candidates.first()
            saveIdentity(context, SOURCE_PREFIX, only)
            return only
        }
        return null
    }

    fun resolveTarget(
        context: Context,
        calendars: List<CalendarInfo>
    ): CalendarInfo? {
        val candidates = targetCandidates(calendars)
        resolveStored(context, TARGET_PREFIX, candidates)?.let { return it }

        if (!hasStoredIdentity(context, TARGET_PREFIX) && candidates.size == 1) {
            val only = candidates.first()
            saveIdentity(context, TARGET_PREFIX, only)
            return only
        }
        return null
    }

    fun selectSource(
        context: Context,
        calendar: CalendarInfo
    ): SyncActionResult {
        if (isSyncEnabled(context)) {
            return SyncActionResult(false, "请先关闭自动同步，再更换源日历。")
        }
        if (calendar.accountType != "LOCAL") {
            return SyncActionResult(false, "所选日历不是 Local 日历。")
        }
        saveIdentity(context, SOURCE_PREFIX, calendar)
        return SyncActionResult(true, "源日历已选择：${calendar.displayName}")
    }

    fun selectTarget(
        context: Context,
        calendar: CalendarInfo
    ): SyncActionResult {
        if (isSyncEnabled(context)) {
            return SyncActionResult(false, "请先关闭自动同步，再更换目标日历。")
        }
        if (calendar.accountType != "bitfire.at.davdroid") {
            return SyncActionResult(false, "所选日历不是 DAVx⁵ 日历。")
        }
        saveIdentity(context, TARGET_PREFIX, calendar)
        return SyncActionResult(true, "目标日历已选择：${calendar.displayName}")
    }

    private fun resolveStored(
        context: Context,
        prefix: String,
        candidates: List<CalendarInfo>
    ): CalendarInfo? {
        if (!hasStoredIdentity(context, prefix)) return null

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getLong("${prefix}_id", -1L)
        val type = prefs.getString("${prefix}_account_type", null)
        val account = prefs.getString("${prefix}_account_name", null)
        val name = prefs.getString("${prefix}_display_name", null)

        candidates.firstOrNull { it.id == id }?.let {
            if (matches(it, type, account, name)) return it
        }

        val matching = candidates.filter { matches(it, type, account, name) }
        if (matching.size == 1) {
            val relocated = matching.first()
            saveIdentity(context, prefix, relocated)
            return relocated
        }

        forceSyncOff(context)
        return null
    }

    private fun matches(
        calendar: CalendarInfo,
        type: String?,
        account: String?,
        name: String?
    ): Boolean =
        calendar.accountType == type &&
            calendar.accountName == account &&
            calendar.displayName == name

    private fun saveIdentity(
        context: Context,
        prefix: String,
        calendar: CalendarInfo
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong("${prefix}_id", calendar.id)
            .putString("${prefix}_account_type", calendar.accountType)
            .putString("${prefix}_account_name", calendar.accountName)
            .putString("${prefix}_display_name", calendar.displayName)
            .apply()
    }

    private fun hasStoredIdentity(context: Context, prefix: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .contains("${prefix}_account_type")

    private fun isSyncEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SYNC_ENABLED, false)

    private fun forceSyncOff(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SYNC_ENABLED, false)
            .apply()
    }
}
