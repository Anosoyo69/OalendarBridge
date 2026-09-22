package com.example.oalendarbridge

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
import java.security.MessageDigest
import java.util.TimeZone


private const val PREFS_NAME =
    "oalendar_bridge_prefs"

private const val KEY_SYNC_ENABLED =
    "sync_enabled"

private const val KEY_BASELINE_EVENT_IDS =
    "baseline_event_ids"

private const val KEY_MANAGED_EVENT_IDS =
    "managed_event_ids"

private const val SNAPSHOT_PREFIX =
    "managed_snapshot_"


data class CalendarInfo(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val accountType: String,
    val accessLevel: Int,
    val visible: Int,
    val syncEvents: Int,
    val isPrimary: Int
)


data class LocalEventInfo(
    val id: Long,
    val originalId: Long?
)


data class SyncActionResult(
    val success: Boolean,
    val message: String
)


private data class MigrationResult(
    val copied: Boolean,
    val sourceRemoved: Boolean,
    val targetEventId: Long?
)


private data class ReminderCopy(
    val minutes: Int,
    val method: Int
)


private data class AttendeeCopy(
    val name: String?,
    val email: String?,
    val relationship: Int,
    val type: Int,
    val status: Int
)


/*
 * OalendarBridge 管理的 DAVx⁵ Event 当前状态。
 *
 * fingerprint：
 * 用户真正可能修改的 Event 主体内容指纹。
 *
 * dirty：
 * CalendarProvider 当前 DIRTY 状态。
 */
private data class ManagedEventState(
    val id: Long,
    val title: String?,
    val dtStart: Long?,
    val dirty: Int,
    val deleted: Int,
    val syncId: String?,
    val fingerprint: String
)


private data class RepairResult(
    val changedEvents: Int,
    val repairedEvents: Int,
    val removedEvents: Int
)


object SyncEngine {


    fun isSyncEnabled(
        context: Context
    ): Boolean {

        return context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .getBoolean(
                KEY_SYNC_ENABLED,
                false
            )
    }


    /*
     * 打开总同步开关。
     *
     * 这里同时做两件基线初始化：
     *
     * 1. 当前 Local Event 全部视为旧事件
     *    → 关闭期间创建的日程不会补同步。
     *
     * 2. 当前由 OalendarBridge 管理的 DAV Event
     *    全部重新记录内容快照
     *    → 关闭期间发生的修改不会补同步。
     */
    fun enableSync(
        context: Context
    ): SyncActionResult {

        return try {

            val calendars =
                readCalendars(context)

            val sourceCalendar =
                findSourceCalendar(context, calendars)

            if (
                sourceCalendar == null
            ) {

                SyncActionResult(
                    false,
                    "无法开启：没有找到 OPPO Local 日历"
                )

            } else {

                val targetCalendar =
                    findTargetCalendar(context, calendars)

                if (
                    targetCalendar == null
                ) {

                    SyncActionResult(
                        false,
                        "无法开启：没有找到可写的 DAVx⁵ / iCloud 日历"
                    )

                } else {

                    /*
                     * 当前所有 Local Event
                     * 都属于开启之前的历史状态。
                     */
                    val baselineIds =
                        readLocalEvents(
                            context,
                            sourceCalendar.id
                        )
                            .map {
                                it.id.toString()
                            }
                            .toSet()


                    val prefs =
                        context.getSharedPreferences(
                            PREFS_NAME,
                            Context.MODE_PRIVATE
                        )


                    prefs.edit()
                        .putStringSet(
                            KEY_BASELINE_EVENT_IDS,
                            baselineIds
                        )
                        .putBoolean(
                            KEY_SYNC_ENABLED,
                            true
                        )
                        .apply()


                    /*
                     * 找回以前由 OalendarBridge 创建的
                     * DAVx⁵ Event。
                     *
                     * 这样包括刚才测试的 Event 89
                     * 也可以进入管理范围。
                     */
                    val managedIds =
                        readManagedEventIds(
                            context
                        )


                    managedIds.addAll(
                        discoverBridgeCreatedDavEvents(
                            context,
                            targetCalendar.id
                        )
                    )


                    saveManagedEventIds(
                        context,
                        managedIds
                    )


                    /*
                     * 开启瞬间重新建立所有 managed event 快照。
                     *
                     * 所以 OFF 期间的修改到这里会成为新的 baseline，
                     * 不会被补同步。
                     */
                    rebuildManagedSnapshots(
                        context,
                        targetCalendar.id,
                        managedIds
                    )


                    SyncActionResult(
                        true,
                        "自动同步已开启。从现在开始的新建和修改将同步到 ${targetCalendar.displayName}。"
                    )
                }
            }

        } catch (
            e: Exception
        ) {

            SyncActionResult(
                false,
                "开启失败：${e.javaClass.simpleName}：${e.message}"
            )
        }
    }


    fun disableSync(
        context: Context
    ) {

        context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .edit()
            .putBoolean(
                KEY_SYNC_ENABLED,
                false
            )
            .apply()
    }


    /*
     * 现在这个函数承担两件事：
     *
     * A. 迁移新建的 OPPO Local 日程
     *
     * B. 检查已经迁移到 DAVx⁵ 的日程，
     *    如果内容变化但 dirty=0，
     *    自动做一次普通同值 update，
     *    让 CalendarProvider 正确生成 dirty=1。
     */
    fun checkAndMigrateNewEvents(
        context: Context
    ): String {

        if (
            !isSyncEnabled(context)
        ) {

            return "自动同步已关闭"
        }


        return try {

            val calendars =
                readCalendars(context)

            val sourceCalendar =
                findSourceCalendar(context, calendars)

            if (
                sourceCalendar == null
            ) {

                "没有找到 OPPO Local 日历"

            } else {

                val targetCalendar =
                    findTargetCalendar(context, calendars)

                if (
                    targetCalendar == null
                ) {

                    "没有找到可写的 DAVx⁵ / iCloud 日历"

                } else {

                    val baselineIds =
                        readBaselineEventIds(
                            context
                        )


                    val managedIds =
                        readManagedEventIds(
                            context
                        )


                    /*
                     * 为兼容我们前面已经创建过的事件，
                     * 每轮都顺便发现一次 mutators 中
                     * 带有 OalendarBridge 的 DAV Event。
                     */
                    managedIds.addAll(
                        discoverBridgeCreatedDavEvents(
                            context,
                            targetCalendar.id
                        )
                    )


                    var newDetected =
                        0

                    var migrated =
                        0

                    var migrationFailed =
                        0

                    var baselineChanged =
                        false


                    val localEvents =
                        readLocalEvents(
                            context,
                            sourceCalendar.id
                        )


                    /*
                     * ----------------------------------
                     * 第一部分：新建日程迁移
                     * ----------------------------------
                     */
                    for (
                        event in localEvents
                    ) {

                        if (
                            !isSyncEnabled(context)
                        ) {

                            break
                        }


                        if (
                            event.id in baselineIds
                        ) {

                            continue
                        }


                        if (
                            event.originalId != null &&
                            event.originalId in baselineIds
                        ) {

                            baselineIds.add(
                                event.id
                            )

                            baselineChanged =
                                true

                            continue
                        }


                        newDetected++


                        val result =
                            cloneEventIntoDavCalendar(
                                context,
                                event.id,
                                targetCalendar.id
                            )


                        if (
                            result.copied
                        ) {

                            migrated++


                            /*
                             * 记录新建出来的真正 DAVx⁵ Event ID。
                             */
                            result.targetEventId?.let {
                                newDavId ->

                                managedIds.add(
                                    newDavId
                                )


                                /*
                                 * 新 Event 创建后立即建立内容快照。
                                 */
                                readManagedEventState(
                                    context,
                                    newDavId,
                                    targetCalendar.id
                                )?.let {
                                    state ->

                                    saveSnapshot(
                                        context,
                                        newDavId,
                                        state.fingerprint
                                    )
                                }
                            }


                            /*
                             * 如果目标创建成功但 Local 原件删除失败，
                             * 避免下次再次复制。
                             */
                            if (
                                !result.sourceRemoved
                            ) {

                                baselineIds.add(
                                    event.id
                                )

                                baselineChanged =
                                    true
                            }

                        } else {

                            migrationFailed++
                        }
                    }


                    if (
                        baselineChanged
                    ) {

                        saveBaselineEventIds(
                            context,
                            baselineIds
                        )
                    }


                    saveManagedEventIds(
                        context,
                        managedIds
                    )


                    /*
                     * ----------------------------------
                     * 第二部分：修改修复
                     * ----------------------------------
                     */
                    val repair =
                        repairManagedEventChanges(
                            context,
                            targetCalendar.id,
                            managedIds
                        )


                    val pieces =
                        mutableListOf<String>()


                    if (
                        newDetected > 0
                    ) {

                        pieces.add(
                            "新增：成功 $migrated 个" +
                                    if (migrationFailed > 0) {
                                        "，失败 $migrationFailed 个"
                                    } else {
                                        ""
                                    }
                        )
                    }


                    if (
                        repair.changedEvents > 0
                    ) {

                        pieces.add(
                            "修改：发现 ${repair.changedEvents} 个，修复 ${repair.repairedEvents} 个"
                        )
                    }


                    if (
                        pieces.isEmpty()
                    ) {

                        "检查完成：没有新的日历变化"

                    } else {

                        "检查完成：" +
                                pieces.joinToString("；")
                    }
                }
            }

        } catch (
            e: Exception
        ) {

            "检查异常：${e.javaClass.simpleName}：${e.message}"
        }
    }


    /*
     * -------------------------------------------
     * 修改同步核心
     * -------------------------------------------
     *
     * 比较：
     *
     * 上一轮快照
     *      vs
     * 当前 CalendarProvider 中的内容
     *
     * 如果：
     *
     * 内容变化
     * AND dirty == 0
     *
     * 就说明非常像我们刚才实机验证到的
     * ColorOS 修改异常。
     *
     * 这时做一次同值普通 update。
     *
     * CalendarProvider 会自动：
     *
     * dirty = 1
     * mutators += com.example.oalendarbridge
     *
     * 然后 DAVx⁵ 就能正常上传。
     */
    private fun repairManagedEventChanges(
        context: Context,
        targetCalendarId: Long,
        managedIds: MutableSet<Long>
    ): RepairResult {

        var changedCount =
            0

        var repairedCount =
            0

        var removedCount =
            0

        var managedSetChanged =
            false


        val ids =
            managedIds.toList()


        for (
            eventId in ids
        ) {

            if (
                !isSyncEnabled(context)
            ) {

                break
            }


            val state =
                readManagedEventState(
                    context,
                    eventId,
                    targetCalendarId
                )


            /*
             * Event 已经不在当前 DAV Calendar。
             *
             * 删除同步稍后单独做；
             * 当前先从修改监控集合中清理掉。
             */
            if (
                state == null
            ) {

                managedIds.remove(
                    eventId
                )

                removeSnapshot(
                    context,
                    eventId
                )

                managedSetChanged =
                    true

                removedCount++

                continue
            }


            val oldFingerprint =
                readSnapshot(
                    context,
                    eventId
                )


            /*
             * 第一次见到：
             * 只建立 baseline，不认为是修改。
             */
            if (
                oldFingerprint == null
            ) {

                saveSnapshot(
                    context,
                    eventId,
                    state.fingerprint
                )

                continue
            }


            if (
                oldFingerprint !=
                state.fingerprint
            ) {

                changedCount++


                /*
                 * 如果 CalendarProvider 自己已经正确 dirty=1，
                 * 就完全不干涉。
                 *
                 * 只有 ColorOS 那种：
                 *
                 * 内容变了
                 * dirty 仍然 = 0
                 *
                 * 才需要 OalendarBridge 修复。
                 */
                if (
                    state.deleted == 0 &&
                    state.dirty == 0
                ) {

                    val repaired =
                        forceNormalAppUpdate(
                            context,
                            state
                        )


                    if (
                        repaired
                    ) {

                        repairedCount++
                    }
                }


                /*
                 * 无论是否需要修复，
                 * 当前内容都成为下一轮比较基准。
                 */
                saveSnapshot(
                    context,
                    eventId,
                    state.fingerprint
                )
            }
        }


        if (
            managedSetChanged
        ) {

            saveManagedEventIds(
                context,
                managedIds
            )
        }


        return RepairResult(
            changedEvents =
                changedCount,

            repairedEvents =
                repairedCount,

            removedEvents =
                removedCount
        )
    }


    /*
     * 我们刚才通过 adb 实机验证成功的逻辑：
     *
     * 重新写入一个“内容不变”的合法字段。
     *
     * 优先 TITLE。
     *
     * 无标题日程则写入同一个 DTSTART。
     *
     * 这里绝不直接写 DIRTY。
     */
    private fun forceNormalAppUpdate(
        context: Context,
        state: ManagedEventState
    ): Boolean {

        val values =
            ContentValues()


        if (
            state.title != null
        ) {

            values.put(
                CalendarContract.Events.TITLE,
                state.title
            )

        } else if (
            state.dtStart != null
        ) {

            values.put(
                CalendarContract.Events.DTSTART,
                state.dtStart
            )

        } else {

            return false
        }


        val uri =
            ContentUris.withAppendedId(
                CalendarContract.Events.CONTENT_URI,
                state.id
            )


        return try {

            context
                .contentResolver
                .update(
                    uri,
                    values,
                    null,
                    null
                ) > 0

        } catch (
            _: Exception
        ) {

            false
        }
    }


    /*
     * 读取一个 managed DAV Event，
     * 并生成“用户内容指纹”。
     *
     * 目前覆盖：
     *
     * 标题
     * 地点
     * 描述
     * 开始/结束时间
     * 时区
     * 全天
     * duration
     * RRULE/RDATE
     * EXRULE/EXDATE
     * access level
     * availability
     *
     * 也就是当前阶段最核心的 Event 主体修改。
     */
    private fun readManagedEventState(
        context: Context,
        eventId: Long,
        targetCalendarId: Long
    ): ManagedEventState? {

        val uri =
            ContentUris.withAppendedId(
                CalendarContract.Events.CONTENT_URI,
                eventId
            )


        val projection =
            arrayOf(

                CalendarContract.Events._ID,

                CalendarContract.Events.CALENDAR_ID,

                CalendarContract.Events.TITLE,

                CalendarContract.Events.EVENT_LOCATION,

                CalendarContract.Events.DESCRIPTION,

                CalendarContract.Events.DTSTART,

                CalendarContract.Events.DTEND,

                CalendarContract.Events.EVENT_TIMEZONE,

                CalendarContract.Events.EVENT_END_TIMEZONE,

                CalendarContract.Events.DURATION,

                CalendarContract.Events.ALL_DAY,

                CalendarContract.Events.RRULE,

                CalendarContract.Events.RDATE,

                CalendarContract.Events.EXRULE,

                CalendarContract.Events.EXDATE,

                CalendarContract.Events.ACCESS_LEVEL,

                CalendarContract.Events.AVAILABILITY,

                CalendarContract.Events.DIRTY,

                CalendarContract.Events.DELETED,

                CalendarContract.Events._SYNC_ID
            )


        var result:
            ManagedEventState? =
            null


        context
            .contentResolver
            .query(
                uri,
                projection,
                null,
                null,
                null
            )
            ?.use {
                cursor ->


                if (
                    cursor.moveToFirst()
                ) {

                    val calendarId =
                        cursorLong(
                            cursor,
                            CalendarContract.Events.CALENDAR_ID
                        )


                    if (
                        calendarId !=
                        targetCalendarId
                    ) {

                        return@use
                    }


                    val canonical =
                        listOf(

                            cursorString(
                                cursor,
                                CalendarContract.Events.TITLE
                            ),

                            cursorString(
                                cursor,
                                CalendarContract.Events.EVENT_LOCATION
                            ),

                            cursorString(
                                cursor,
                                CalendarContract.Events.DESCRIPTION
                            ),

                            cursorLong(
                                cursor,
                                CalendarContract.Events.DTSTART
                            ),

                            cursorLong(
                                cursor,
                                CalendarContract.Events.DTEND
                            ),

                            cursorString(
                                cursor,
                                CalendarContract.Events.EVENT_TIMEZONE
                            ),

                            cursorString(
                                cursor,
                                CalendarContract.Events.EVENT_END_TIMEZONE
                            ),

                            cursorString(
                                cursor,
                                CalendarContract.Events.DURATION
                            ),

                            cursorInt(
                                cursor,
                                CalendarContract.Events.ALL_DAY
                            ),

                            cursorString(
                                cursor,
                                CalendarContract.Events.RRULE
                            ),

                            cursorString(
                                cursor,
                                CalendarContract.Events.RDATE
                            ),

                            cursorString(
                                cursor,
                                CalendarContract.Events.EXRULE
                            ),

                            cursorString(
                                cursor,
                                CalendarContract.Events.EXDATE
                            ),

                            cursorInt(
                                cursor,
                                CalendarContract.Events.ACCESS_LEVEL
                            ),

                            cursorInt(
                                cursor,
                                CalendarContract.Events.AVAILABILITY
                            )
                        )
                            .joinToString(
                                separator = "\u001F"
                            ) {
                                value ->

                                value?.toString()
                                    ?: "<NULL>"
                            }


                    result =
                        ManagedEventState(

                            id =
                                eventId,

                            title =
                                cursorString(
                                    cursor,
                                    CalendarContract.Events.TITLE
                                ),

                            dtStart =
                                cursorLong(
                                    cursor,
                                    CalendarContract.Events.DTSTART
                                ),

                            dirty =
                                cursorInt(
                                    cursor,
                                    CalendarContract.Events.DIRTY
                                ) ?: 0,

                            deleted =
                                cursorInt(
                                    cursor,
                                    CalendarContract.Events.DELETED
                                ) ?: 0,

                            syncId =
                                cursorString(
                                    cursor,
                                    CalendarContract.Events._SYNC_ID
                                ),

                            fingerprint =
                                sha256(
                                    canonical
                                )
                        )
                }
            }


        return result
    }


    /*
     * 在 DAV Calendar 中寻找过去由
     * OalendarBridge 创建/修改过的 Event。
     *
     * 这是为了兼容升级前已经创建好的测试事件。
     */
    private fun discoverBridgeCreatedDavEvents(
        context: Context,
        targetCalendarId: Long
    ): Set<Long> {

        val result =
            mutableSetOf<Long>()


        context
            .contentResolver
            .query(

                CalendarContract.Events.CONTENT_URI,

                arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events.CALENDAR_ID,
                    CalendarContract.Events.MUTATORS
                ),

                "${CalendarContract.Events.CALENDAR_ID}=?",

                arrayOf(
                    targetCalendarId.toString()
                ),

                null
            )
            ?.use {
                cursor ->


                val idIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Events._ID
                    )


                val mutatorIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Events.MUTATORS
                    )


                while (
                    cursor.moveToNext()
                ) {

                    val mutators =
                        if (
                            cursor.isNull(
                                mutatorIndex
                            )
                        ) {
                            null
                        } else {
                            cursor.getString(
                                mutatorIndex
                            )
                        }


                    if (
                        mutators?.contains(
                            context.packageName
                        ) == true
                    ) {

                        result.add(
                            cursor.getLong(
                                idIndex
                            )
                        )
                    }
                }
            }


        return result
    }


    /*
     * 开启开关时重建修改监控 baseline。
     *
     * 这是实现：
     *
     * OFF 期间修改
     * → 重新 ON
     * → 不补同步
     *
     * 的关键。
     */
    private fun rebuildManagedSnapshots(
        context: Context,
        targetCalendarId: Long,
        managedIds: MutableSet<Long>
    ) {

        val validIds =
            mutableSetOf<Long>()


        for (
            eventId in managedIds
        ) {

            val state =
                readManagedEventState(
                    context,
                    eventId,
                    targetCalendarId
                )


            if (
                state != null
            ) {

                validIds.add(
                    eventId
                )


                saveSnapshot(
                    context,
                    eventId,
                    state.fingerprint
                )

            } else {

                removeSnapshot(
                    context,
                    eventId
                )
            }
        }


        saveManagedEventIds(
            context,
            validIds
        )
    }


    /*
     * -------------------------------------------
     * 新建日程迁移
     * -------------------------------------------
     */
    private fun cloneEventIntoDavCalendar(
        context: Context,
        sourceEventId: Long,
        targetCalendarId: Long
    ): MigrationResult {

        val eventValues =
            readEventValuesForClone(
                context,
                sourceEventId,
                targetCalendarId
            )


        if (
            eventValues == null
        ) {

            return MigrationResult(
                copied = false,
                sourceRemoved = false,
                targetEventId = null
            )
        }


        val reminders =
            readReminders(
                context,
                sourceEventId
            )


        val attendees =
            readAttendees(
                context,
                sourceEventId
            )


        val operations =
            ArrayList<ContentProviderOperation>()


        operations.add(

            ContentProviderOperation
                .newInsert(
                    CalendarContract.Events.CONTENT_URI
                )
                .withValues(
                    eventValues
                )
                .build()
        )


        reminders.forEach {
            reminder ->

            operations.add(

                ContentProviderOperation
                    .newInsert(
                        CalendarContract.Reminders.CONTENT_URI
                    )
                    .withValueBackReference(
                        CalendarContract.Reminders.EVENT_ID,
                        0
                    )
                    .withValue(
                        CalendarContract.Reminders.MINUTES,
                        reminder.minutes
                    )
                    .withValue(
                        CalendarContract.Reminders.METHOD,
                        reminder.method
                    )
                    .build()
            )
        }


        attendees.forEach {
            attendee ->

            val builder =
                ContentProviderOperation
                    .newInsert(
                        CalendarContract.Attendees.CONTENT_URI
                    )
                    .withValueBackReference(
                        CalendarContract.Attendees.EVENT_ID,
                        0
                    )
                    .withValue(
                        CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
                        attendee.relationship
                    )
                    .withValue(
                        CalendarContract.Attendees.ATTENDEE_TYPE,
                        attendee.type
                    )
                    .withValue(
                        CalendarContract.Attendees.ATTENDEE_STATUS,
                        attendee.status
                    )


            if (
                !attendee.name.isNullOrBlank()
            ) {

                builder.withValue(
                    CalendarContract.Attendees.ATTENDEE_NAME,
                    attendee.name
                )
            }


            if (
                !attendee.email.isNullOrBlank()
            ) {

                builder.withValue(
                    CalendarContract.Attendees.ATTENDEE_EMAIL,
                    attendee.email
                )
            }


            operations.add(
                builder.build()
            )
        }


        val results =

            try {

                context
                    .contentResolver
                    .applyBatch(
                        CalendarContract.AUTHORITY,
                        operations
                    )

            } catch (
                _: Exception
            ) {

                return MigrationResult(
                    copied = false,
                    sourceRemoved = false,
                    targetEventId = null
                )
            }


        if (
            results.isEmpty() ||
            results[0].uri == null
        ) {

            return MigrationResult(
                copied = false,
                sourceRemoved = false,
                targetEventId = null
            )
        }


        val newEventId =

            try {

                ContentUris.parseId(
                    results[0].uri!!
                )

            } catch (
                _: Exception
            ) {

                null
            }


        val sourceUri =
            ContentUris.withAppendedId(
                CalendarContract.Events.CONTENT_URI,
                sourceEventId
            )


        val deletedCount =

            try {

                context
                    .contentResolver
                    .delete(
                        sourceUri,
                        null,
                        null
                    )

            } catch (
                _: Exception
            ) {

                0
            }


        return MigrationResult(
            copied = true,
            sourceRemoved =
                deletedCount > 0,
            targetEventId =
                newEventId
        )
    }


    private fun readEventValuesForClone(
        context: Context,
        sourceEventId: Long,
        targetCalendarId: Long
    ): ContentValues? {

        val sourceUri =
            ContentUris.withAppendedId(
                CalendarContract.Events.CONTENT_URI,
                sourceEventId
            )


        val projection =
            arrayOf(

                CalendarContract.Events.TITLE,

                CalendarContract.Events.EVENT_LOCATION,

                CalendarContract.Events.DESCRIPTION,

                CalendarContract.Events.DTSTART,

                CalendarContract.Events.DTEND,

                CalendarContract.Events.EVENT_TIMEZONE,

                CalendarContract.Events.EVENT_END_TIMEZONE,

                CalendarContract.Events.DURATION,

                CalendarContract.Events.ALL_DAY,

                CalendarContract.Events.RRULE,

                CalendarContract.Events.RDATE,

                CalendarContract.Events.EXRULE,

                CalendarContract.Events.EXDATE,

                CalendarContract.Events.ACCESS_LEVEL,

                CalendarContract.Events.AVAILABILITY
            )


        var result:
            ContentValues? =
            null


        context
            .contentResolver
            .query(
                sourceUri,
                projection,
                null,
                null,
                null
            )
            ?.use {
                cursor ->


                if (
                    cursor.moveToFirst()
                ) {

                    val values =
                        ContentValues()


                    values.put(
                        CalendarContract.Events.CALENDAR_ID,
                        targetCalendarId
                    )


                    copyString(
                        cursor,
                        values,
                        CalendarContract.Events.TITLE
                    )


                    copyString(
                        cursor,
                        values,
                        CalendarContract.Events.EVENT_LOCATION
                    )


                    copyString(
                        cursor,
                        values,
                        CalendarContract.Events.DESCRIPTION
                    )


                    copyLong(
                        cursor,
                        values,
                        CalendarContract.Events.DTSTART
                    )


                    copyLong(
                        cursor,
                        values,
                        CalendarContract.Events.DTEND
                    )


                    copyString(
                        cursor,
                        values,
                        CalendarContract.Events.EVENT_TIMEZONE
                    )


                    copyString(
                        cursor,
                        values,
                        CalendarContract.Events.EVENT_END_TIMEZONE
                    )


                    copyString(
                        cursor,
                        values,
                        CalendarContract.Events.DURATION
                    )


                    copyInt(
                        cursor,
                        values,
                        CalendarContract.Events.ALL_DAY
                    )


                    copyString(
                        cursor,
                        values,
                        CalendarContract.Events.RRULE
                    )


                    copyString(
                        cursor,
                        values,
                        CalendarContract.Events.RDATE
                    )


                    copyString(
                        cursor,
                        values,
                        CalendarContract.Events.EXRULE
                    )


                    copyString(
                        cursor,
                        values,
                        CalendarContract.Events.EXDATE
                    )


                    copyInt(
                        cursor,
                        values,
                        CalendarContract.Events.ACCESS_LEVEL
                    )


                    copyInt(
                        cursor,
                        values,
                        CalendarContract.Events.AVAILABILITY
                    )


                    val allDay =
                        values.getAsInteger(
                            CalendarContract.Events.ALL_DAY
                        ) ?: 0


                    if (
                        allDay == 1
                    ) {

                        values.put(
                            CalendarContract.Events.EVENT_TIMEZONE,
                            "UTC"
                        )

                    } else {

                        val timezone =
                            values.getAsString(
                                CalendarContract.Events.EVENT_TIMEZONE
                            )


                        if (
                            timezone.isNullOrBlank()
                        ) {

                            values.put(
                                CalendarContract.Events.EVENT_TIMEZONE,
                                TimeZone.getDefault().id
                            )
                        }
                    }


                    result =
                        values
                }
            }


        return result
    }


    private fun readReminders(
        context: Context,
        eventId: Long
    ): List<ReminderCopy> {

        val result =
            mutableListOf<ReminderCopy>()


        context
            .contentResolver
            .query(

                CalendarContract.Reminders.CONTENT_URI,

                arrayOf(
                    CalendarContract.Reminders.MINUTES,
                    CalendarContract.Reminders.METHOD
                ),

                "${CalendarContract.Reminders.EVENT_ID}=?",

                arrayOf(
                    eventId.toString()
                ),

                null
            )
            ?.use {
                cursor ->


                val minutesIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Reminders.MINUTES
                    )


                val methodIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Reminders.METHOD
                    )


                while (
                    cursor.moveToNext()
                ) {

                    result.add(

                        ReminderCopy(
                            minutes =
                                cursor.getInt(
                                    minutesIndex
                                ),

                            method =
                                cursor.getInt(
                                    methodIndex
                                )
                        )
                    )
                }
            }


        return result
    }


    private fun readAttendees(
        context: Context,
        eventId: Long
    ): List<AttendeeCopy> {

        val result =
            mutableListOf<AttendeeCopy>()


        context
            .contentResolver
            .query(

                CalendarContract.Attendees.CONTENT_URI,

                arrayOf(

                    CalendarContract.Attendees.ATTENDEE_NAME,

                    CalendarContract.Attendees.ATTENDEE_EMAIL,

                    CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,

                    CalendarContract.Attendees.ATTENDEE_TYPE,

                    CalendarContract.Attendees.ATTENDEE_STATUS
                ),

                "${CalendarContract.Attendees.EVENT_ID}=?",

                arrayOf(
                    eventId.toString()
                ),

                null
            )
            ?.use {
                cursor ->


                val nameIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Attendees.ATTENDEE_NAME
                    )


                val emailIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Attendees.ATTENDEE_EMAIL
                    )


                val relationshipIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Attendees.ATTENDEE_RELATIONSHIP
                    )


                val typeIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Attendees.ATTENDEE_TYPE
                    )


                val statusIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Attendees.ATTENDEE_STATUS
                    )


                while (
                    cursor.moveToNext()
                ) {

                    result.add(

                        AttendeeCopy(

                            name =
                                if (
                                    cursor.isNull(nameIndex)
                                ) {
                                    null
                                } else {
                                    cursor.getString(nameIndex)
                                },

                            email =
                                if (
                                    cursor.isNull(emailIndex)
                                ) {
                                    null
                                } else {
                                    cursor.getString(emailIndex)
                                },

                            relationship =
                                cursor.getInt(
                                    relationshipIndex
                                ),

                            type =
                                cursor.getInt(
                                    typeIndex
                                ),

                            status =
                                cursor.getInt(
                                    statusIndex
                                )
                        )
                    )
                }
            }


        return result
    }


    fun readCalendars(
        context: Context
    ): List<CalendarInfo> {

        val result =
            mutableListOf<CalendarInfo>()


        val projection =
            arrayOf(

                CalendarContract.Calendars._ID,

                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,

                CalendarContract.Calendars.ACCOUNT_NAME,

                CalendarContract.Calendars.ACCOUNT_TYPE,

                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,

                CalendarContract.Calendars.VISIBLE,

                CalendarContract.Calendars.SYNC_EVENTS,

                CalendarContract.Calendars.IS_PRIMARY
            )


        context
            .contentResolver
            .query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                null
            )
            ?.use {
                cursor ->


                val idIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Calendars._ID
                    )


                val displayIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
                    )


                val accountNameIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Calendars.ACCOUNT_NAME
                    )


                val accountTypeIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Calendars.ACCOUNT_TYPE
                    )


                val accessIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
                    )


                val visibleIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Calendars.VISIBLE
                    )


                val syncIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Calendars.SYNC_EVENTS
                    )


                val primaryIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Calendars.IS_PRIMARY
                    )


                while (
                    cursor.moveToNext()
                ) {

                    result.add(

                        CalendarInfo(

                            id =
                                cursor.getLong(
                                    idIndex
                                ),

                            displayName =
                                cursor.getString(
                                    displayIndex
                                ) ?: "",

                            accountName =
                                cursor.getString(
                                    accountNameIndex
                                ) ?: "",

                            accountType =
                                cursor.getString(
                                    accountTypeIndex
                                ) ?: "",

                            accessLevel =
                                cursor.getInt(
                                    accessIndex
                                ),

                            visible =
                                cursor.getInt(
                                    visibleIndex
                                ),

                            syncEvents =
                                cursor.getInt(
                                    syncIndex
                                ),

                            isPrimary =
                                cursor.getInt(
                                    primaryIndex
                                )
                        )
                    )
                }
            }


        return result
    }


    private fun findSourceCalendar(
        context: Context,
        calendars: List<CalendarInfo>
    ): CalendarInfo? {
        return CalendarSelectionStore.resolveSource(context, calendars)
    }


    private fun findTargetCalendar(
        context: Context,
        calendars: List<CalendarInfo>
    ): CalendarInfo? {
        return CalendarSelectionStore.resolveTarget(context, calendars)
    }


    private fun readLocalEvents(
        context: Context,
        calendarId: Long
    ): List<LocalEventInfo> {

        val result =
            mutableListOf<LocalEventInfo>()


        context
            .contentResolver
            .query(

                CalendarContract.Events.CONTENT_URI,

                arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events.ORIGINAL_ID
                ),

                "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.DELETED}=0",

                arrayOf(
                    calendarId.toString()
                ),

                "${CalendarContract.Events._ID} ASC"
            )
            ?.use {
                cursor ->


                val idIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Events._ID
                    )


                val originalIndex =
                    cursor.getColumnIndexOrThrow(
                        CalendarContract.Events.ORIGINAL_ID
                    )


                while (
                    cursor.moveToNext()
                ) {

                    result.add(

                        LocalEventInfo(

                            id =
                                cursor.getLong(
                                    idIndex
                                ),

                            originalId =
                                if (
                                    cursor.isNull(
                                        originalIndex
                                    )
                                ) {
                                    null
                                } else {
                                    cursor.getLong(
                                        originalIndex
                                    )
                                }
                        )
                    )
                }
            }


        return result
    }


    private fun readBaselineEventIds(
        context: Context
    ): MutableSet<Long> {

        val raw =
            context
                .getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                .getStringSet(
                    KEY_BASELINE_EVENT_IDS,
                    emptySet()
                )
                ?: emptySet()


        return raw
            .mapNotNull {
                it.toLongOrNull()
            }
            .toMutableSet()
    }


    private fun saveBaselineEventIds(
        context: Context,
        ids: Set<Long>
    ) {

        context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .edit()
            .putStringSet(
                KEY_BASELINE_EVENT_IDS,
                ids
                    .map {
                        it.toString()
                    }
                    .toSet()
            )
            .apply()
    }


    private fun readManagedEventIds(
        context: Context
    ): MutableSet<Long> {

        val raw =
            context
                .getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                .getStringSet(
                    KEY_MANAGED_EVENT_IDS,
                    emptySet()
                )
                ?: emptySet()


        return raw
            .mapNotNull {
                it.toLongOrNull()
            }
            .toMutableSet()
    }


    private fun saveManagedEventIds(
        context: Context,
        ids: Set<Long>
    ) {

        context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .edit()
            .putStringSet(
                KEY_MANAGED_EVENT_IDS,
                ids
                    .map {
                        it.toString()
                    }
                    .toSet()
            )
            .apply()
    }


    private fun saveSnapshot(
        context: Context,
        eventId: Long,
        fingerprint: String
    ) {

        context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .edit()
            .putString(
                SNAPSHOT_PREFIX +
                        eventId,
                fingerprint
            )
            .apply()
    }


    private fun readSnapshot(
        context: Context,
        eventId: Long
    ): String? {

        return context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .getString(
                SNAPSHOT_PREFIX +
                        eventId,
                null
            )
    }


    private fun removeSnapshot(
        context: Context,
        eventId: Long
    ) {

        context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .edit()
            .remove(
                SNAPSHOT_PREFIX +
                        eventId
            )
            .apply()
    }


    private fun sha256(
        value: String
    ): String {

        val digest =
            MessageDigest
                .getInstance(
                    "SHA-256"
                )
                .digest(
                    value.toByteArray(
                        Charsets.UTF_8
                    )
                )


        return digest
            .joinToString(
                separator = ""
            ) {
                byte ->

                "%02x".format(
                    byte
                )
            }
    }


    private fun cursorString(
        cursor: Cursor,
        column: String
    ): String? {

        val index =
            cursor.getColumnIndex(
                column
            )


        return if (
            index >= 0 &&
            !cursor.isNull(index)
        ) {

            cursor.getString(
                index
            )

        } else {

            null
        }
    }


    private fun cursorLong(
        cursor: Cursor,
        column: String
    ): Long? {

        val index =
            cursor.getColumnIndex(
                column
            )


        return if (
            index >= 0 &&
            !cursor.isNull(index)
        ) {

            cursor.getLong(
                index
            )

        } else {

            null
        }
    }


    private fun cursorInt(
        cursor: Cursor,
        column: String
    ): Int? {

        val index =
            cursor.getColumnIndex(
                column
            )


        return if (
            index >= 0 &&
            !cursor.isNull(index)
        ) {

            cursor.getInt(
                index
            )

        } else {

            null
        }
    }


    private fun copyString(
        cursor: Cursor,
        values: ContentValues,
        column: String
    ) {

        val value =
            cursorString(
                cursor,
                column
            )


        if (
            value != null
        ) {

            values.put(
                column,
                value
            )
        }
    }


    private fun copyLong(
        cursor: Cursor,
        values: ContentValues,
        column: String
    ) {

        val value =
            cursorLong(
                cursor,
                column
            )


        if (
            value != null
        ) {

            values.put(
                column,
                value
            )
        }
    }


    private fun copyInt(
        cursor: Cursor,
        values: ContentValues,
        column: String
    ) {

        val value =
            cursorInt(
                cursor,
                column
            )


        if (
            value != null
        ) {

            values.put(
                column,
                value
            )
        }
    }
}
