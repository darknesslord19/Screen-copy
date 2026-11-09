package com.screencopy.app;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Tek bir TCP soketi üzerinde uzunluk önekli basit mesaj protokolü.
 * Mesaj yapısı: [1 byte tip][4 byte uzunluk (big-endian)][payload].
 */
public final class Protocol {

    public static final int PORT = 7843;

    public static final byte TYPE_FRAME = 0;  // Host -> Controller : JPEG kare
    public static final byte TYPE_TOUCH = 1;  // Controller -> Host : dokunma olayı
    public static final byte TYPE_META  = 2;  // Host -> Controller : ekran boyutu
    public static final byte TYPE_KEY   = 3;  // Controller -> Host : donanım tuşu
    public static final byte TYPE_TEXT  = 4;  // Controller -> Host : metin girişi

    public static final byte ACTION_DOWN = 0;
    public static final byte ACTION_MOVE = 1;
    public static final byte ACTION_UP   = 2;

    // Donanım tuşları (TYPE_KEY payload[0])
    public static final byte KEY_BACK          = 0;
    public static final byte KEY_HOME          = 1;
    public static final byte KEY_RECENTS       = 2;
    public static final byte KEY_NOTIFICATIONS = 3;

    private Protocol() {}

    public static synchronized void writeMessage(DataOutputStream out, byte type, byte[] payload) throws IOException {
        out.writeByte(type);
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    public static byte readType(DataInputStream in) throws IOException {
        return in.readByte();
    }

    public static byte[] readPayload(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 50_000_000) {
            throw new IOException("Gecersiz uzunluk: " + len);
        }
        byte[] buf = new byte[len];
        in.readFully(buf);
        return buf;
    }

    /** Dokunma olayini payload'a kodlar: action(1) + x(float) + y(float), x/y 0..1 normalize. */
    public static byte[] encodeTouch(byte action, float nx, float ny) {
        byte[] b = new byte[9];
        b[0] = action;
        int xi = Float.floatToIntBits(nx);
        int yi = Float.floatToIntBits(ny);
        b[1] = (byte) (xi >> 24); b[2] = (byte) (xi >> 16); b[3] = (byte) (xi >> 8); b[4] = (byte) xi;
        b[5] = (byte) (yi >> 24); b[6] = (byte) (yi >> 16); b[7] = (byte) (yi >> 8); b[8] = (byte) yi;
        return b;
    }

    public static float touchX(byte[] b) {
        int xi = ((b[1] & 0xFF) << 24) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 8) | (b[4] & 0xFF);
        return Float.intBitsToFloat(xi);
    }

    public static float touchY(byte[] b) {
        int yi = ((b[5] & 0xFF) << 24) | ((b[6] & 0xFF) << 16) | ((b[7] & 0xFF) << 8) | (b[8] & 0xFF);
        return Float.intBitsToFloat(yi);
    }

    public static byte touchAction(byte[] b) {
        return b[0];
    }

    public static byte[] encodeMeta(int w, int h) {
        byte[] b = new byte[8];
        b[0] = (byte) (w >> 24); b[1] = (byte) (w >> 16); b[2] = (byte) (w >> 8); b[3] = (byte) w;
        b[4] = (byte) (h >> 24); b[5] = (byte) (h >> 16); b[6] = (byte) (h >> 8); b[7] = (byte) h;
        return b;
    }

    public static int metaW(byte[] b) {
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    public static int metaH(byte[] b) {
        return ((b[4] & 0xFF) << 24) | ((b[5] & 0xFF) << 16) | ((b[6] & 0xFF) << 8) | (b[7] & 0xFF);
    }
}
