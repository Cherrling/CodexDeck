// ShellDeck addition, GPL-3.0-only.
package com.termux.terminal;

/**
 * Nonblocking session transport. Implementations serialize writes and own their I/O resources.
 * Bytes must be copied before write() returns. start() allocates the initial remote geometry;
 * onReady() confirms it. resize() invokes onApplied only after successful protocol submission.
 * Failures end the transport via onClosed(), rather than falsely acknowledging a resize.
 * Listener callbacks are ordered; onBytes must run off the UI thread and may apply backpressure.
 * close() is nonblocking, idempotent and must unblock any active reader/writer.
 */
public interface TerminalTransport {
    interface Listener {
        void onReady();
        void onBytes(byte[] bytes, int length);
        void onClosed(int exitCode);
    }

    void start(TerminalSize initial, Listener listener);
    void write(byte[] bytes, int offset, int count);
    void resize(TerminalSize size, Runnable onApplied);
    void close();
}
