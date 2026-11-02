package com.termux.terminal;

import org.junit.Test;
import static org.junit.Assert.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class TransportSessionTest {
    static class Peer implements TerminalTransport {
        Listener listener;
        List<TerminalSize> sizes = new ArrayList<>();
        List<String> writes = new ArrayList<>();
        Runnable applied;
        int closes;
        public void start(TerminalSize size, Listener listener) { this.listener = listener; }
        public void write(byte[] b, int offset, int count) { writes.add(new String(b, offset, count, StandardCharsets.UTF_8)); }
        public void resize(TerminalSize size, Runnable applied) { sizes.add(size); this.applied = applied; }
        public void close() { closes++; }
        void emit(String text) { byte[] b = text.getBytes(StandardCharsets.UTF_8); listener.onBytes(b, b.length); }
    }
    final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    final Peer peer = new Peer();
    int finished;
    final TerminalSessionClient client = (TerminalSessionClient) Proxy.newProxyInstance(
        TerminalSessionClient.class.getClassLoader(), new Class[]{TerminalSessionClient.class},
        (proxy, method, args) -> { if (method.getName().equals("onSessionFinished")) finished++; return null; });
    final TerminalSession session = new TerminalSession(peer, 100, client, tasks::add);
    void pump() { Runnable next; while ((next = tasks.poll()) != null) next.run(); }
    void start() { session.updateSize(80, 24, 8, 16); peer.listener.onReady(); pump(); }

    @Test public void replaysPreReadySizeAndCoalescesPendingChanges() {
        session.updateSize(80, 24, 8, 16);
        session.updateSize(80, 12, 8, 16);
        assertTrue(peer.sizes.isEmpty());
        peer.listener.onReady(); pump();
        assertEquals(12, peer.sizes.get(0).rows);
        session.updateSize(80, 13, 8, 16);
        session.updateSize(80, 14, 8, 16);
        assertEquals(1, peer.sizes.size());
        peer.applied.run(); pump();
        assertEquals(14, peer.sizes.get(1).rows);
        peer.applied.run(); pump();
        session.updateSize(80, 14, 9, 17);
        assertEquals(2, peer.sizes.size());
        assertEquals(640, peer.sizes.get(1).windowWidthPixels());
        assertEquals(224, peer.sizes.get(1).windowHeightPixels());
    }
    @Test public void colorProbesReturnThroughTransport() {
        start(); peer.emit("\033]10;?\007\033]11;?\007"); pump();
        assertEquals(2, peer.writes.size());
        assertTrue(peer.writes.get(0).startsWith("\033]10;rgb:"));
        assertTrue(peer.writes.get(1).startsWith("\033]11;rgb:"));
    }
    @Test public void splitUtf8AndEofPreserveFinalOutput() {
        start(); byte[] bytes = "中文".getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) { peer.listener.onBytes(new byte[]{b}, 1); pump(); }
        peer.emit("END"); peer.listener.onClosed(7); pump();
        assertTrue(session.getEmulator().getScreen().getTranscriptText().contains("中文END"));
        assertEquals(7, session.getExitStatus());
        assertEquals(1, finished);
        session.finishIfRunning(); assertEquals(1, peer.closes);
    }
    @Test public void closeUnblocksProducerAndIgnoresLateReady() throws Exception {
        start();
        Thread producer = new Thread(() -> peer.listener.onBytes(new byte[200000], 200000));
        producer.setDaemon(true); producer.start();
        producer.join(50);
        session.finishIfRunning(); producer.join(2000);
        assertFalse(producer.isAlive());
        peer.listener.onReady(); peer.listener.onClosed(9); pump();
        assertFalse(session.isReady()); assertEquals(1, finished);
    }
}
