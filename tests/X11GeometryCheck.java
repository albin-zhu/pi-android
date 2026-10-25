package com.termux.x11;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Host-side protocol checks; Android input/lifecycle safety still needs device instrumentation. */
public final class X11GeometryCheck {
    private static final int ROOT = 0x12345678;

    public static void main(String[] args) throws Exception {
        byte[] cookie = new byte[16];
        Arrays.fill(cookie, (byte) 0x5a);
        ByteArrayOutputStream authorityBytes = new ByteArrayOutputStream();
        DataOutputStream authority = new DataOutputStream(authorityBytes);
        authority.writeShort(65535);
        field(authority, new byte[0]);
        field(authority, "9".getBytes(StandardCharsets.US_ASCII));
        field(authority, "MIT-MAGIC-COOKIE-1".getBytes(StandardCharsets.US_ASCII));
        field(authority, cookie);
        require(Arrays.equals(cookie, X11Geometry.readCookie(input(authorityBytes.toByteArray()))), "private display cookie");
        byte[] wrongDisplay = authorityBytes.toByteArray();
        wrongDisplay[6] = '8';
        rejects(() -> X11Geometry.readCookie(input(wrongDisplay)), "another display's authority");

        // Vendor padding and pixmap formats precede the screen: geometry cannot be guessed from UI size.
        byte[] setup = new byte[8 + 32 + 8 + 16 + 40];
        ByteBuffer data = ByteBuffer.wrap(setup).order(ByteOrder.BIG_ENDIAN);
        data.put(0, (byte) 1); data.putShort(2, (short) 11); data.putShort(6, (short) ((setup.length - 8) / 4));
        data.putShort(8 + 16, (short) 5); data.put(8 + 20, (byte) 1); data.put(8 + 21, (byte) 2);
        data.putInt(8 + 32 + 8 + 16, ROOT);
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        require(X11Geometry.authenticate(input(setup), new DataOutputStream(sent), cookie) == ROOT, "actual root from setup");
        DataInputStream handshake = input(sent.toByteArray());
        require(handshake.readUnsignedByte() == 'B', "big-endian protocol");
        handshake.readUnsignedByte();
        require(handshake.readUnsignedShort() == 11 && handshake.readUnsignedShort() == 0, "X11 protocol version");
        int nameLength = handshake.readUnsignedShort(), cookieLength = handshake.readUnsignedShort();
        handshake.readUnsignedShort();
        byte[] name = new byte[nameLength], actualCookie = new byte[cookieLength];
        handshake.readFully(name);
        handshake.skipBytes((-nameLength) & 3);
        handshake.readFully(actualCookie);
        handshake.skipBytes((-cookieLength) & 3);
        require("MIT-MAGIC-COOKIE-1".equals(new String(name, StandardCharsets.US_ASCII))
                && Arrays.equals(cookie, actualCookie) && handshake.available() == 0, "authenticated setup only");

        sent.reset();
        X11Geometry.watch(new DataOutputStream(sent), ROOT);
        DataInputStream requests = input(sent.toByteArray());
        require(requests.readUnsignedByte() == 2 && requests.readUnsignedByte() == 0
                && requests.readUnsignedShort() == 4 && requests.readInt() == ROOT
                && requests.readInt() == (1 << 11) && requests.readInt() == (1 << 17), "only per-client root event selection");
        require(requests.readUnsignedByte() == 14 && requests.readUnsignedByte() == 0
                && requests.readUnsignedShort() == 2 && requests.readInt() == ROOT
                && requests.available() == 0, "query after subscription, no resize or input requests");

        byte[] reply = new byte[32];
        data = ByteBuffer.wrap(reply).order(ByteOrder.BIG_ENDIAN);
        data.put(0, (byte) 1); data.putInt(8, ROOT); data.putShort(16, (short) 1920); data.putShort(18, (short) 1080);
        require(Arrays.equals(new int[]{1920, 1080}, X11Geometry.readSize(input(reply), ROOT)), "initial desktop geometry");
        byte[] event = new byte[32];
        data = ByteBuffer.wrap(event).order(ByteOrder.BIG_ENDIAN);
        data.put(0, (byte) 22); data.putInt(4, ROOT); data.putInt(8, ROOT);
        data.putShort(20, (short) 800); data.putShort(22, (short) 1200);
        require(Arrays.equals(new int[]{800, 1200}, X11Geometry.readSize(input(event), ROOT)), "server resize updates preview dimensions");
        event[0] = (byte) (22 | 0x80);
        require(X11Geometry.readSize(input(event), ROOT) == null, "ignore synthetic desktop geometry");
        event[0] = 22;
        data.putInt(8, ROOT + 1);
        require(X11Geometry.readSize(input(event), ROOT) == null, "ignore other window geometry");
        data.putInt(8, ROOT); data.putShort(20, (short) 0);
        rejects(() -> X11Geometry.readSize(input(event), ROOT), "empty desktop");
        rejects(() -> X11Geometry.readSize(input(new byte[8]), ROOT), "truncated response");
        setup[0] = 0;
        rejects(() -> X11Geometry.authenticate(input(setup), new DataOutputStream(new ByteArrayOutputStream()), cookie), "rejected authentication");
        System.out.println("X11 authentication, non-mutating geometry observation, resize events and malformed response checks passed");
    }

    private static DataInputStream input(byte[] data) { return new DataInputStream(new ByteArrayInputStream(data)); }
    private static void field(DataOutputStream output, byte[] value) throws IOException { output.writeShort(value.length); output.write(value); }
    private static void require(boolean value, String label) { if (!value) throw new AssertionError(label); }
    private interface IOAction { void run() throws IOException; }
    private static void rejects(IOAction action, String label) throws IOException {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Accepted " + label);
    }
}
