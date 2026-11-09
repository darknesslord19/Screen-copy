package com.screencopy.app;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class ControllerActivity extends AppCompatActivity {

    private FrameView frameView;
    private LinearLayout navBar;
    private TextView showHandle;
    private Socket socket;
    private DataOutputStream out;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final LinkedBlockingQueue<Msg> sendQueue = new LinkedBlockingQueue<>();
    private String host;
    private int port = Protocol.PORT;

    private static class Msg {
        final byte type; final byte[] payload;
        Msg(byte t, byte[] p) { type = t; payload = p; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String arg = getIntent().getStringExtra("host");
        if (arg != null && arg.contains(":")) {
            String[] p = arg.split(":");
            host = p[0].trim();
            try { port = Integer.parseInt(p[1].trim()); } catch (Exception ignored) {}
        } else host = arg;

        FrameLayout rootFl = new FrameLayout(this);
        rootFl.setBackgroundColor(Color.BLACK);

        // Ekran görüntüsü tüm alanı kaplar (gerçek tam ekran)
        frameView = new FrameView(this);
        frameView.setTouchSender(this::sendTouch);
        rootFl.addView(frameView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Alt kontrol çubuğu (yarı saydam, üstte yüzer)
        navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setBackgroundColor(0xE6141A22);
        navBar.setPadding(dp(6), dp(6), dp(6), dp(6));
        navBar.addView(navButton("‹", "Geri", v -> sendKey(Protocol.KEY_BACK)));
        navBar.addView(navButton("⌂", "Ana", v -> sendKey(Protocol.KEY_HOME)));
        navBar.addView(navButton("▢", "Son", v -> sendKey(Protocol.KEY_RECENTS)));
        navBar.addView(navButton("⌨", "Yaz", v -> showKeyboardDialog()));
        navBar.addView(navButton("🔔", "Bildirim", v -> sendKey(Protocol.KEY_NOTIFICATIONS)));
        navBar.addView(navButton("⌄", "Gizle", v -> setBarVisible(false)));
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        rootFl.addView(navBar, navLp);

        // Çubuk gizliyken alttan küçük tutamak
        showHandle = new TextView(this);
        showHandle.setText("⌃");
        showHandle.setTextColor(Color.WHITE);
        showHandle.setTextSize(18);
        showHandle.setGravity(Gravity.CENTER);
        showHandle.setBackgroundColor(0x99141A22);
        showHandle.setPadding(dp(18), dp(2), dp(18), dp(2));
        showHandle.setVisibility(View.GONE);
        showHandle.setOnClickListener(v -> setBarVisible(true));
        FrameLayout.LayoutParams hLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        rootFl.addView(showHandle, hLp);

        setContentView(rootFl);
        new Thread(this::networkLoop, "sc-controller").start();
    }

    private void setBarVisible(boolean visible) {
        navBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        showHandle.setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    private LinearLayout navButton(String glyph, String label, View.OnClickListener l) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackground(getDrawable(R.drawable.nav_btn));
        col.setPadding(dp(4), dp(7), dp(4), dp(5));
        col.setClickable(true);
        col.setOnClickListener(l);

        TextView g = new TextView(this);
        g.setText(glyph);
        g.setTextColor(0xFFE6EAF0);
        g.setTextSize(19);
        g.setGravity(Gravity.CENTER);
        col.addView(g);

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(0xFF9AA4B2);
        t.setTextSize(10);
        t.setGravity(Gravity.CENTER);
        col.addView(t);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        col.setLayoutParams(lp);
        return col;
    }

    private void showKeyboardDialog() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setHint("Karşı telefona yazılacak metin");
        new AlertDialog.Builder(this)
                .setTitle("Metin gönder")
                .setView(et)
                .setPositiveButton("Gönder", (d, w) -> {
                    String t = et.getText().toString();
                    if (!t.isEmpty())
                        sendQueue.offer(new Msg(Protocol.TYPE_TEXT, t.getBytes(StandardCharsets.UTF_8)));
                })
                .setNegativeButton("Vazgeç", null)
                .show();
    }

    // Gerçek tam ekran (sistem çubuklarını gizle)
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
    }

    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void networkLoop() {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 8000);
            socket.setTcpNoDelay(true);
            out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            toast("Bağlandı: " + host + ":" + port);
            startSender();

            BitmapFactory.Options opts = new BitmapFactory.Options();
            while (running.get()) {
                byte type = Protocol.readType(in);
                byte[] payload = Protocol.readPayload(in);
                if (type == Protocol.TYPE_FRAME) {
                    Bitmap bmp = BitmapFactory.decodeByteArray(payload, 0, payload.length, opts);
                    if (bmp != null) frameView.updateFrame(bmp);
                }
            }
        } catch (Exception e) {
            toast("Bağlantı hatası: " + e.getMessage());
        } finally {
            close();
        }
    }

    private void sendTouch(byte action, float nx, float ny) {
        sendQueue.offer(new Msg(Protocol.TYPE_TOUCH, Protocol.encodeTouch(action, nx, ny)));
    }

    private void sendKey(byte key) {
        sendQueue.offer(new Msg(Protocol.TYPE_KEY, new byte[]{key}));
    }

    private void startSender() {
        new Thread(() -> {
            while (running.get()) {
                try {
                    Msg m = sendQueue.take();
                    DataOutputStream o = out;
                    if (o != null) Protocol.writeMessage(o, m.type, m.payload);
                } catch (InterruptedException ie) {
                    return;
                } catch (Exception ignored) { }
            }
        }, "sc-sender").start();
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private void close() {
        running.set(false);
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onDestroy() {
        close();
        super.onDestroy();
    }
}
