package com.screencopy.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

/**
 * Uzaktan gelen dokunma olaylarini gercek ekrana enjekte eder.
 * Host modunda kontrol icin kullanicinin bu servisi Erisilebilirlik
 * ayarlarindan etkinlestirmesi gerekir.
 */
public class ControlAccessibilityService extends AccessibilityService {

    private static ControlAccessibilityService instance;

    public static ControlAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    /** Bir jest (tap / swipe / basili tutma) yolunu belirtilen surede uygular. */
    public void dispatchPath(Path path, long durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        if (durationMs < 1) durationMs = 1;
        if (durationMs > 60000) durationMs = 60000;
        try {
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, durationMs);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(stroke)
                    .build();
            dispatchGesture(gesture, null, null);
        } catch (Exception ignored) { }
    }
}
