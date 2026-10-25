package com.termux.x11;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Read-only X11 core protocol: observe root geometry without changing the desktop mode. */
final class X11Geometry {
    private X11Geometry() { }

    static byte[] readCookie(DataInputStream authority) throws IOException {
        // X11Service writes one FamilyWild record for its private :9 server.
        if (authority.readUnsignedShort() != 65535) throw new IOException("Unexpected Xauthority family");
        byte[] address = readField(authority);
        byte[] display = readField(authority);
        byte[] name = readField(authority);
        byte[] cookie = readField(authority);
        if (address.length != 0 || !"9".equals(new String(display, StandardCharsets.US_ASCII))
                || !"MIT-MAGIC-COOKIE-1".equals(new String(name, StandardCharsets.US_ASCII)) || cookie.length != 16)
            throw new IOException("Invalid :9 Xauthority record");
        return cookie;
    }

    private static byte[] readField(DataInputStream input) throws IOException {
        byte[] field = new byte[input.readUnsignedShort()];
        input.readFully(field);
        return field;
    }

    static int authenticate(DataInputStream input, DataOutputStream output, byte[] cookie) throws IOException {
        byte[] name = "MIT-MAGIC-COOKIE-1".getBytes(StandardCharsets.US_ASCII);
        output.writeByte('B'); output.writeByte(0); output.writeShort(11); output.writeShort(0);
        output.writeShort(name.length); output.writeShort(cookie.length); output.writeShort(0);
        output.write(name);
        for (int i = name.length; (i & 3) != 0; i++) output.writeByte(0);
        output.write(cookie);
        for (int i = cookie.length; (i & 3) != 0; i++) output.writeByte(0);
        output.flush();
        int status = input.readUnsignedByte();
        input.readUnsignedByte();
        int major = input.readUnsignedShort();
        input.readUnsignedShort();
        int length = input.readUnsignedShort() * 4;
        if (status != 1 || major != 11) throw new IOException("X11 cookie authentication rejected");
        byte[] setup = new byte[length];
        input.readFully(setup);
        if (length < 32) throw new IOException("Truncated X11 setup");
        ByteBuffer data = ByteBuffer.wrap(setup).order(ByteOrder.BIG_ENDIAN);
        int vendorLength = data.getShort(16) & 0xffff;
        int screenCount = data.get(20) & 0xff;
        int formatCount = data.get(21) & 0xff;
        int screenOffset = 32 + ((vendorLength + 3) & ~3) + formatCount * 8;
        if (screenCount == 0 || screenOffset + 40 > length) throw new IOException("Missing X11 root screen");
        return data.getInt(screenOffset);
    }

    static void watch(DataOutputStream output, int root) throws IOException {
        // Event selection is per-client. This does not resize or modify another client's selection.
        output.writeByte(2); output.writeByte(0); output.writeShort(4); // ChangeWindowAttributes
        output.writeInt(root); output.writeInt(1 << 11); output.writeInt(1 << 17); // StructureNotifyMask
        // Query after subscribing: a resize between setup and subscription must not be missed.
        output.writeByte(14); output.writeByte(0); output.writeShort(2); // GetGeometry
        output.writeInt(root);
        output.flush();
    }

    static int[] readSize(DataInputStream input, int root) throws IOException {
        byte[] packet = new byte[32];
        input.readFully(packet);
        ByteBuffer data = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);
        int type = packet[0] & 0xff;
        if (type == 0) throw new IOException("X11 geometry request failed: " + (packet[1] & 0xff));
        int width, height;
        if (type == 1) {
            // GetGeometry is our only reply-producing request, with a fixed 32-byte reply.
            if (data.getInt(4) != 0 || data.getInt(8) != root) throw new IOException("Invalid X11 geometry reply");
            width = data.getShort(16) & 0xffff;
            height = data.getShort(18) & 0xffff;
        } else if (type == 22 && data.getInt(8) == root) { // real ConfigureNotify, never SendEvent
            width = data.getShort(20) & 0xffff;
            height = data.getShort(22) & 0xffff;
        } else {
            return null;
        }
        if (width == 0 || height == 0) throw new IOException("Empty X11 root geometry");
        return new int[]{width, height};
    }
}
