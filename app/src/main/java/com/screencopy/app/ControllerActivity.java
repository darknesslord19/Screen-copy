package com.screencopy.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class ControllerActivity extends AppCompatActivity {

    private FrameView frameView;
    private Socket socket;
    private DataOutputStream out;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final LinkedBlockingQueue<byte[]> touchQueue = new LinkedBlockingQueue<>();
    private String host;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        host = getIntent().getStringExtra("host");
        frameView = new FrameView(this);
        frameView.setTouchSender(this::sendTouch);
        setContentView(frameView);

        new Thread(this::networkLoop, "sc-controller").start();
    }

    private void networkLoop() {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, Protocol.PORT), 8000);
            socket.setTcpNoDelay(true);
            out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            toast("Baglandi: " + host);
            startTouchSender();

            BitmapFactory.Options opts = new BitmapFactory.Options();
            while (running.get()) {
                byte type = Protocol.readType(in);
                byte[] payload = Protocol.readPayload(in);
                if (type == Protocol.TYPE_FRAME) {
                    Bitmap bmp = BitmapFactory.decodeByteArray(payload, 0, payload.length, opts);
                    if (bmp != null) frameView.updateFrame(bmp);
                } else if (type == Protocol.TYPE_META) {
                    // Host ekran boyutu (ileride kullanilabilir)
                }
            }
        } catch (Exception e) {
            toast("Baglanti hatasi: " + e.getMessage());
        } finally {
            close();
        }
    }

    private void sendTouch(byte action, float nx, float ny) {
        touchQueue.offer(Protocol.encodeTouch(action, nx, ny));
    }

    private void startTouchSender() {
        new Thread(() -> {
            while (running.get()) {
                try {
                    byte[] payload = touchQueue.take();
                    DataOutputStream o = out;
                    if (o != null) {
                        Protocol.writeMessage(o, Protocol.TYPE_TOUCH, payload);
                    }
                } catch (InterruptedException ie) {
                    return;
                } catch (Exception ignored) { }
            }
        }, "sc-touch-sender").start();
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
