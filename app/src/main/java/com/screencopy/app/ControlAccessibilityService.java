package com.screencopy.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

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

    /** Donanım tuşu: geri / ana ekran / son uygulamalar / bildirim paneli. */
    public void performKey(byte key) {
        int action;
        switch (key) {
            case Protocol.KEY_BACK:          action = GLOBAL_ACTION_BACK; break;
            case Protocol.KEY_HOME:          action = GLOBAL_ACTION_HOME; break;
            case Protocol.KEY_RECENTS:       action = GLOBAL_ACTION_RECENTS; break;
            case Protocol.KEY_NOTIFICATIONS: action = GLOBAL_ACTION_NOTIFICATIONS; break;
            default: return;
        }
        try { performGlobalAction(action); } catch (Exception ignored) {}
    }

    /** Odaktaki düzenlenebilir alana metin yazar (mevcut metne ekler). */
    public void inputText(String text) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus == null) return;
            CharSequence existing = focus.getText();
            String merged = (existing != null ? existing.toString() : "") + text;
            Bundle b = new Bundle();
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, merged);
            focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        } catch (Exception ignored) {}
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
