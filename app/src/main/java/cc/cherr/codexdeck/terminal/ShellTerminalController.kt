package cc.cherr.codexdeck.terminal

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TerminalSize
import com.termux.terminal.TerminalTransport
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.lang.ref.WeakReference

enum class TerminalModifier {
    CTRL,
    ALT,
}

class ShellTerminalController(
    private val context: Context,
    transport: TerminalTransport,
    private val onFinished: () -> Unit,
    initialFontSizeSp: Int = 13,
) : TerminalSessionClient, TerminalViewClient {
    val session = TerminalSession(transport, 5_000, this)
    private var viewReference = WeakReference<TerminalView>(null)
    private val view: TerminalView? get() = viewReference.get()

    var backgroundColor by mutableIntStateOf(
        TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND],
    )
        private set
    var foregroundColor by mutableIntStateOf(
        TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND],
    )
        private set
    var ctrlPressed by mutableStateOf(false)
        private set
    var altPressed by mutableStateOf(false)
        private set
    var title by mutableStateOf("Shell")
        private set
    private var fontSizeSp = initialFontSizeSp.coerceIn(9, 28)

    init {
        session.updateSize(80, 24, 8, 16)
    }

    fun createView(): TerminalView = TerminalView(context, null).also { terminal ->
        viewReference = WeakReference(terminal)
        terminal.setTerminalViewClient(this)
        terminal.setTypeface(Typeface.MONOSPACE)
        applyFontSize(terminal)
        terminal.attachSession(session)
        terminal.isFocusableInTouchMode = true
        terminal.setBackgroundColor(backgroundColor)
        terminal.setOnCreateContextMenuListener { menu, currentView, _ ->
            menu.add("粘贴").setOnMenuItemClickListener {
                pasteFromClipboard()
                true
            }
            menu.add("选择文本").setOnMenuItemClickListener {
                val now = android.os.SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(
                    now,
                    now,
                    MotionEvent.ACTION_DOWN,
                    currentView.width / 2f,
                    currentView.height / 2f,
                    0,
                )
                try {
                    (currentView as TerminalView).startTextSelectionMode(event)
                } finally {
                    event.recycle()
                }
                true
            }
        }
        terminal.requestFocus()
    }

    fun setFontSize(sp: Int) {
        val normalized = sp.coerceIn(9, 28)
        if (fontSizeSp == normalized) return
        fontSizeSp = normalized
        view?.let {
            applyFontSize(it)
            it.onScreenUpdated()
        }
    }

    private fun applyFontSize(terminal: TerminalView) {
        terminal.setTextSize(
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                fontSizeSp.toFloat(),
                terminal.resources.displayMetrics,
            ).toInt(),
        )
    }

    fun releaseView(released: TerminalView) {
        released.setTerminalCursorBlinkerState(false, false)
        released.stopTextSelectionMode()
        if (view === released) viewReference.clear()
        clearModifiers()
    }

    fun toggleModifier(modifier: TerminalModifier) {
        when (modifier) {
            TerminalModifier.CTRL -> ctrlPressed = !ctrlPressed
            TerminalModifier.ALT -> altPressed = !altPressed
        }
    }

    fun clearModifiers() {
        ctrlPressed = false
        altPressed = false
    }

    fun sendKey(keyCode: Int) {
        if (!session.isReady) return
        val flags = (if (ctrlPressed) KeyHandler.KEYMOD_CTRL else 0) or
            (if (altPressed) KeyHandler.KEYMOD_ALT else 0)
        if (view?.handleKeyCode(keyCode, flags) == true) clearModifiers()
    }

    fun typeText(text: String) {
        if (session.isReady) session.write(text)
    }

    fun showKeyboard() {
        val terminal = view ?: return
        terminal.requestFocus()
        terminal.context.getSystemService(InputMethodManager::class.java)
            .showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard(): Boolean {
        val terminal = view ?: return false
        if (ViewCompat.getRootWindowInsets(terminal)
                ?.isVisible(WindowInsetsCompat.Type.ime()) != true
        ) {
            return false
        }
        windowController(terminal)
            ?.hide(WindowInsetsCompat.Type.ime())
        return true
    }

    fun toggleKeyboard() {
        val terminal = view ?: return
        if (ViewCompat.getRootWindowInsets(terminal)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        ) {
            windowController(terminal)
                ?.hide(WindowInsetsCompat.Type.ime())
        } else {
            showKeyboard()
        }
    }

    fun copySelection() {
        view?.let { terminal ->
            val text = terminal.selectedText
            if (!text.isNullOrEmpty()) {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Terminal", text))
            }
        }
    }

    fun pasteFromClipboard() {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).text?.toString()?.let { text ->
                if (text.length <= 128 * 1024) session.emulator?.paste(text)
            }
        }
    }

    fun close() {
        clearModifiers()
        session.finishIfRunning()
        viewReference.clear()
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        view?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        title = changedSession.title.orEmpty()
            .filterNot(Char::isISOControl)
            .trim()
            .take(120)
            .ifBlank { "Shell" }
    }

    override fun onSessionFinished(finishedSession: TerminalSession) = onFinished()

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (!text.isNullOrEmpty()) {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Terminal", text))
        }
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) = pasteFromClipboard()

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        session.emulator?.mColors?.mCurrentColors?.let { colors ->
            backgroundColor = colors[TextStyle.COLOR_INDEX_BACKGROUND]
            foregroundColor = colors[TextStyle.COLOR_INDEX_FOREGROUND]
        }
        view?.let {
            it.setBackgroundColor(backgroundColor)
            it.invalidate()
        }
    }

    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
    override fun getTerminalCursorStyle(): Int = 0
    override fun onScale(scale: Float): Float = 1f

    override fun onSingleTapUp(event: MotionEvent?) {
        showKeyboard()
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = false
    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, event: KeyEvent?, session: TerminalSession?): Boolean {
        if (event != null && event.isCtrlPressed && event.isShiftPressed &&
            keyCode == KeyEvent.KEYCODE_V
        ) {
            if (event.repeatCount == 0) pasteFromClipboard()
            return true
        }
        if (event == null || session?.isReady != true || event.isSystem ||
            event.isFunctionPressed || KeyEvent.isModifierKey(keyCode)
        ) {
            return false
        }
        val flags = (if (event.isCtrlPressed || ctrlPressed) KeyHandler.KEYMOD_CTRL else 0) or
            (if (event.isAltPressed || altPressed) KeyHandler.KEYMOD_ALT else 0) or
            (if (event.isShiftPressed) KeyHandler.KEYMOD_SHIFT else 0) or
            (if (event.isNumLockOn) KeyHandler.KEYMOD_NUM_LOCK else 0)
        val emulator = session.emulator ?: return false
        if (KeyHandler.getCode(
                keyCode,
                flags,
                emulator.isCursorKeysApplicationMode,
                emulator.isKeypadApplicationMode,
            ) == null
        ) {
            return false
        }
        return view?.handleKeyCode(keyCode, flags)?.also {
            if (it) clearModifiers()
        } ?: false
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = false

    override fun onLongPress(event: MotionEvent?): Boolean {
        if (event == null || view?.isSelectingText == true) return false
        val terminal = view ?: return false
        terminal.startTextSelectionMode(event)
        return true
    }

    override fun readControlKey() = ctrlPressed
    override fun readAltKey() = altPressed
    override fun readShiftKey() = false
    override fun readFnKey() = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        if (session?.isReady != true) return true
        clearModifiers()
        return false
    }

    override fun onEmulatorSet() {
        view?.setTerminalCursorBlinkerState(false, false)
    }

    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit

    private fun windowController(view: TerminalView) =
        context.findActivity()?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view)
        }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
