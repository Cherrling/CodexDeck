package com.termux.terminal;

import android.os.Handler;
import android.os.Looper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ShellDeck adaptation of Termux TerminalSession: keeps the View-facing API, replaces local
 * process/JNI ownership with TerminalTransport. Parser and renderer remain upstream code.
 * UI-facing methods and all emulator access belong to the main thread. Transport callbacks
 * marshal to it; only processToEmulator is called by the producer thread.
 */
public final class TerminalSession extends TerminalOutput {
    public final String mHandle = UUID.randomUUID().toString();
    public String mSessionName;
    private TerminalEmulator emulator;
    private TerminalSessionClient client;
    private final TerminalTransport transport;
    private final Integer transcriptRows;
    private final Executor main;
    private final ByteQueue incoming = new ByteQueue(64 * 1024);
    private final byte[] receiveBuffer = new byte[64 * 1024];
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final ResizeCoordinator resize = new ResizeCoordinator();
    private volatile boolean finished;
    private boolean ready;
    private boolean finishing;
    private int exitStatus;

    public TerminalSession(TerminalTransport transport, Integer transcriptRows, TerminalSessionClient client) {
        this(transport, transcriptRows, client, new Handler(Looper.getMainLooper())::post);
    }

    // Deterministic executor seam for ordering/close tests, not a second production mode.
    TerminalSession(TerminalTransport transport, Integer transcriptRows,
                    TerminalSessionClient client, Executor main) {
        this.transport = transport;
        this.transcriptRows = transcriptRows;
        this.client = client;
        this.main = main;
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        this.client = client;
        if (emulator != null) emulator.updateTerminalSessionClient(client);
    }

    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (finished) return;
        TerminalSize size = new TerminalSize(columns, rows, cellWidthPixels, cellHeightPixels);
        resize.request(size);
        if (emulator == null) {
            emulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, transcriptRows, client);
            try {
                transport.start(size, new TerminalTransport.Listener() {
                    @Override public void onReady() {
                        main.execute(() -> {
                            if (finished || ready) return;
                            ready = true;
                            resize.ready(size);
                            flushResize();
                            notifyScreenUpdate();
                        });
                    }
                    @Override public void onBytes(byte[] bytes, int length) { processToEmulator(bytes, length); }
                    @Override public void onClosed(int code) { main.execute(() -> finish(code, true)); }
                });
            } catch (RuntimeException failure) {
                finish(1, false);
            }
        } else {
            emulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
            flushResize();
            notifyScreenUpdate();
        }
    }

    private void flushResize() {
        if (finished) return;
        TerminalSize next = resize.next();
        if (next == null) return;
        try {
            transport.resize(next, () -> main.execute(() -> {
                if (finished) return;
                resize.acknowledge(next);
                flushResize();
            }));
        } catch (RuntimeException failure) {
            finish(1, false);
        }
    }

    private void processToEmulator(byte[] buffer, int length) {
        if (length < 0 || length > buffer.length) throw new IllegalArgumentException("Invalid input length");
        // Notify between chunks so a producer larger than the bounded queue cannot wait
        // forever for a consumer that has not yet been scheduled.
        for (int offset = 0; offset < length && !finished;) {
            int count = Math.min(4096, length - offset);
            if (!incoming.write(buffer, offset, count)) return;
            offset += count;
            if (drainScheduled.compareAndSet(false, true)) main.execute(this::drain);
        }
    }

    private void drain() {
        drainScheduled.set(false);
        if (finished) return;
        int length = incoming.read(receiveBuffer, false);
        if (length > 0 && emulator != null) {
            emulator.append(receiveBuffer, length);
            notifyScreenUpdate();
        }
    }

    @Override public void write(byte[] data, int offset, int count) {
        if (finished || !ready || count == 0) return;
        try { transport.write(data, offset, count); }
        catch (RuntimeException failure) { finish(1, false); }
    }

    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (!Character.isValidCodePoint(codePoint) || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new IllegalArgumentException("Invalid Unicode code point");
        }
        String value = (prependEscape ? "\u001b" : "") + new String(Character.toChars(codePoint));
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        write(bytes, 0, bytes.length);
    }

    private void finish(int code, boolean drainRemaining) {
        if (finished || finishing) return;
        finishing = true;
        if (drainRemaining) drain();
        finished = true;
        ready = false;
        exitStatus = code;
        incoming.close();
        try { transport.close(); }
        catch (RuntimeException ignored) { /* Already closed; do not crash UI during cleanup. */ }
        finally { client.onSessionFinished(this); }
    }

    public void finishIfRunning() { finish(0, false); }
    public boolean isRunning() { return !finished; }
    public boolean isReady() { return ready && !finished; }
    public int getExitStatus() { return exitStatus; }
    public TerminalEmulator getEmulator() { return emulator; }
    public String getTitle() { return emulator == null ? null : emulator.getTitle(); }
    public void reset() { if (emulator != null) { emulator.reset(); notifyScreenUpdate(); } }
    private void notifyScreenUpdate() { client.onTextChanged(this); }
    @Override public void titleChanged(String oldTitle, String newTitle) { client.onTitleChanged(this); }
    @Override public void onCopyTextToClipboard(String text) { client.onCopyTextToClipboard(this, text); }
    @Override public void onPasteTextFromClipboard() { client.onPasteTextFromClipboard(this); }
    @Override public void onBell() { client.onBell(this); }
    @Override public void onColorsChanged() { client.onColorsChanged(this); }
}
