package com.example.oalendarbridge

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ScreenState(
    val calendars: List<CalendarInfo>,
    val source: CalendarInfo?,
    val target: CalendarInfo?,
    val syncEnabled: Boolean,
    val diagnostics: String,
    val log: List<String>
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BridgeScreen()
                }
            }
        }
    }
}

@Composable
private fun BridgeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var granted by remember { mutableStateOf(hasCalendarPermissions(context)) }
    var calendars by remember { mutableStateOf(emptyList<CalendarInfo>()) }
    var source by remember { mutableStateOf<CalendarInfo?>(null) }
    var target by remember { mutableStateOf<CalendarInfo?>(null) }
    var syncEnabled by remember { mutableStateOf(SyncEngine.isSyncEnabled(context)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("正在检测日历……") }
    var diagnostics by remember { mutableStateOf("") }
    var logLines by remember { mutableStateOf(emptyList<String>()) }
    var diagnosticsExpanded by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted =
            result[Manifest.permission.READ_CALENDAR] == true &&
                result[Manifest.permission.WRITE_CALENDAR] == true
        if (!granted) status = "需要日历读取和写入权限。"
    }

    suspend fun reload(runCheck: Boolean) {
        val state = withContext(Dispatchers.IO) {
            if (runCheck && SyncEngine.isSyncEnabled(context)) {
                Diagnostics.log(context, "界面触发一次检查")
                SyncEngine.checkAndMigrateNewEvents(context)
            }

            val list = SyncEngine.readCalendars(context)
            val selection = CalendarSelectionStore.currentSelection(context, list)
            val enabled = SyncEngine.isSyncEnabled(context)

            if (enabled) {
                CalendarSyncScheduler.ensureScheduled(context)
            } else {
                Diagnostics.log(
                    context,
                    "界面加载：自动同步为 OFF，确认取消调度"
                )
                CalendarSyncScheduler.cancel(context)
            }

            ScreenState(
                calendars = list,
                source = selection.source,
                target = selection.target,
                syncEnabled = enabled,
                diagnostics =
                    Diagnostics.environmentSummary(context) +
                        "\n" +
                        Diagnostics.jobSummary(context),
                log = Diagnostics.readLog(context, 150)
            )
        }

        calendars = state.calendars
        source = state.source
        target = state.target
        syncEnabled = state.syncEnabled
        diagnostics = state.diagnostics
        logLines = state.log
    }

    LaunchedEffect(Unit) {
        if (!granted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR
                )
            )
        }
    }

    LaunchedEffect(granted) {
        if (granted) {
            busy = true
            reload(true)
            status = if (syncEnabled) "自动同步正在运行" else "请确认源日历和目标日历"
            busy = false
        }
    }

    val sourceCandidates = CalendarSelectionStore.sourceCandidates(calendars)
    val targetCandidates = CalendarSelectionStore.targetCandidates(calendars)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("OalendarBridge", style = MaterialTheme.typography.headlineMedium)
        Text("OPPO Calendar → DAVx⁵ / iCloud")

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("日历选择", style = MaterialTheme.typography.titleMedium)
                Text(
                    "不会使用固定日历 ID；会保存账户和日历身份。",
                    style = MaterialTheme.typography.bodySmall
                )

                CalendarSelector(
                    title = "源日历",
                    description = "OPPO 新建日程实际写入的 Local 日历",
                    calendars = sourceCandidates,
                    selected = source,
                    enabled = !syncEnabled && !busy,
                    emptyText = "没有找到 Local 日历"
                ) { selected ->
                    scope.launch {
                        busy = true
                        val result = withContext(Dispatchers.IO) {
                            CalendarSelectionStore.selectSource(context, selected)
                        }
                        status = result.message
                        reload(false)
                        busy = false
                    }
                }

                CalendarSelector(
                    title = "目标日历",
                    description = "DAVx⁵ 中用于同步 iCloud / CalDAV 的日历",
                    calendars = targetCandidates,
                    selected = target,
                    enabled = !syncEnabled && !busy,
                    emptyText = "没有找到可写 DAVx⁵ 日历"
                ) { selected ->
                    scope.launch {
                        busy = true
                        val result = withContext(Dispatchers.IO) {
                            CalendarSelectionStore.selectTarget(context, selected)
                        }
                        status = result.message
                        reload(false)
                        busy = false
                    }
                }

                if (syncEnabled) {
                    Text(
                        "自动同步开启期间日历选择会锁定；要更换请先关闭自动同步。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("自动同步", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (syncEnabled) "只处理开关开启期间的新建和修改"
                    else "关闭期间发生的变化以后不会补同步",
                    style = MaterialTheme.typography.bodySmall
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = syncEnabled,
                        enabled =
                            granted &&
                                !busy &&
                                (syncEnabled || (source != null && target != null)),
                        onCheckedChange = { newValue ->
                            scope.launch {
                                busy = true

                                if (newValue) {
                                    Diagnostics.log(
                                        context,
                                        "用户操作：把自动同步打开"
                                    )
                                    val result = withContext(Dispatchers.IO) {
                                        val enabledResult = SyncEngine.enableSync(context)
                                        if (enabledResult.success) {
                                            CalendarSyncScheduler.ensureScheduled(context)
                                        }
                                        enabledResult
                                    }
                                    status = result.message
                                } else {
                                    Diagnostics.log(
                                        context,
                                        "用户操作：把自动同步关闭"
                                    )
                                    withContext(Dispatchers.IO) {
                                        if (SyncEngine.isSyncEnabled(context)) {
                                            SyncEngine.checkAndMigrateNewEvents(context)
                                        }
                                        SyncEngine.disableSync(context)
                                        CalendarSyncScheduler.cancel(context)
                                    }
                                    status = "自动同步已关闭"
                                }

                                reload(false)
                                busy = false
                            }
                        }
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text("状态", style = MaterialTheme.typography.titleMedium)
                Text(status)
                Text(
                    "手机重启后自动同步默认关闭。",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "ColorOS 请保持允许自启动、后台运行和后台弹出界面。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            diagnosticsExpanded = !diagnosticsExpanded
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "诊断（遇到问题时可展开）",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (diagnosticsExpanded) "收起 ▲" else "展开 ▼",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    "记录后台唤醒链路，用于排查“新建日程没有同步”的问题。",
                    style = MaterialTheme.typography.bodySmall
                )

                if (diagnosticsExpanded) {
                    Text(
                        Diagnostics.BUILD_LABEL,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(diagnostics, style = MaterialTheme.typography.bodySmall)

                    Text(
                        "最近日志（新→旧）",
                        style = MaterialTheme.typography.labelLarge
                    )
                    if (logLines.isEmpty()) {
                        Text("（暂无）", style = MaterialTheme.typography.bodySmall)
                    } else {
                        logLines.forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            onClick = {
                                val payload =
                                    "版本：" +
                                        Diagnostics.BUILD_LABEL +
                                        "\n\n" +
                                        diagnostics +
                                        "\n\n日志（新→旧）\n" +
                                        logLines.joinToString("\n")
                                try {
                                    val clipboard = context.getSystemService(
                                        Context.CLIPBOARD_SERVICE
                                    ) as ClipboardManager
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText(
                                            "OalendarBridge 诊断",
                                            payload
                                        )
                                    )
                                    status = "诊断信息已复制到剪贴板"
                                } catch (_: Exception) {
                                    status = "复制失败"
                                }
                            }
                        ) {
                            Text("复制诊断信息")
                        }

                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    withContext(Dispatchers.IO) {
                                        Diagnostics.clearLog(context)
                                    }
                                    reload(false)
                                    status = "诊断日志已清空"
                                    busy = false
                                }
                            }
                        ) {
                            Text("清空日志")
                        }
                    }
                }
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = granted && !busy,
            onClick = {
                scope.launch {
                    busy = true
                    reload(syncEnabled)
                    status =
                        if (SyncEngine.isSyncEnabled(context))
                            "检测完成，自动同步正在运行"
                        else
                            "检测完成"
                    busy = false
                }
            }
        ) {
            Text("重新检测日历")
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "检测到 ${calendars.size} 个日历",
                    style = MaterialTheme.typography.titleMedium
                )

                calendars.forEach { calendar ->
                    val marker = when {
                        source?.id == calendar.id -> "【源】"
                        target?.id == calendar.id -> "【目标】"
                        else -> ""
                    }
                    Text("$marker ${calendar.displayName}".trim())
                    Text(
                        "ID=${calendar.id} · ${calendar.accountName} · ${calendar.accountType}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun CalendarSelector(
    title: String,
    description: String,
    calendars: List<CalendarInfo>,
    selected: CalendarInfo?,
    enabled: Boolean,
    emptyText: String,
    onSelected: (CalendarInfo) -> Unit
) {
    var open by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(description, style = MaterialTheme.typography.bodySmall)

        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && calendars.isNotEmpty(),
                onClick = { open = true }
            ) {
                val label = when {
                    calendars.isEmpty() -> emptyText
                    selected == null -> "请选择 ▼"
                    else -> "${calendarLabel(selected)}  ▼"
                }
                Text(label)
            }

            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false }
            ) {
                calendars.forEach { calendar ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(calendar.displayName)
                                Text(
                                    "${calendar.accountName} · ID ${calendar.id}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        },
                        onClick = {
                            open = false
                            onSelected(calendar)
                        }
                    )
                }
            }
        }

        if (selected != null) {
            Text(
                "当前 ID ${selected.id} · ${selected.accountType}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun calendarLabel(calendar: CalendarInfo): String =
    if (
        calendar.accountName.isBlank() ||
        calendar.accountName == calendar.displayName
    ) {
        calendar.displayName
    } else {
        "${calendar.displayName} · ${calendar.accountName}"
    }

private fun hasCalendarPermissions(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) ==
        PackageManager.PERMISSION_GRANTED
