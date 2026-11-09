package com.screencopy.app;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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

        // "ip" veya "ip:port"
        String arg = getIntent().getStringExtra("host");
        if (arg != null && arg.contains(":")) {
            String[] parts = arg.split(":");
            host = parts[0].trim();
            try { port = Integer.parseInt(parts[1].trim()); } catch (Exception ignored) {}
        } else {
            host = arg;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        frameView = new FrameView(this);
        frameView.setTouchSender(this::sendTouch);
        LinearLayout.LayoutParams fvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        frameView.setLayoutParams(fvLp);
        root.addView(frameView);

        // Alt navigasyon çubuğu (gerçek telefon tuşları gibi)
        LinearLayout navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setBackgroundColor(Color.parseColor("#1A1F24"));
        navBar.addView(navButton("◀ Geri", v -> sendKey(Protocol.KEY_BACK)));
        navBar.addView(navButton("● Ana", v -> sendKey(Protocol.KEY_HOME)));
        navBar.addView(navButton("■ Son", v -> sendKey(Protocol.KEY_RECENTS)));
        navBar.addView(navButton("⌨ Yaz", v -> showKeyboardDialog()));
        navBar.addView(navButton("🔔", v -> sendKey(Protocol.KEY_NOTIFICATIONS)));
        root.addView(navBar);

        setContentView(root);
        new Thread(this::networkLoop, "sc-controller").start();
    }

    private Button navButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor("#263238"));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(2, 4, 2, 4);
        b.setLayoutParams(lp);
        return b;
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
                    if (!t.isEmpty()) {
                        sendQueue.offer(new Msg(Protocol.TYPE_TEXT, t.getBytes(StandardCharsets.UTF_8)));
                    }
                })
                .setNegativeButton("Vazgeç", null)
                .show();
    }

    private void networkLoop() {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 8000);
            socket.setTcpNoDelay(true);
            out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            toast("Baglandi: " + host + ":" + port);
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
            toast("Baglanti hatasi: " + e.getMessage());
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

    @Override
    protected void onDestroy() {
        close();
        super.onDestroy();
    }
}
