package cc.cherr.codexdeck.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cc.cherr.codexdeck.AppState
import cc.cherr.codexdeck.TerminalSessionHandle
import cc.cherr.codexdeck.terminal.TerminalModifier

@Composable
fun TerminalScreen(
    state: AppState,
    terminal: TerminalSessionHandle,
    onBack: () -> Unit,
    onSelectTerminal: (String?) -> Unit,
    onCloseTerminal: (String) -> Unit,
) {
    val view = LocalView.current
    DisposableEffect(view, state.settings.keepScreenOnInTerminal) {
        view.keepScreenOn = state.settings.keepScreenOnInTerminal
        onDispose { view.keepScreenOn = false }
    }
    BackHandler {
        if (!terminal.controller.hideKeyboard()) onBack()
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = androidx.compose.ui.graphics.Color(terminal.controller.backgroundColor),
        topBar = {
            Surface(tonalElevation = 6.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回 Codex")
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                terminal.hostLabel,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                terminal.controller.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        TextButton(onClick = { terminal.controller.toggleKeyboard() }) {
                            Text("键盘")
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        state.terminalSessions.forEach { session ->
                            AssistChip(
                                onClick = { onSelectTerminal(session.id) },
                                label = {
                                    Text(
                                        if (session.id == terminal.id) session.controller.title
                                        else session.hostLabel,
                                    )
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { onCloseTerminal(session.id) },
                                        modifier = Modifier.padding(0.dp),
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "关闭 Shell")
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            TerminalKeyBar(terminal)
        },
    ) { insets ->
        key(terminal.id) {
            AndroidView(
                factory = { context -> terminal.controller.createView(context) },
                onRelease = terminal.controller::releaseView,
                update = { terminal.controller.setFontSize(state.settings.terminalFontSizeSp) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets)
                    .imePadding(),
            )
        }
    }
}

@Composable
private fun TerminalKeyBar(terminal: TerminalSessionHandle) {
    val controller = terminal.controller
    Surface(tonalElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            KeyButton("ESC") { controller.sendKey(KeyEvent.KEYCODE_ESCAPE) }
            KeyButton("/") { controller.typeText("/") }
            KeyButton("-") { controller.typeText("-") }
            KeyButton("TAB") { controller.sendKey(KeyEvent.KEYCODE_TAB) }
            FilterChip(
                selected = controller.ctrlPressed,
                onClick = { controller.toggleModifier(TerminalModifier.CTRL) },
                label = { Text("CTRL") },
            )
            FilterChip(
                selected = controller.altPressed,
                onClick = { controller.toggleModifier(TerminalModifier.ALT) },
                label = { Text("ALT") },
            )
            KeyButton("↑") { controller.sendKey(KeyEvent.KEYCODE_DPAD_UP) }
            KeyButton("↓") { controller.sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
            KeyButton("←") { controller.sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
            KeyButton("→") { controller.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
            KeyButton("PGUP") { controller.sendKey(KeyEvent.KEYCODE_PAGE_UP) }
            KeyButton("PGDN") { controller.sendKey(KeyEvent.KEYCODE_PAGE_DOWN) }
            KeyButton("粘贴") { controller.pasteFromClipboard() }
            KeyButton("复制") { controller.copySelection() }
            KeyButton("键盘") { controller.toggleKeyboard() }
        }
    }
}

@Composable
private fun KeyButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
