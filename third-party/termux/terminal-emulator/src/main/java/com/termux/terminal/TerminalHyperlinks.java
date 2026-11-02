package com.termux.terminal;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded OSC 8 target storage. Evicted IDs never resolve to a different target. */
final class TerminalHyperlinks {
    private final LinkedHashMap<Integer, String> links = new LinkedHashMap<>();
    private int nextId = 1;
    private int characters;

    int add(String uri) {
        if (uri.isEmpty() || uri.length() > 4096 || nextId == Integer.MAX_VALUE) return 0;
        for (int i = 0; i < uri.length(); i++) if (Character.isISOControl(uri.charAt(i))) return 0;
        // Repeated TUI redraws of the same target must not evict the rest of the visible links.
        for (Map.Entry<Integer, String> entry : links.entrySet()) if (entry.getValue().equals(uri)) return entry.getKey();
        while (links.size() >= 256 || characters + uri.length() > 65536) {
            Map.Entry<Integer, String> oldest = links.entrySet().iterator().next();
            characters -= oldest.getValue().length();
            links.remove(oldest.getKey());
        }
        int id = nextId++;
        links.put(id, uri);
        characters += uri.length();
        return id;
    }

    String get(int id) { return links.get(id); }
    void clear() { links.clear(); characters = 0; } // Do not reuse IDs held by scrollback or the other buffer.
}
