package cc.cherr.codexdeck.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun automaticLaunchReconnectIsOptIn() {
        assertFalse(AppSettings().autoReconnectOnLaunch)
        assertTrue(AppSettings(autoReconnectOnLaunch = true).autoReconnectOnLaunch)
    }

    @Test
    fun diagnosticLoggingIsOptIn() {
        assertFalse(AppSettings().diagnosticLoggingEnabled)
        assertTrue(AppSettings(diagnosticLoggingEnabled = true).diagnosticLoggingEnabled)
    }
}
