package cc.cherr.codexdeck.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DiagnosticLogStoreTest {
    @Test
    fun disabledStoreDoesNotCreateOrWriteAFile() = runBlocking {
        val file = Files.createTempFile("codexdeck-diagnostics", ".log").toFile().also {
            it.delete()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = DiagnosticLogStore(file = file, scope = scope)
        try {
            store.log("connection", "this must not be retained")
            store.logFatal("crash", "this crash must not be retained")
            assertEquals("", store.read())
            assertFalse(file.exists())
        } finally {
            store.close()
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun disablingStopsNewWritesButKeepsExistingLog() = runBlocking {
        val file = Files.createTempFile("codexdeck-diagnostics", ".log").toFile().also {
            it.delete()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = DiagnosticLogStore(file = file, scope = scope)
        try {
            store.setEnabled(true)
            store.log("connection", "first entry")
            store.logFatal("crash", "fatal entry")
            val first = store.read()
            assertTrue(first.contains("first entry"))
            assertTrue(first.contains("fatal entry"))

            store.setEnabled(false)
            store.log("connection", "entry after disable")
            val second = store.read()
            assertEquals(first, second)
            assertFalse(second.contains("entry after disable"))
        } finally {
            store.close()
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun sanitizerRemovesCredentialShapesAndPrivateKeys() {
        val longSecret = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo".repeat(3) + "=="
        val raw = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            c2VjcmV0LWtleS1ib2R5
            -----END OPENSSH PRIVATE KEY-----
            Authorization: Bearer bearer-secret-value
            password=hunter2
            access_token="access-secret"
            client_secret: client-secret
            ssh://alice:uri-password@example.com
            payload=$longSecret
        """.trimIndent()

        val sanitized = sanitizeDiagnosticText(raw)

        assertTrue(sanitized.contains("[REDACTED PRIVATE KEY]"))
        assertTrue(sanitized.contains("[REDACTED]"))
        listOf(
            "c2VjcmV0LWtleS1ib2R5",
            "bearer-secret-value",
            "hunter2",
            "access-secret",
            "client-secret",
            "uri-password",
            longSecret,
        ).forEach { secret ->
            assertFalse("sanitized log leaked $secret", sanitized.contains(secret))
        }
    }

    @Test
    fun storeKeepsBothByteAndLineBounds() = runBlocking {
        val file = Files.createTempFile("codexdeck-diagnostics", ".log").toFile().also {
            it.delete()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = DiagnosticLogStore(
            file = file,
            scope = scope,
            maxBytes = 512,
            maxLines = 20,
        )
        try {
            store.setEnabled(true)
            repeat(40) { index ->
                store.log("test", "entry-$index-" + "x".repeat(96))
            }
            val result = store.read()
            assertTrue(result.toByteArray(Charsets.UTF_8).size <= 512)
            assertTrue(result.lineSequence().count { it.isNotEmpty() } <= 20)
            assertTrue(result.contains("entry-39-"))
            assertFalse(result.contains("entry-0-"))
        } finally {
            store.close()
            scope.cancel()
            file.delete()
        }
    }

    @Test
    fun genericBoundingKeepsNewestCompleteLines() {
        val input = (1..50).joinToString("\n") { "line-$it-" + "x".repeat(30) }
        val bounded = boundDiagnosticLog(input, maxBytes = 400, maxLines = 8)

        assertTrue(bounded.toByteArray(Charsets.UTF_8).size <= 400)
        assertTrue(bounded.lineSequence().count() <= 8)
        assertTrue(bounded.contains("line-50-"))
        assertFalse(bounded.contains("line-1-"))
    }
}
