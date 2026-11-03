package cc.cherr.codexdeck.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandCardTest {
    @Test
    fun commandSummaryUsesFirstNonBlankLineAndBoundsLongCommands() {
        assertEquals(
            "cargo test",
            commandExecutionSummary("\n\n  cargo test  \noutput follows"),
        )
        assertEquals("（无命令文本）", commandExecutionSummary("\n  \n"))
        assertEquals(180, commandExecutionSummary("x".repeat(500)).length)
    }

    @Test
    fun commandStatusUsesMobileReadableLabelsAndPreservesUnknownStatuses() {
        assertEquals("运行中", commandExecutionStatusLabel("inProgress"))
        assertEquals("完成", commandExecutionStatusLabel("completed"))
        assertEquals("失败", commandExecutionStatusLabel("failed"))
        assertEquals("futureStatus", commandExecutionStatusLabel("futureStatus"))
        assertEquals("命令", commandExecutionStatusLabel(""))
        assertTrue(commandExecutionStatusLabel("declined").contains("拒绝"))
    }
}
