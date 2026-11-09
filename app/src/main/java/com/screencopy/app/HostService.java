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
import android.util.DisplayMetrics;
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
    private static final int TARGET_MAX_DIM = 900; // yayin cozunurlugu ust siniri
    private static final int JPEG_QUALITY = 40;
    private static final long MIN_FRAME_INTERVAL_MS = 60; // ~16 fps

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

        computeDimensions();
        acquireWakeLock();
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
        if (maxDim > TARGET_MAX_DIM) {
            scale = (float) TARGET_MAX_DIM / maxDim;
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
        cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos);
        cropped.recycle();
        return bos.toByteArray();
    }

    private void serverLoop() {
        while (running.get()) {
            try {
                serverSocket = new ServerSocket(Protocol.PORT);
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
            if (type != Protocol.TYPE_TOUCH) continue;
            byte action = Protocol.touchAction(payload);
            float nx = Protocol.touchX(payload);
            float ny = Protocol.touchY(payload);
            float x = nx * fullW;
            float y = ny * fullH;
            handleTouch(action, x, y);
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
            long wait = MIN_FRAME_INTERVAL_MS - (now - lastSentTime);
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
                .setContentText("Ekran paylasiliyor. Port " + Protocol.PORT)
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .build();
    }
}
