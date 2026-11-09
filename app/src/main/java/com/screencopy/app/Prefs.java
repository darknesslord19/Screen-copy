package com.screencopy.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Kullanıcı ayarları (çözünürlük, kalite, FPS, port). */
public final class Prefs {

    private static final String NAME = "sc_prefs";
    public static final String MAX_DIM = "max_dim";
    public static final String QUALITY = "quality";
    public static final String FPS = "fps";
    public static final String PORT = "port";

    public static final int DEF_MAX_DIM = 900;
    public static final int DEF_QUALITY = 40;
    public static final int DEF_FPS = 16;
    public static final int DEF_PORT = Protocol.PORT;

    private Prefs() {}

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static int maxDim(Context c)  { return clamp(get(c).getInt(MAX_DIM, DEF_MAX_DIM), 200, 2000); }
    public static int quality(Context c) { return clamp(get(c).getInt(QUALITY, DEF_QUALITY), 10, 90); }
    public static int fps(Context c)     { return clamp(get(c).getInt(FPS, DEF_FPS), 1, 30); }
    public static int port(Context c)    { return clamp(get(c).getInt(PORT, DEF_PORT), 1024, 65535); }

    public static long frameIntervalMs(Context c) { return 1000L / fps(c); }

    public static void save(Context c, int maxDim, int quality, int fps, int port) {
        get(c).edit()
                .putInt(MAX_DIM, maxDim)
                .putInt(QUALITY, quality)
                .putInt(FPS, fps)
                .putInt(PORT, port)
                .apply();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
