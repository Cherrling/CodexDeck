package cc.cherr.codexdeck

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.cherr.codexdeck.ui.CodexDeckApp
import cc.cherr.codexdeck.ui.CodexDeckTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
        }
        val controller = (application as CodexDeckApplication).runtime
        controller.restoreLastConnection()
        setContent {
            val state = controller.state.collectAsStateWithLifecycle().value
            CodexDeckTheme(state.settings) {
                CodexDeckApp(controller)
            }
        }
    }
}
