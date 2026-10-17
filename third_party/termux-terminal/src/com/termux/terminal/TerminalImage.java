package com.termux.terminal;

import android.graphics.Bitmap;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.IdentityHashMap;

/** Decoded inline image, shared by the text cells which own its placement. */
public final class TerminalImage {
    public Bitmap bitmap;
    public final float width, height;
    public final int cellWidth, cellHeight;
    private final Store store;
    private int references = 1;
    private final byte[] digest;
    private final IdentityHashMap<TerminalRow, int[]> owners = new IdentityHashMap<>();

    TerminalImage(Store store, Bitmap bitmap, float width, float height, int cellWidth, int cellHeight, byte[] digest) {
        this.store = store;
        this.bitmap = bitmap;
        this.width = width;
        this.height = height;
        this.cellWidth = cellWidth;
        this.cellHeight = cellHeight;
        this.digest = digest;
    }

    void retain() { references++; }
    void addOwner(TerminalRow row) {
        int[] count = owners.get(row);
        if (count == null) owners.put(row, new int[]{1});
        else count[0]++;
    }
    void removeOwner(TerminalRow row) {
        int[] count = owners.get(row);
        if (count != null && --count[0] == 0) owners.remove(row);
    }
    void release() { if (--references == 0) store.remove(this); }

    /** One budget for both main and alternate buffers; eviction leaves harmless empty cells. */
    static final class Store {
        private static final int MAX_BYTES = 32 * 1024 * 1024;
        private static final int MAX_IMAGES = 64;
        private final ArrayDeque<TerminalImage> images = new ArrayDeque<>();
        private int bytes;

        void prepare(int requiredBytes) {
            while (!images.isEmpty() && (bytes + requiredBytes > MAX_BYTES || images.size() >= MAX_IMAGES)) remove(images.peekFirst());
        }

        TerminalImage add(Bitmap bitmap, float width, float height, int cellWidth, int cellHeight, byte[] digest) {
            int size = bitmap.getAllocationByteCount();
            prepare(size);
            TerminalImage image = new TerminalImage(this, bitmap, width, height, cellWidth, cellHeight, digest);
            images.addLast(image);
            bytes += size;
            return image;
        }

        TerminalImage find(byte[] digest, float width, float height, int cellWidth, int cellHeight) {
            for (TerminalImage image : images) {
                if (image.width == width && image.height == height && image.cellWidth == cellWidth
                        && image.cellHeight == cellHeight && Arrays.equals(image.digest, digest)) {
                    image.retain();
                    return image;
                }
            }
            return null;
        }

        void remove(TerminalImage image) {
            if (image.bitmap == null) return;
            images.remove(image);
            bytes -= image.bitmap.getAllocationByteCount();
            image.bitmap.recycle();
            image.bitmap = null;
            if (!image.owners.isEmpty()) {
                TerminalRow[] rows = image.owners.keySet().toArray(new TerminalRow[0]);
                image.owners.clear();
                for (TerminalRow row : rows) row.clearImage(image);
            }
        }

        void clear() { while (!images.isEmpty()) remove(images.peekFirst()); }
    }
}
