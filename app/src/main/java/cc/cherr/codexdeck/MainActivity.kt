package cc.cherr.codexdeck

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
        val controller = (application as CodexDeckApplication).runtime
        setContent {
            val state = controller.state.collectAsStateWithLifecycle().value
            CodexDeckTheme(state.settings) {
                CodexDeckApp(controller)
            }
        }
    }
}
