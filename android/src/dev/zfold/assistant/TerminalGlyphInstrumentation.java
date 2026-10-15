package dev.zfold.assistant;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;
import com.termux.view.TerminalRenderer;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Android Canvas pixels, not source assertions. PNG columns: natural / old X-only fit / renderer. */
public final class TerminalGlyphInstrumentation extends Instrumentation {
    private static final String ESC = "\033[";
    private static final int BLUE = 0xff1234a0;
    private static final int CYAN = 0xff00ffff;
    private static final TerminalOutput OUTPUT = new TerminalOutput() {
        @Override public void write(byte[] data, int offset, int count) {}
        @Override public void titleChanged(String oldTitle, String newTitle) {}
        @Override public void onCopyTextToClipboard(String text) {}
        @Override public void onPasteTextFromClipboard() {}
        @Override public void onBell() {}
        @Override public void onColorsChanged() {}
    };

    @Override public void onCreate(Bundle arguments) { start(); }

    @Override public void onStart() {
        Bundle report = new Bundle();
        String failure = "";
        report.putString("pngDirectory", getTargetContext().getCacheDir().getAbsolutePath());
        try {
            int reproduced = 0;
            StringBuilder metrics = new StringBuilder("size,glyph,advance/cell,naturalAspect,oldAspect,fixedAspect\n");
            String[] circles = {"\u25cf", "\u25cb", "\u25c9", "\u25ce"};
            for (int size : new int[]{12, 18, 24, 32, 48}) {
                TerminalRenderer renderer = new TerminalRenderer(size, Typeface.MONOSPACE);
                Paint paint = paint(size);
                int width = (int) Math.ceil(renderer.getFontWidth() * 6) + 2;
                int height = renderer.getFontLineSpacing() * 4;
                Bitmap sheet = Bitmap.createBitmap(width * 3, height * circles.length, Bitmap.Config.ARGB_8888);
                Canvas sheetCanvas = new Canvas(sheet);
                sheetCanvas.drawColor(Color.BLACK);
                try {
                    for (int row = 0; row < circles.length; row++) {
                        String glyph = circles[row];
                        check(WcWidth.width(glyph.codePointAt(0)) == 1, "Fixture must occupy one cell");
                        float advance = paint.measureText(glyph);
                        Bitmap natural = reference(renderer, paint, glyph, false, width, height);
                        Bitmap legacy = reference(renderer, paint, glyph, true, width, height);
                        TerminalEmulator emulator = emulator(renderer);
                        append(emulator, ESC + "?25l" + ESC + "2;3H" + glyph);
                        Bitmap fixed = render(renderer, emulator, width, height);
                        try {
                            Rect naturalInk = ink(natural, Color.WHITE);
                            Rect oldInk = ink(legacy, Color.WHITE);
                            Rect fixedInk = ink(fixed, Color.WHITE);
                            float naturalAspect = aspect(naturalInk);
                            float oldAspect = aspect(oldInk);
                            float fixedAspect = aspect(fixedInk);
                            metrics.append(size).append(',').append(glyph).append(',')
                                .append(advance / renderer.getFontWidth()).append(',')
                                .append(naturalAspect).append(',').append(oldAspect).append(',')
                                .append(fixedAspect).append('\n');
                            report.putString("circlePixelMetrics", metrics.toString());
                            sheetCanvas.drawBitmap(natural, 0, row * height, null);
                            sheetCanvas.drawBitmap(legacy, width, row * height, null);
                            sheetCanvas.drawBitmap(fixed, width * 2, row * height, null);
                            // Pixel quantization is material at tiny terminal sizes. At 24px and above,
                            // an actual circle must retain its natural aspect within two ink pixels.
                            if (size >= 24) {
                                float tolerance = 2.f / fixedInk.height();
                                check(Math.abs(fixedAspect - naturalAspect) <= tolerance,
                                    "Circle distorted at " + size + "px: " + glyph + " " + fixedAspect + " vs " + naturalAspect);
                                if (advance > renderer.getFontWidth() * 1.01f && oldAspect < naturalAspect - 0.01f) {
                                    check(Math.abs(fixedAspect - naturalAspect) < Math.abs(oldAspect - naturalAspect),
                                        "Uniform fit did not improve circle geometry");
                                    reproduced++;
                                }
                            }
                        } finally {
                            natural.recycle(); legacy.recycle(); fixed.recycle();
                        }
                    }
                } finally {
                    try { save(sheet, "terminal-glyphs-" + size + ".png"); }
                    finally { sheet.recycle(); }
                }
            }
            check(reproduced > 0, "Default Android monospace fallback did not expose a wider one-cell circle; inspect PNGs/metrics");
            report.putInt("reproducedFallbackCases", reproduced);
            report.putString("circlePixelMetrics", metrics.toString());
            cellGeometry(report);
            mixedLayout(report);
        } catch (Throwable error) {
            failure = error.toString();
            report.putString("error", failure);
        }
        finish(failure.isEmpty() ? -1 : 0, report);
    }

    private void cellGeometry(Bundle report) throws Exception {
        TerminalRenderer renderer = new TerminalRenderer(32, Typeface.MONOSPACE);
        int width = (int) Math.ceil(renderer.getFontWidth() * 6) + 2;
        int height = renderer.getFontLineSpacing() * 4;
        float cellWidth = renderer.getFontWidth();
        int cellHeight = renderer.getFontLineSpacing();
        float cellBottom = 3 * cellHeight + (int) Math.ceil(paint(32).ascent());
        // Invisible text isolates filled cell geometry from antialiased glyph pixels.
        TerminalEmulator emulator = emulator(renderer);
        append(emulator, ESC + "?25l" + ESC + "2;3H" + ESC + "48;2;18;52;160m" + ESC + "8m\u25cf");
        Bitmap background = render(renderer, emulator, width, height);
        try {
            Rect bounds = ink(background, BLUE);
            geometry(bounds, 2 * cellWidth, cellBottom, cellWidth, cellHeight, "background");
            save(background, "terminal-glyph-background.png");
        } finally { background.recycle(); }
        int[] modes = {2, 4, 6};
        String[] names = {"block", "underline", "bar"};
        for (int i = 0; i < modes.length; i++) {
            emulator = emulator(renderer);
            append(emulator, ESC + "2;3H" + ESC + "8m\u25cf" + ESC + "2;3H" + ESC + modes[i] + " q");
            Bitmap cursor = render(renderer, emulator, width, height);
            try {
                Rect bounds = ink(cursor, CYAN);
                geometry(bounds, 2 * cellWidth, cellBottom, i == 2 ? cellWidth / 4 : cellWidth,
                    i == 1 ? cellHeight / 4.f : cellHeight, names[i] + " cursor");
                save(cursor, "terminal-glyph-cursor-" + names[i] + ".png");
            } finally { cursor.recycle(); }
        }
        report.putBoolean("fullCellBackgroundAndCursorGeometry", true);
    }

    private void mixedLayout(Bundle report) {
        TerminalRenderer renderer = new TerminalRenderer(32, Typeface.MONOSPACE);
        int width = (int) Math.ceil(renderer.getFontWidth() * 6) + 2;
        int height = renderer.getFontLineSpacing() * 4;
        TerminalEmulator mixed = emulator(renderer);
        append(mixed, ESC + "?25l" + ESC + "2;1H" + ESC + "1;3;38;2;255;0;0mA\u4e2de\u0301" + ESC + "0mX");
        check(mixed.getCursorCol() == 5, "ASCII/CJK/combining sequence changed cell allocation");
        TerminalEmulator isolated = emulator(renderer);
        append(isolated, ESC + "?25l" + ESC + "2;5HX");
        Bitmap first = render(renderer, mixed, width, height);
        Bitmap again = render(renderer, mixed, width, height);
        Bitmap expected = render(renderer, isolated, width, height);
        try {
            check(ink(first, Color.WHITE).equals(ink(expected, Color.WHITE)),
                "Bold/italic/CJK/combining run displaced the following ASCII cell");
            check(first.sameAs(again), "Paint state changed repeated rendering");
        } finally { first.recycle(); again.recycle(); expected.recycle(); }
        report.putBoolean("boldItalicCombiningCjkAsciiLayout", true);
    }

    private static Paint paint(int size) {
        Paint result = new Paint();
        result.setTypeface(Typeface.MONOSPACE);
        result.setAntiAlias(true);
        result.setTextSize(size);
        result.setColor(Color.WHITE);
        return result;
    }

    private static Bitmap reference(TerminalRenderer renderer, Paint paint, String glyph,
                                    boolean oldFit, int width, int height) {
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawColor(Color.BLACK);
        float left = 2 * renderer.getFontWidth();
        if (oldFit) {
            float scale = renderer.getFontWidth() / paint.measureText(glyph);
            canvas.scale(scale, 1.f);
            left /= scale;
        }
        char[] text = glyph.toCharArray();
        canvas.drawTextRun(text, 0, text.length, 0, text.length, left,
            2 * renderer.getFontLineSpacing(), false, paint);
        return result;
    }

    private static TerminalEmulator emulator(TerminalRenderer renderer) {
        TerminalEmulator result = new TerminalEmulator(OUTPUT, 6, 3,
            (int) Math.ceil(renderer.getFontWidth()), renderer.getFontLineSpacing(), null, null);
        result.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = Color.WHITE;
        result.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = Color.BLACK;
        result.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] = CYAN;
        return result;
    }

    private static void append(TerminalEmulator emulator, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
    }

    private static Bitmap render(TerminalRenderer renderer, TerminalEmulator emulator, int width, int height) {
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawColor(Color.BLACK);
        renderer.render(emulator, canvas, 0, -1, -1, -1, -1);
        return result;
    }

    private static Rect ink(Bitmap bitmap, int color) {
        Rect bounds = new Rect(bitmap.getWidth(), bitmap.getHeight(), 0, 0);
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                int pixel = bitmap.getPixel(x, y);
                boolean matches = color == Color.WHITE
                    ? Color.red(pixel) > 128 && Color.green(pixel) > 128 && Color.blue(pixel) > 128
                    : pixel == color;
                if (matches) {
                    bounds.left = Math.min(bounds.left, x); bounds.top = Math.min(bounds.top, y);
                    bounds.right = Math.max(bounds.right, x + 1); bounds.bottom = Math.max(bounds.bottom, y + 1);
                }
            }
        }
        check(!bounds.isEmpty(), "No expected ink for color " + Integer.toHexString(color));
        return bounds;
    }

    private static float aspect(Rect bounds) { return (float) bounds.width() / bounds.height(); }

    private static void geometry(Rect bounds, float left, float bottom, float width, float height, String label) {
        check(Math.abs(bounds.left - left) <= 1.f && Math.abs(bounds.bottom - bottom) <= 1.f
                && Math.abs(bounds.width() - width) <= 2.f && Math.abs(bounds.height() - height) <= 2.f,
            label + " escaped cell geometry: " + bounds + " expected left/bottom/width/height "
                + left + "/" + bottom + "/" + width + "/" + height);
    }

    private void save(Bitmap image, String name) throws Exception {
        try (FileOutputStream output = new FileOutputStream(new File(getTargetContext().getCacheDir(), name))) {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, output), "PNG compression failed");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
