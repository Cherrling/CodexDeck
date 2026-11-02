package cc.cherr.codexdeck.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.cherr.codexdeck.AppController
import cc.cherr.codexdeck.AppState
import cc.cherr.codexdeck.BuildConfig
import cc.cherr.codexdeck.data.ThemeMode

@Composable
fun SettingsScreen(
    state: AppState,
    controller: AppController,
    onOpenIdentities: () -> Unit,
) {
    val settings = state.settings
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 88.dp),
    ) {
        SectionHeading("设置", "连接、终端与安全策略")

        SettingsCard("外观") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.theme == mode,
                        onClick = { controller.updateSettings(settings.copy(theme = mode)) },
                        label = {
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> "跟随系统"
                                    ThemeMode.LIGHT -> "明亮"
                                    ThemeMode.DARK -> "深色"
                                },
                            )
                        },
                    )
                }
            }
            SettingSwitch(
                title = "动态配色",
                detail = "Android 12+ 使用系统壁纸颜色；关闭后使用 CodexDeck 配色",
                checked = settings.dynamicColor,
                onCheckedChange = {
                    controller.updateSettings(settings.copy(dynamicColor = it))
                },
            )
        }

        SettingsCard("连接") {
            SettingSwitch(
                title = "启动时自动连接上次服务器",
                detail = "默认关闭。开启后，App 启动会尝试恢复上次 Host 和 Thread；主动断开后仍不会自动连接。",
                checked = settings.autoReconnectOnLaunch,
                onCheckedChange = {
                    controller.updateSettings(settings.copy(autoReconnectOnLaunch = it))
                },
            )
        }

        SettingsCard("交互式 Shell") {
            Text("终端字号 ${settings.terminalFontSizeSp}sp")
            Slider(
                value = settings.terminalFontSizeSp.toFloat(),
                onValueChange = {
                    controller.updateSettings(settings.copy(terminalFontSizeSp = it.toInt()))
                },
                valueRange = 9f..28f,
                steps = 18,
            )
            SettingSwitch(
                title = "终端页面保持屏幕常亮",
                detail = "只在前台 Shell 页面启用；离开后关闭",
                checked = settings.keepScreenOnInTerminal,
                onCheckedChange = {
                    controller.updateSettings(settings.copy(keepScreenOnInTerminal = it))
                },
            )
            Text(
                "Termux terminal-emulator 提供 ANSI/TrueColor、滚动历史和 PTY resize，支持 tmux、vim、htop 与 Codex TUI。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("SSH 与凭据") {
            Text("${state.identities.size} 个 SSH 身份 · ${state.hosts.size} 个 Host")
            Text(
                "私钥、保存在本机的 Host 密码均由 Android Keystore 保护的 AES-GCM 密钥加密。Host Key 严格校验，首次连接和指纹变化必须人工确认。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onOpenIdentities) { Text("管理 SSH 身份") }
        }

        SettingsCard("关于 CodexDeck") {
            Text("版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            Text("构建 ${BuildConfig.BUILD_TIME}", style = MaterialTheme.typography.bodySmall)
            Text("提交 ${BuildConfig.SOURCE_REVISION}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Codex Deck 是非官方客户端。Codex 实际运行成本、认证、模型提供商和工具权限都由远端 Codex 决定。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked, onCheckedChange)
    }
}
