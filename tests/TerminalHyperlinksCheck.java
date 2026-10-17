package com.termux.terminal;

import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/** Standalone core check; add --images on Android to exercise real PNG decoding and placement. */
public final class TerminalHyperlinksCheck {
    private static final String ESC = "\033";
    private static final String ST = ESC + "\\";
    private static final String CLOSE = ESC + "]8;;" + ST;
    private static final String FIRST = "https://example.com/a;b?q=1";
    private static final String SECOND = "file:///tmp/linked%20file.png";
    private static final TerminalOutput OUTPUT = new TerminalOutput() {
        @Override public void write(byte[] data, int offset, int count) {}
        @Override public void titleChanged(String oldTitle, String newTitle) {}
        @Override public void onCopyTextToClipboard(String text) {}
        @Override public void onPasteTextFromClipboard() {}
        @Override public void onBell() {}
        @Override public void onColorsChanged() {}
    };

    private static TerminalEmulator terminal(int columns, int rows) {
        return new TerminalEmulator(OUTPUT, columns, rows, 10, 20, 100, null);
    }

    private static String open(String target) {
        return ESC + "]8;id=attachment:unknown=ignored;" + target + ST;
    }

    private static void append(TerminalEmulator terminal, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        terminal.append(bytes, bytes.length);
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual))
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }

    private static void link(TerminalBuffer buffer, int column, int row, String target) {
        equal(target, buffer.getHyperlink(column, row), "hyperlink at " + column + "," + row);
    }

    private static void parser() {
        TerminalEmulator terminal = terminal(20, 4);
        String sequence = open(FIRST) + ESC + "[31mA界\u0301" + CLOSE + "Z";
        for (byte b : sequence.getBytes(StandardCharsets.UTF_8)) terminal.append(new byte[]{b}, 1);
        TerminalBuffer buffer = terminal.getScreen();
        link(buffer, 0, 0, FIRST);
        link(buffer, 1, 0, FIRST);
        link(buffer, 2, 0, FIRST);
        link(buffer, 3, 0, null);
        equal("A界\u0301Z", buffer.getSelectedText(0, 0, 3, 0), "fragmented UTF-8 label");
        equal(1, TextStyle.decodeForeColor(buffer.getStyleAt(0, 0)), "ANSI color retained");
        append(terminal, ESC + "]8;;" + SECOND + "\007B" + ESC + "]8;;\007C");
        link(buffer, 4, 0, SECOND);
        link(buffer, 5, 0, null);
        append(terminal, open(FIRST) + "e" + CLOSE + "\u0301");
        link(buffer, 6, 0, FIRST);
        append(terminal, "f" + open(SECOND) + "\u0301" + CLOSE);
        link(buffer, 7, 0, null);
        link(buffer, -1, 0, null);
        link(buffer, 20, 0, null);
        link(buffer, 0, -1, null);
        link(buffer, 0, 4, null);
        equal(null, buffer.getImage(-1, 0), "invalid image column");
        equal(null, buffer.getImage(0, -1), "invalid image row");
        buffer.mLines[buffer.externalToInternalRow(3)] = null;
        link(buffer, 0, 3, null);
        equal(null, buffer.getImage(0, 3), "unallocated image row");
        equal(null, buffer.mLines[buffer.externalToInternalRow(3)], "lookups do not allocate");
    }

    private static void invalidTargets() {
        String maximum = "https://example.com/" + "x".repeat(4096 - "https://example.com/".length());
        TerminalEmulator terminal = terminal(12, 3);
        append(terminal, open(maximum) + "a" + CLOSE);
        link(terminal.getScreen(), 0, 0, maximum);
        String[] invalid = {maximum + "x", "https://example.com/a\nb", "https://example.com/a\tb",
            "https://example.com/a\u0000b", "https://example.com/a\u007fb", "https://example.com/a\u0085b",
            "https://example.com/a" + ESC + "xb", "https://example.com/" + "x".repeat(9000)};
        for (String target : invalid) {
            terminal = terminal(12, 3);
            append(terminal, open(FIRST) + "a" + open(target) + "b" + CLOSE);
            link(terminal.getScreen(), 0, 0, FIRST);
            link(terminal.getScreen(), 1, 0, null);
            equal("ab", terminal.getScreen().getSelectedText(0, 0, 1, 0), "rejected target stays hidden");
        }
        terminal = terminal(12, 3);
        append(terminal, open(FIRST) + "a" + ESC + "]8;missing-uri-separator" + ST + "b");
        link(terminal.getScreen(), 1, 0, null);
        append(terminal, open(FIRST) + ESC + "]8;;incomplete\030c");
        link(terminal.getScreen(), 2, 0, null);
    }

    private static void copiesAndErasure() throws Exception {
        TerminalBuffer buffer = new TerminalBuffer(8, 20, 4);
        for (int x = 0; x < 6; x++) buffer.setChar(x, 0, 'a' + x, TextStyle.NORMAL, x < 3 ? FIRST : SECOND);
        buffer.blockCopy(0, 0, 6, 1, 2, 0);
        for (int x = 0; x < 6; x++) link(buffer, x + 2, 0, x < 3 ? FIRST : SECOND);
        buffer.blockCopy(2, 0, 6, 1, 0, 0);
        for (int x = 0; x < 6; x++) link(buffer, x, 0, x < 3 ? FIRST : SECOND);
        buffer.blockCopy(0, 0, 8, 1, 0, 1);
        for (int x = 0; x < 8; x++) link(buffer, x, 1, buffer.getHyperlink(x, 0));
        buffer.blockCopy(0, 2, 8, 1, 0, 1);
        for (int x = 0; x < 8; x++) link(buffer, x, 1, null);
        buffer.setChar(1, 1, '界', TextStyle.NORMAL, FIRST);
        buffer.setChar(2, 1, 'x', TextStyle.NORMAL);
        link(buffer, 1, 1, null);
        link(buffer, 2, 1, null);
        buffer.setChar(2, 1, '界', TextStyle.NORMAL, FIRST);
        buffer.setChar(2, 1, 'x', TextStyle.NORMAL, SECOND);
        link(buffer, 2, 1, SECOND);
        link(buffer, 3, 1, null);
        buffer.setChar(4, 1, '界', TextStyle.NORMAL, FIRST);
        buffer.setChar(3, 1, '界', TextStyle.NORMAL, SECOND);
        link(buffer, 3, 1, SECOND);
        link(buffer, 4, 1, SECOND);
        link(buffer, 5, 1, null);
        buffer.blockCopy(4, 1, 1, 1, 6, 1);
        link(buffer, 6, 1, null); // A copied half-glyph becomes an unlinked space.
        buffer.blockSet(0, 0, 8, 4, ' ', TextStyle.NORMAL);
        Field metadata = TerminalRow.class.getDeclaredField("mHyperlinks");
        metadata.setAccessible(true);
        for (int row = 0; row < 4; row++)
            equal(null, metadata.get(buffer.mLines[buffer.externalToInternalRow(row)]), "empty rows release targets and arrays");
        TerminalEmulator terminal = terminal(12, 3);
        append(terminal, open(FIRST) + "abcdef" + CLOSE + ESC + "[1;2H" + ESC + "[2P");
        link(terminal.getScreen(), 1, 0, FIRST);
        append(terminal, ESC + "[2@" );
        link(terminal.getScreen(), 1, 0, null);
        link(terminal.getScreen(), 3, 0, FIRST);
        append(terminal, ESC + "[2K");
        for (int x = 0; x < 12; x++) link(terminal.getScreen(), x, 0, null);
    }

    private static void scrollingReflowAndReset() {
        TerminalEmulator terminal = terminal(8, 3);
        append(terminal, open(FIRST) + "A界\u0301BCDEF" + CLOSE + "\r\nx\r\ny\r\nz");
        TerminalBuffer buffer = terminal.getScreen();
        link(buffer, 0, -1, FIRST);
        terminal.resize(5, 3, 10, 20);
        int linkedCells = 0;
        for (int row = -buffer.getActiveTranscriptRows(); row < buffer.mScreenRows; row++) {
            for (int x = 0; x < buffer.mColumns; x++) {
                String target = buffer.getHyperlink(x, row);
                if (target != null) {
                    equal(FIRST, target, "reflow target");
                    linkedCells++;
                }
            }
        }
        equal(8, linkedCells, "reflow preserves wide and combining cells");
        terminal.resize(5, 4, 10, 20);
        append(terminal, ESC + "[?1049h" + ESC + "[H" + open(SECOND) + "alt" + CLOSE);
        TerminalBuffer alternate = terminal.getScreen();
        link(alternate, 0, 0, SECOND);
        append(terminal, ESC + "[?1049l");
        equal(buffer, terminal.getScreen(), "main buffer restored");
        terminal.reset();
        for (TerminalBuffer screen : new TerminalBuffer[]{buffer, alternate})
            for (int row = -screen.getActiveTranscriptRows(); row < screen.mScreenRows; row++)
                for (int x = 0; x < screen.mColumns; x++) link(screen, x, row, null);
        append(terminal, "r");
        for (int x = 0; x < buffer.mColumns; x++) link(buffer, x, terminal.getCursorRow(), null);

        buffer = new TerminalBuffer(6, 20, 4);
        buffer.setChar(4, 0, ' ', TextStyle.NORMAL, FIRST);
        buffer.setChar(0, 1, 'x', TextStyle.NORMAL);
        buffer.resize(3, 4, 20, new int[]{0, 1}, TextStyle.NORMAL, false);
        link(buffer, 1, 1, FIRST); // Linked trailing whitespace must not be trimmed by reflow.
        buffer.scrollDownOneLine(0, 4, TextStyle.NORMAL);
        link(buffer, 1, 0, FIRST);
        buffer.scrollDownOneLine(0, 4, TextStyle.NORMAL);
        link(buffer, 1, -1, FIRST);
        buffer.clearTranscript();
        link(buffer, 1, -1, null);
    }

    private static void images() {
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)) throw new AssertionError("PNG encoding failed");
        bitmap.recycle();
        TerminalEmulator terminal = terminal(8, 4);
        append(terminal, open(SECOND) + ESC + "]1337;File=inline=1;width=2;height=2;preserveAspectRatio=0:"
            + Base64.getEncoder().encodeToString(png.toByteArray()) + ST + CLOSE);
        TerminalBuffer buffer = terminal.getScreen();
        TerminalImage image = buffer.getImage(0, 0);
        if (image == null) throw new AssertionError("inline PNG did not place");
        for (int row = 0; row < 2; row++) {
            for (int x = 0; x < 2; x++) {
                link(buffer, x, row, SECOND);
                equal(image, buffer.getImage(x, row), "linked image cell");
            }
        }
        buffer.blockCopy(0, 0, 2, 1, 2, 0);
        buffer.scrollDownOneLine(0, 4, TextStyle.NORMAL);
        link(buffer, 3, -1, SECOND);
        equal(image, buffer.getImage(3, -1), "image follows scrollback");
        terminal.resize(5, 4, 10, 20);
        int cells = 0;
        for (int row = -buffer.getActiveTranscriptRows(); row < buffer.mScreenRows; row++) {
            for (int x = 0; x < buffer.mColumns; x++) {
                if (buffer.getImage(x, row) != null) {
                    link(buffer, x, row, SECOND);
                    cells++;
                }
            }
        }
        equal(6, cells, "image and hyperlink cells survive reflow");
        buffer.blockSet(0, 0, buffer.mColumns, buffer.mScreenRows, ' ', TextStyle.NORMAL);
        for (int row = 0; row < buffer.mScreenRows; row++) {
            for (int x = 0; x < buffer.mColumns; x++) {
                link(buffer, x, row, null);
                equal(null, buffer.getImage(x, row), "image erased with hyperlink");
            }
        }
        terminal.reset();
    }

    public static void main(String[] args) throws Exception {
        parser();
        invalidTargets();
        copiesAndErasure();
        scrollingReflowAndReset();
        if (args.length == 1 && args[0].equals("--images")) images();
        System.out.println("terminal OSC 8 parser, cell metadata, copy, erase, scroll, reflow and reset checks passed");
    }
}
