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

    public static final int DEF_MAX_DIM = 1600;
    public static final int DEF_QUALITY = 75;
    public static final int DEF_FPS = 30;
    public static final int DEF_PORT = Protocol.PORT;

    public static final int MIN_MAX_DIM = 320, MAX_MAX_DIM = 2560;
    public static final int MIN_QUALITY = 20, MAX_QUALITY = 95;
    public static final int MIN_FPS = 5, MAX_FPS = 60;

    private Prefs() {}

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static int maxDim(Context c)  { return clamp(get(c).getInt(MAX_DIM, DEF_MAX_DIM), MIN_MAX_DIM, MAX_MAX_DIM); }
    public static int quality(Context c) { return clamp(get(c).getInt(QUALITY, DEF_QUALITY), MIN_QUALITY, MAX_QUALITY); }
    public static int fps(Context c)     { return clamp(get(c).getInt(FPS, DEF_FPS), MIN_FPS, MAX_FPS); }
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
