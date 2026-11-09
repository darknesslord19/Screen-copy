package com.screencopy.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

/** Gelen kareleri en-boy orani korunarak tam ekran cizer ve dokunmalari normalize eder. */
public class FrameView extends View {

    public interface TouchSender {
        void send(byte action, float nx, float ny);
    }

    private Bitmap frame;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private final Rect src = new Rect();
    private TouchSender sender;

    private float drawX, drawY, drawW, drawH;

    public FrameView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
    }

    public void setTouchSender(TouchSender s) { this.sender = s; }

    public void updateFrame(Bitmap bmp) {
        Bitmap old = frame;
        frame = bmp;
        postInvalidate();
        if (old != null && old != bmp) old.recycle();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (frame == null) return;
        int vw = getWidth(), vh = getHeight();
        int bw = frame.getWidth(), bh = frame.getHeight();
        float scale = Math.min((float) vw / bw, (float) vh / bh);
        drawW = bw * scale;
        drawH = bh * scale;
        drawX = (vw - drawW) / 2f;
        drawY = (vh - drawH) / 2f;
        src.set(0, 0, bw, bh);
        dst.set(drawX, drawY, drawX + drawW, drawY + drawH);
        canvas.drawBitmap(frame, src, dst, paint);
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        if (sender == null || drawW <= 0) return true;
        float nx = (e.getX() - drawX) / drawW;
        float ny = (e.getY() - drawY) / drawH;
        nx = Math.max(0f, Math.min(1f, nx));
        ny = Math.max(0f, Math.min(1f, ny));
        byte action;
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN: action = Protocol.ACTION_DOWN; break;
            case android.view.MotionEvent.ACTION_MOVE: action = Protocol.ACTION_MOVE; break;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL: action = Protocol.ACTION_UP; break;
            default: return true;
        }
        sender.send(action, nx, ny);
        return true;
    }
}
