// ShellDeck addition, GPL-3.0-only.
package com.termux.terminal;

/** Cell metrics stay local; transports use windowWidth/HeightPixels for protocol dimensions. */
public final class TerminalSize {
    public final int columns, rows, cellWidthPixels, cellHeightPixels;

    public TerminalSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (columns < 1 || rows < 1 || cellWidthPixels < 1 || cellHeightPixels < 1) {
            throw new IllegalArgumentException("Terminal dimensions must be positive");
        }
        this.columns = columns;
        this.rows = rows;
        this.cellWidthPixels = cellWidthPixels;
        this.cellHeightPixels = cellHeightPixels;
    }

    public int windowWidthPixels() { return Math.multiplyExact(columns, cellWidthPixels); }
    public int windowHeightPixels() { return Math.multiplyExact(rows, cellHeightPixels); }

    public boolean sameGrid(TerminalSize other) {
        return other != null && columns == other.columns && rows == other.rows;
    }
}
