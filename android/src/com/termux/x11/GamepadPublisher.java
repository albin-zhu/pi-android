package com.termux.x11;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;

/** Single writer of the versioned guest Xbox state. No Android or network dependency. */
final class GamepadPublisher implements AutoCloseable {
    static final int SIZE = 64, MAGIC = 0x4450475a, VERSION = 1;
    private final ByteBuffer packet = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer sequenceBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
    private final FileChannel channel;
    private final FileLock writer;
    private int sequence;
    private boolean closed;

    GamepadPublisher(File directory, int pid, long now) throws IOException {
        Files.createDirectories(directory.toPath());
        if (Files.isSymbolicLink(directory.toPath())) throw new IOException("Gamepad directory must not be a symlink");
        privateAccess(directory, true);
        File state = new File(directory, "state");
        channel = FileChannel.open(state.toPath(), StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired = null;
        try {
            privateAccess(state, false);
            acquired = channel.tryLock();
            if (acquired == null) throw new IOException("Another X11 service owns the gamepad state");
            channel.truncate(SIZE);
            packet.putInt(0, MAGIC); packet.putInt(4, VERSION); packet.putInt(12, pid);
            packet.putLong(48, new SecureRandom().nextLong());
            // Invalidate any previous generation before publishing the new header and neutral state.
            sequence = 1;
            packet.putInt(8, sequence); packet.putLong(16, now); packet.putInt(44, 1);
            packet.position(0); writeFully(packet, 0);
            commit(now, true);
        } catch (IOException | RuntimeException error) {
            if (acquired != null) acquired.release();
            channel.close();
            throw error;
        }
        writer = acquired;
    }

    private static void privateAccess(File file, boolean directory) throws IOException {
        if (!file.setReadable(false, false) || !file.setWritable(false, false)
                || !file.setExecutable(false, false) || !file.setReadable(true, true)
                || !file.setWritable(true, true) || directory && !file.setExecutable(true, true))
            throw new IOException("Cannot restrict gamepad file permissions: " + file.getName());
    }

    boolean update(int buttons, int[] axes, long now) throws IOException {
        if (closed || axes == null || axes.length != 8 || (buttons & ~0xf7ff) != 0) return false;
        for (int i = 0; i < 8; i++) {
            int low = i < 4 ? -32767 : i < 6 ? 0 : -1;
            int high = i < 6 ? 32767 : 1;
            if (axes[i] < low || axes[i] > high) return false;
        }
        int hatButtons = (axes[7] < 0 ? 1 : axes[7] > 0 ? 2 : 0)
                | (axes[6] < 0 ? 4 : axes[6] > 0 ? 8 : 0);
        if ((buttons & 15) != hatButtons) return false;
        packet.putInt(24, buttons);
        for (int i = 0; i < 8; i++) packet.putShort(28 + i * 2, (short) axes[i]);
        commit(now, true);
        return true;
    }

    void neutral(long now) throws IOException {
        if (closed) return;
        packet.putInt(24, 0);
        for (int i = 0; i < 8; i++) packet.putShort(28 + i * 2, (short) 0);
        commit(now, true);
    }

    void heartbeat(long now) throws IOException { if (!closed) commit(now, true); }

    private void commit(long now, boolean connected) throws IOException {
        // Syscall writes, rather than Java mmap stores, establish the publication order.
        // Readers pread the packet and re-read its sequence; odd/changing versions are rejected.
        sequence = (sequence + 1) | 1;
        sequenceBytes.clear(); sequenceBytes.putInt(sequence).flip(); writeFully(sequenceBytes, 8);
        packet.putLong(16, now); packet.putInt(44, connected ? 1 : 0);
        packet.putLong(56, System.currentTimeMillis());
        packet.position(12); packet.limit(SIZE); writeFully(packet, 12);
        sequence++;
        sequenceBytes.clear(); sequenceBytes.putInt(sequence).flip(); writeFully(sequenceBytes, 8);
    }

    private void writeFully(ByteBuffer data, long offset) throws IOException {
        while (data.hasRemaining()) {
            int count = channel.write(data, offset);
            if (count <= 0) throw new IOException("Gamepad state write made no progress");
            offset += count;
        }
    }

    @Override public void close() throws IOException {
        if (closed) return;
        try {
            packet.putInt(24, 0);
            for (int i = 0; i < 8; i++) packet.putShort(28 + i * 2, (short) 0);
            commit(packet.getLong(16), false);
        } finally {
            closed = true;
            try { writer.release(); } finally { channel.close(); }
        }
    }
}
