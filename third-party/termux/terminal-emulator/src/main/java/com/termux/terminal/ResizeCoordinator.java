// ShellDeck addition, GPL-3.0-only.
package com.termux.terminal;

/** Single-thread-owned desired / acknowledged / in-flight geometry. No timers or polling. */
public final class ResizeCoordinator {
    private TerminalSize desired, acknowledged, inFlight;
    private boolean ready;

    public void request(TerminalSize size) { desired = size; }

    public void ready(TerminalSize initial) {
        acknowledged = initial;
        inFlight = null;
        ready = true;
    }

    public TerminalSize next() {
        if (!ready || desired == null || inFlight != null || desired.sameGrid(acknowledged)) return null;
        inFlight = desired;
        return inFlight;
    }

    public void acknowledge(TerminalSize size) {
        if (size != inFlight) throw new IllegalStateException("Unexpected resize acknowledgement");
        acknowledged = size;
        inFlight = null;
    }
}
