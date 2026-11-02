package cc.cherr.codexdeck.data

import android.content.Context
import androidx.core.content.edit

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = true,
    val terminalFontSizeSp: Int = 13,
    val keepScreenOnInTerminal: Boolean = false,
)

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("codexdeck_settings", Context.MODE_PRIVATE)

    fun read(): AppSettings = AppSettings(
        theme = ThemeMode.entries.firstOrNull {
            it.name == preferences.getString("theme", ThemeMode.DARK.name)
        } ?: ThemeMode.DARK,
        dynamicColor = preferences.getBoolean("dynamic_color", true),
        terminalFontSizeSp = preferences.getInt("terminal_font_size", 13).coerceIn(9, 28),
        keepScreenOnInTerminal = preferences.getBoolean("keep_screen_on_terminal", false),
    )

    fun write(settings: AppSettings) {
        preferences.edit {
            putString("theme", settings.theme.name)
            putBoolean("dynamic_color", settings.dynamicColor)
            putInt("terminal_font_size", settings.terminalFontSizeSp.coerceIn(9, 28))
            putBoolean("keep_screen_on_terminal", settings.keepScreenOnInTerminal)
        }
    }

    fun lastHostId(): String? =
        preferences.getString("last_host_id", null)?.takeIf(String::isNotBlank)

    fun lastThreadId(): String? =
        preferences.getString("last_thread_id", null)?.takeIf(String::isNotBlank)

    fun rememberConnection(hostId: String, threadId: String?) {
        preferences.edit {
            putString("last_host_id", hostId)
            if (threadId == null) remove("last_thread_id")
            else putString("last_thread_id", threadId)
        }
    }

    fun clearConnection() {
        preferences.edit {
            remove("last_host_id")
            remove("last_thread_id")
        }
    }
}
