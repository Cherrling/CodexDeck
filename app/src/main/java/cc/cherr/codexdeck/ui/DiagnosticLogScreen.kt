package cc.cherr.codexdeck.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.cherr.codexdeck.AppController
import cc.cherr.codexdeck.AppState
import kotlinx.coroutines.launch

@Composable
fun DiagnosticLogScreen(
    state: AppState,
    controller: AppController,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var text by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    fun reload() {
        if (loading) return
        loading = true
        scope.launch {
            text = runCatching { controller.readDiagnosticLog() }.getOrDefault("")
            loading = false
        }
    }

    fun copyLog() {
        val current = text?.takeIf(String::isNotBlank) ?: return
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("CodexDeck diagnostics", current))
        Toast.makeText(context, "诊断日志已复制", Toast.LENGTH_SHORT).show()
    }

    fun shareLog() {
        val current = text?.takeIf(String::isNotBlank) ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "CodexDeck diagnostics")
            putExtra(Intent.EXTRA_TEXT, current)
        }
        context.startActivity(Intent.createChooser(send, "分享诊断日志"))
    }

    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(text) {
        if (!text.isNullOrEmpty()) {
            withFrameNanos { }
            scrollState.scrollTo(scrollState.maxValue)
        }
    }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置")
            }
            Column(Modifier.weight(1f)) {
                Text("诊断日志", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${text?.toByteArray(Charsets.UTF_8)?.size ?: 0} bytes · 最多保留 256 KiB / 2,000 行",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = ::reload, enabled = !loading) {
                Text(if (loading) "读取中…" else "刷新")
            }
        }
        HorizontalDivider()

        if (!state.settings.diagnosticLoggingEnabled) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "诊断日志当前关闭。关闭期间不会采集或写入新日志，已有记录仍可查看。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = ::copyLog, enabled = !text.isNullOrBlank()) {
                Text("复制")
            }
            OutlinedButton(onClick = ::shareLog, enabled = !text.isNullOrBlank()) {
                Text("分享")
            }
            OutlinedButton(
                onClick = { confirmClear = true },
                enabled = !text.isNullOrBlank() && !loading,
            ) {
                Text("清空")
            }
        }

        when {
            text == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            text.isNullOrBlank() -> Box(
                Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (state.settings.diagnosticLoggingEnabled) {
                        "暂无日志。复现问题后返回此页刷新。"
                    } else {
                        "暂无日志。打开诊断日志后重新连接或复现问题。"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> SelectionContainer {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text.orEmpty(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空诊断日志？") },
            text = { Text("此操作只删除本机诊断记录，不影响 Host、SSH 身份或 Codex 会话。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    loading = true
                    scope.launch {
                        runCatching { controller.clearDiagnosticLog() }
                            .onSuccess {
                                text = ""
                                Toast.makeText(context, "诊断日志已清空", Toast.LENGTH_SHORT).show()
                            }
                            .onFailure {
                                Toast.makeText(context, "清空失败：${it.message.orEmpty()}", Toast.LENGTH_LONG)
                                    .show()
                            }
                        loading = false
                    }
                }) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}
