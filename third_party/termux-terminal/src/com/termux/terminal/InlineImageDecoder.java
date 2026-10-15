package com.termux.terminal;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.util.Arrays;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** OSC 1337 File= intake. Never interprets names as paths; only inline PNG/JPEG bytes. */
final class InlineImageDecoder {
    static final int MAX_ENCODED_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PIXELS = 4 * 1024 * 1024;
    private static final int MAX_DIMENSION = 8192;
    private final StringBuilder header = new StringBuilder();
    private byte[] data;
    private int length, quartet, digits, padding, characters;
    private boolean payload, rejected, ended;
    private String width = "auto", height = "auto";
    private boolean preserve = true;
    private int declaredSize = -1;

    void accept(int c) {
        if (rejected) return;
        if (!payload) {
            if (c == ':') {
                payload = true;
                parseHeader();
            } else if (c < 32 || c > 126 || header.length() >= 1024) {
                reject();
            } else header.append((char) c);
            return;
        }
        if (++characters > ((MAX_ENCODED_BYTES + 2) / 3) * 4 || ended) { reject(); return; }
        int value;
        if (c >= 'A' && c <= 'Z') value = c - 'A';
        else if (c >= 'a' && c <= 'z') value = c - 'a' + 26;
        else if (c >= '0' && c <= '9') value = c - '0' + 52;
        else if (c == '+') value = 62;
        else if (c == '/') value = 63;
        else if (c == '=' && digits >= 2) { value = 0; padding++; }
        else { reject(); return; }
        if (padding > 2 || (padding > 0 && c != '=')) { reject(); return; }
        quartet = (quartet << 6) | value;
        if (++digits == 4) {
            if ((padding == 1 && (quartet & 255) != 0) || (padding == 2 && (quartet & 65535) != 0)) { reject(); return; }
            int count = 3 - padding;
            if (length + count > MAX_ENCODED_BYTES) { reject(); return; }
            try {
                if (data == null) data = new byte[8192];
                else if (length + count > data.length) data = Arrays.copyOf(data, Math.min(MAX_ENCODED_BYTES, data.length * 2));
            } catch (OutOfMemoryError exhausted) { reject(); return; }
            data[length++] = (byte) (quartet >> 16);
            if (count > 1) data[length++] = (byte) (quartet >> 8);
            if (count > 2) data[length++] = (byte) quartet;
            ended = padding > 0;
            digits = quartet = 0;
        }
    }

    private void parseHeader() {
        boolean inline = false;
        try {
            for (String field : header.toString().split(";", -1)) {
                int separator = field.indexOf('=');
                if (separator <= 0) { reject(); return; }
                String key = field.substring(0, separator), value = field.substring(separator + 1);
                switch (key) {
                    case "inline": inline = value.equals("1"); if (!inline) { reject(); return; } break;
                    case "width": width = value; break;
                    case "height": height = value; break;
                    case "preserveAspectRatio":
                        if (!value.equals("0") && !value.equals("1")) { reject(); return; }
                        preserve = value.equals("1"); break;
                    case "size":
                        declaredSize = Integer.parseInt(value);
                        if (declaredSize <= 0 || declaredSize > MAX_ENCODED_BYTES) { reject(); return; }
                        break;
                    case "name": break; // Opaque metadata, never opened or decoded.
                    default: reject(); return;
                }
            }
            if (!inline) reject();
        } catch (NumberFormatException invalid) { reject(); }
    }

    private void reject() { rejected = true; data = null; }

    TerminalImage finish(TerminalImage.Store store, int columns, int rows, int cellWidth, int cellHeight) {
        if (rejected || !payload || digits != 0 || length < 8 || data == null || (declaredSize >= 0 && declaredSize != length)
                || cellWidth <= 0 || cellHeight <= 0) return null;
        boolean png = data[0] == (byte) 137 && data[1] == 80 && data[2] == 78 && data[3] == 71
                && data[4] == 13 && data[5] == 10 && data[6] == 26 && data[7] == 10;
        boolean jpeg = data[0] == (byte) 255 && data[1] == (byte) 216 && data[2] == (byte) 255;
        if (!png && !jpeg) return null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > MAX_DIMENSION || options.outHeight > MAX_DIMENSION
                    || (long) options.outWidth * options.outHeight > MAX_PIXELS) return null;
            float w = dimension(width, columns, cellWidth), h = dimension(height, rows, cellHeight);
            if (w == 0 || h == 0) return null;
            float ratio = (float) options.outWidth / options.outHeight;
            if (w < 0 && h < 0) { w = options.outWidth; h = options.outHeight; }
            else if (w < 0) w = h * ratio;
            else if (h < 0) h = w / ratio;
            else if (preserve) { float scale = Math.min(w / options.outWidth, h / options.outHeight); w = options.outWidth * scale; h = options.outHeight * scale; }
            // Native sizing fits terminal width without distorting the image or allocating extra pixels.
            if (w > columns * (float) cellWidth) { h *= columns * (float) cellWidth / w; w = columns * (float) cellWidth; }
            if (w <= 0 || h <= 0 || w > MAX_DIMENSION || h > MAX_DIMENSION
                    || Math.ceil(w / cellWidth) * Math.ceil(h / cellHeight) > 65536) return null;
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(data, 0, length);
            byte[] digest = hash.digest();
            TerminalImage cached = store.find(digest, w, h, cellWidth, cellHeight);
            if (cached != null) return cached;
            store.prepare(options.outWidth * options.outHeight * 4);
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, length, options);
            if (bitmap == null) return null;
            return store.add(bitmap, w, h, cellWidth, cellHeight, digest);
        } catch (IllegalArgumentException | NoSuchAlgorithmException | OutOfMemoryError invalid) {
            return null;
        } finally { data = null; }
    }

    private static float dimension(String value, int cells, int pixels) {
        if (value.equals("auto")) return -1;
        int multiplier = pixels;
        boolean percent = value.endsWith("%");
        if (percent) value = value.substring(0, value.length() - 1);
        else if (value.endsWith("px")) { multiplier = 1; value = value.substring(0, value.length() - 2); }
        if (value.isEmpty() || value.length() > 8) return 0;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < '0' || value.charAt(i) > '9') return 0;
        int number = Integer.parseInt(value);
        if (number <= 0 || number > MAX_DIMENSION) return 0;
        if (percent) return number <= 100 ? cells * (float) pixels * number / 100 : 0;
        return number * (float) multiplier;
    }
}
