package com.termux.terminal;

public class HyperlinkTest extends TerminalTestCase {
    private static final String OPEN = "\033]8;id=test;https://example.com/a\033\\";
    private static final String CLOSE = "\033]8;;\033\\";

    public void testSplitSequenceWideCombiningAndErase() {
        withTerminalSized(12, 4);
        for (char ch : OPEN.toCharArray()) enterString(String.valueOf(ch));
        enterString("中e\u0301" + CLOSE + "z");
        for (int x = 0; x < 3; x++) assertEquals("https://example.com/a", mTerminal.getHyperlinkAt(x, 0));
        assertNull(mTerminal.getHyperlinkAt(3, 0));
        enterString("\r\033[Cx"); // Overwrite the second half of 中.
        assertNull(mTerminal.getHyperlinkAt(0, 0));
        assertNull(mTerminal.getHyperlinkAt(1, 0));
        assertEquals("https://example.com/a", mTerminal.getHyperlinkAt(2, 0));
        enterString("\033[K");
        assertNull(mTerminal.getHyperlinkAt(2, 0));
        assertInvariants();
    }

    public void testCopyInsertDeleteAndWideOverwrite() {
        withTerminalSized(12, 4);
        enterString(OPEN + "abcd" + CLOSE + "\r\033[2@");
        assertNull(mTerminal.getHyperlinkAt(0, 0));
        for (int x = 2; x < 6; x++) assertEquals("https://example.com/a", mTerminal.getHyperlinkAt(x, 0));
        enterString("\033[2P");
        for (int x = 0; x < 4; x++) assertEquals("https://example.com/a", mTerminal.getHyperlinkAt(x, 0));
        assertNull(mTerminal.getHyperlinkAt(4, 0));
        enterString("\r" + OPEN + "中" + CLOSE + "\rx");
        assertNull(mTerminal.getHyperlinkAt(0, 0));
        assertNull(mTerminal.getHyperlinkAt(1, 0));
        assertInvariants();
    }

    public void testReflowScrollAlternateBufferAndReset() {
        withTerminalSized(8, 4);
        enterString(OPEN + "abcdefghij" + CLOSE);
        resize(5, 4);
        for (int x = 0; x < 5; x++) for (int y = 0; y < 2; y++)
            assertEquals("https://example.com/a", mTerminal.getHyperlinkAt(x, y));
        enterString("\r\n\r\n\r\n");
        assertEquals("https://example.com/a", mTerminal.getHyperlinkAt(0, -1));
        enterString("\033[?1049h");
        assertNull(mTerminal.getHyperlinkAt(0, 0));
        enterString(OPEN + "x" + CLOSE + "\033[?1049l");
        assertEquals("https://example.com/a", mTerminal.getHyperlinkAt(0, -1));
        enterString("\033c");
        assertNull(mTerminal.getHyperlinkAt(0, -1));
        enterString("z");
        assertNull(mTerminal.getHyperlinkAt(0, 0));
        assertInvariants();
    }

    public void testBelMalformedAndBoundedTargets() {
        withTerminalSized(12, 4);
        enterString("\033]8;;https://a.test\007A\033]8;;https://b.test\007B\033]8;;\007C");
        assertEquals("https://a.test", mTerminal.getHyperlinkAt(0, 0));
        assertEquals("https://b.test", mTerminal.getHyperlinkAt(1, 0));
        assertNull(mTerminal.getHyperlinkAt(2, 0));
        enterString(OPEN + "\033]8;broken\007D");
        assertNull(mTerminal.getHyperlinkAt(3, 0));
        TerminalHyperlinks links = new TerminalHyperlinks();
        int old = links.add("https://old.test");
        for (int i = 0; i < 300; i++) links.add("https://example.com/" + i);
        assertNull(links.get(old));
        int id = links.add("https://new.test");
        assertEquals("https://new.test", links.get(id));
        links.clear();
        assertNull(links.get(id));
        assertTrue(links.add("https://another.test") > id);
        assertEquals(0, links.add("x".repeat(4097)));
        assertEquals(0, links.add("https://bad.test/\n"));
    }
}
