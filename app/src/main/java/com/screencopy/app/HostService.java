package com.screencopy.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ekrani MediaProjection ile yakalar, JPEG kareleri TCP uzerinden yayinlar
 * ve kontrol telefonundan gelen dokunma olaylarini enjekte eder.
 */
public class HostService extends Service {

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    private static final String CHANNEL_ID = "screencopy_host";
    private static final int NOTIF_ID = 1;

    // Ayarlardan yüklenir
    private int targetMaxDim = Prefs.DEF_MAX_DIM;
    private int jpegQuality = Prefs.DEF_QUALITY;
    private long minFrameIntervalMs = 60;
    private int port = Protocol.PORT;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;

    private int fullW, fullH, densityDpi;
    private int capW, capH;

    private ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile DataOutputStream clientOut;
    private final Object frameLock = new Object();
    private byte[] latestFrame;
    private long lastSentTime;
    private PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private View keepAwakeView;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopEverything(); }
        }, null);

        // Ayarları yükle
        targetMaxDim = Prefs.maxDim(this);
        jpegQuality = Prefs.quality(this);
        minFrameIntervalMs = Prefs.frameIntervalMs(this);
        port = Prefs.port(this);

        computeDimensions();
        acquireWakeLock();
        addKeepAwakeOverlay();
        startCapture();
        running.set(true);
        new Thread(this::serverLoop, "sc-server").start();
        new Thread(this::senderLoop, "sc-sender").start();
        return START_STICKY;
    }

    private void computeDimensions() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        fullW = dm.widthPixels;
        fullH = dm.heightPixels;
        densityDpi = dm.densityDpi;

        float scale = 1f;
        int maxDim = Math.max(fullW, fullH);
        if (maxDim > targetMaxDim) {
            scale = (float) targetMaxDim / maxDim;
        }
        capW = Math.max(2, (int) (fullW * scale) & ~1); // cift sayi
        capH = Math.max(2, (int) (fullH * scale) & ~1);
    }

    private void startCapture() {
        captureThread = new HandlerThread("sc-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        imageReader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) return;
                byte[] jpeg = imageToJpeg(image);
                if (jpeg != null) {
                    synchronized (frameLock) {
                        latestFrame = jpeg;
                        frameLock.notifyAll();
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (image != null) image.close();
            }
        }, captureHandler);

        virtualDisplay = projection.createVirtualDisplay(
                "ScreenCopy",
                capW, capH, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, captureHandler);
    }

    private byte[] imageToJpeg(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * capW;
        int bmpW = capW + rowPadding / pixelStride;

        Bitmap bitmap = Bitmap.createBitmap(bmpW, capH, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        Bitmap cropped = bitmap;
        if (bmpW != capW) {
            cropped = Bitmap.createBitmap(bitmap, 0, 0, capW, capH);
            bitmap.recycle();
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        cropped.compress(Bitmap.CompressFormat.JPEG, jpegQuality, bos);
        cropped.recycle();
        return bos.toByteArray();
    }

    private void serverLoop() {
        while (running.get()) {
            try {
                serverSocket = new ServerSocket(port);
                serverSocket.setReuseAddress(true);
                while (running.get()) {
                    Socket socket = serverSocket.accept();
                    handleClient(socket);
                }
            } catch (Exception e) {
                if (running.get()) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
            } finally {
                closeServer();
            }
        }
    }

    private void handleClient(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            Protocol.writeMessage(out, Protocol.TYPE_META, Protocol.encodeMeta(fullW, fullH));
            clientOut = out;
            // Bu thread kontrol olaylarini okur; kareler senderLoop'tan gider.
            readControlLoop(in);
        } catch (Exception ignored) {
        } finally {
            clientOut = null;
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    // --- Dokunma yeniden olusturma ---
    private final List<float[]> gesturePoints = new ArrayList<>();
    private long gestureStartTime;

    private void readControlLoop(DataInputStream in) throws Exception {
        while (running.get()) {
            byte type = Protocol.readType(in);
            byte[] payload = Protocol.readPayload(in);
            if (type == Protocol.TYPE_TOUCH) {
                byte action = Protocol.touchAction(payload);
                float x = Protocol.touchX(payload) * fullW;
                float y = Protocol.touchY(payload) * fullH;
                handleTouch(action, x, y);
            } else if (type == Protocol.TYPE_KEY) {
                ControlAccessibilityService svc = ControlAccessibilityService.getInstance();
                if (svc != null && payload.length > 0) svc.performKey(payload[0]);
            } else if (type == Protocol.TYPE_TEXT) {
                ControlAccessibilityService svc = ControlAccessibilityService.getInstance();
                if (svc != null) svc.inputText(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }

    private void handleTouch(byte action, float x, float y) {
        switch (action) {
            case Protocol.ACTION_DOWN:
                gesturePoints.clear();
                gestureStartTime = System.currentTimeMillis();
                gesturePoints.add(new float[]{x, y});
                break;
            case Protocol.ACTION_MOVE:
                gesturePoints.add(new float[]{x, y});
                break;
            case Protocol.ACTION_UP:
                gesturePoints.add(new float[]{x, y});
                dispatchGesture();
                break;
        }
    }

    private void dispatchGesture() {
        ControlAccessibilityService svc = ControlAccessibilityService.getInstance();
        if (svc == null || gesturePoints.isEmpty()) return;

        Path path = new Path();
        float[] first = gesturePoints.get(0);
        path.moveTo(clampX(first[0]), clampY(first[1]));
        boolean moved = false;
        for (int i = 1; i < gesturePoints.size(); i++) {
            float[] p = gesturePoints.get(i);
            path.lineTo(clampX(p[0]), clampY(p[1]));
            if (Math.abs(p[0] - first[0]) > 5 || Math.abs(p[1] - first[1]) > 5) moved = true;
        }
        long duration = System.currentTimeMillis() - gestureStartTime;
        if (!moved) {
            // Tap veya basili tutma: sure kadar beklet
            duration = Math.max(1, Math.min(duration, 1200));
        } else {
            duration = Math.max(20, Math.min(duration, 3000));
        }
        svc.dispatchPath(path, duration);
    }

    private float clampX(float x) { return Math.max(0, Math.min(x, fullW - 1)); }
    private float clampY(float y) { return Math.max(0, Math.min(y, fullH - 1)); }

    private void senderLoop() {
        while (running.get()) {
            byte[] frame;
            synchronized (frameLock) {
                while (running.get() && latestFrame == null) {
                    try { frameLock.wait(500); } catch (InterruptedException ignored) {}
                }
                frame = latestFrame;
                latestFrame = null;
            }
            if (frame == null) continue;
            DataOutputStream out = clientOut;
            if (out == null) continue;
            long now = System.currentTimeMillis();
            long wait = minFrameIntervalMs - (now - lastSentTime);
            if (wait > 0) {
                try { Thread.sleep(wait); } catch (InterruptedException ignored) {}
            }
            try {
                Protocol.writeMessage(out, Protocol.TYPE_FRAME, frame);
                lastSentTime = System.currentTimeMillis();
            } catch (Exception e) {
                clientOut = null;
            }
        }
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenCopy:host");
        wakeLock.acquire(3 * 60 * 60 * 1000L);

        // Ekran kapanınca/kilitlenince Wi-Fi uykuya geçip bağlantıyı düşürmesin
        try {
            android.net.wifi.WifiManager wm =
                    (android.net.wifi.WifiManager) getApplicationContext()
                            .getSystemService(Context.WIFI_SERVICE);
            int mode = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ? android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    : android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "ScreenCopy:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception ignored) {}
    }

    /**
     * Ekran zaman asimiyla sönünce yansima kararir. Köksüz çözüm: üstten çizim
     * izni varsa, ekrani açik tutan ama parlakligi en düsük (görünüste kapali)
     * 1x1 bir overlay ekleyerek ekranin render etmeye devam etmesini saglariz.
     */
    private void addKeepAwakeOverlay() {
        try {
            if (!Settings.canDrawOverlays(this)) return;
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    1, 1, type,
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.screenBrightness = 0.01f; // ekran açik kalir ama neredeyse kapali görünür
            keepAwakeView = new View(this);
            wm.addView(keepAwakeView, lp);
        } catch (Exception ignored) {}
    }

    private void removeKeepAwakeOverlay() {
        try {
            if (keepAwakeView != null) {
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                wm.removeView(keepAwakeView);
                keepAwakeView = null;
            }
        } catch (Exception ignored) {}
    }

    private void stopEverything() {
        running.set(false);
        synchronized (frameLock) { frameLock.notifyAll(); }
        closeServer();
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        try { if (captureThread != null) captureThread.quitSafely(); } catch (Exception ignored) {}
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
        removeKeepAwakeOverlay();
        stopForeground(true);
        stopSelf();
    }

    private void closeServer() {
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        stopEverything();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "ScreenCopy Host", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("ScreenCopy yayinda")
                .setContentText("Ekran paylasiliyor (arka planda calisir). Port " + Prefs.port(this))
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .build();
    }
}
