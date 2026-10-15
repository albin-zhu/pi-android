package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalImage;
import com.termux.terminal.TerminalRow;

/** Images are clipped to their surviving text cells and drawn after the shared text renderer. */
final class TerminalImageRenderer {
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF destination = new RectF();

    void render(TerminalEmulator emulator, Canvas canvas, int topRow, TerminalRenderer metrics) {
        TerminalBuffer screen = emulator.getScreen();
        float cellWidth = metrics.mFontWidth;
        int cellHeight = metrics.mFontLineSpacing;
        for (int row = topRow; row < topRow + emulator.mRows; row++) {
            TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            if (!line.hasImages()) continue;
            float top = metrics.mFontLineSpacingAndAscent + (row - topRow) * cellHeight;
            for (int column = 0; column < emulator.mColumns; ) {
                TerminalImage image = line.getImage(column);
                if (image == null || image.bitmap == null) { column++; continue; }
                int start = column, sourceX = line.getImageX(column), sourceY = line.getImageY(column);
                while (++column < emulator.mColumns && line.getImage(column) == image
                        && line.getImageX(column) == sourceX + column - start && line.getImageY(column) == sourceY) {}
                float left = (start - sourceX) * cellWidth;
                float imageTop = top - sourceY * cellHeight;
                // CSI 16 t integer pixel metrics are the sizing contract; only the origin uses font advance.
                destination.set(left, imageTop, left + image.width, imageTop + image.height);
                int saved = canvas.save();
                canvas.clipRect(start * cellWidth, top, column * cellWidth, top + cellHeight);
                canvas.drawBitmap(image.bitmap, null, destination, paint);
                canvas.restoreToCount(saved);
            }
        }
    }
}
