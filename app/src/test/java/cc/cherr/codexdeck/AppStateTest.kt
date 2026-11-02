package cc.cherr.codexdeck

import cc.cherr.codexdeck.protocol.CodexThread
import cc.cherr.codexdeck.protocol.ThreadRuntimeStatus
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AppStateTest {
    @Test
    fun groupsThreadsByExactRemoteWorkingDirectory() {
        val threads = listOf(
            thread("1", "/srv/api", 10),
            thread("2", "/srv/web", 30),
            thread("3", "/srv/api", 20),
        )

        val projects = groupThreads(threads)

        assertEquals(listOf("/srv/web", "/srv/api"), projects.map { it.path })
        assertEquals(listOf("3", "1"), projects[1].threads.map { it.id })
    }

    private fun thread(id: String, cwd: String, updatedAt: Long) = CodexThread(
        id = id,
        sessionId = id,
        title = id,
        preview = "",
        cwd = cwd,
        modelProvider = null,
        createdAt = updatedAt - 1,
        updatedAt = updatedAt,
        status = ThreadRuntimeStatus("idle"),
        ephemeral = false,
        forkedFromId = null,
        gitBranch = null,
        gitOriginUrl = null,
        raw = JsonObject(emptyMap()),
    )
}
