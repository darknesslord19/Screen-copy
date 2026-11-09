package com.screencopy.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PROJECTION = 1001;
    private EditText ipInput;
    private EditText edMaxDim, edQuality, edFps, edPort;

    private final ActivityResultLauncher<ScanOptions> scanLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() != null) {
                    String content = result.getContents().trim();
                    content = content.replace("screencopy://", "");
                    ipInput.setText(content);
                    Toast.makeText(this, "Okundu: " + content + "\nBağlanmak için 'Bağlan'a basın",
                            Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.parseColor("#101418"));

        TextView title = new TextView(this);
        title.setText("ScreenCopy");
        title.setTextSize(28);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        TextView ip = new TextView(this);
        ip.setText("Bu cihazin IP adresi:  " + getLocalIp());
        ip.setTextColor(Color.parseColor("#90CAF9"));
        ip.setTextSize(16);
        ip.setPadding(0, dp(10), 0, dp(16));
        root.addView(ip);

        // --- HOST ---
        root.addView(sectionLabel("Bu telefonu yayinla (Host)"));
        root.addView(hint("Bozuk/kaynak telefonda bunu secin. Kontrol icin once Erisilebilirlik " +
                "iznini acin. Yayin arka planda, kilitli ekranda ve ekran kapaliyken de calisir."));

        Button accBtn = button("1) Erisilebilirlik iznini ac");
        accBtn.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "Listeden ScreenCopy'i acin", Toast.LENGTH_LONG).show();
        });
        root.addView(accBtn);

        Button hostBtn = button("2) Yayini baslat");
        hostBtn.setOnClickListener(v -> requestProjection());
        root.addView(hostBtn);

        Button qrBtn = button("QR goster (controller buradan okusun)");
        qrBtn.setOnClickListener(v -> showQrDialog());
        root.addView(qrBtn);

        Button stopBtn = button("Yayini durdur");
        stopBtn.setOnClickListener(v -> {
            stopService(new Intent(this, HostService.class));
            Toast.makeText(this, "Yayin durduruldu", Toast.LENGTH_SHORT).show();
        });
        root.addView(stopBtn);

        // --- CONTROLLER ---
        root.addView(spacer(24));
        root.addView(sectionLabel("Diger telefonu kontrol et (Controller)"));
        root.addView(hint("Host IP'yi yazin veya QR tarayin. Ikisi ayni Wi-Fi aginda olmali."));

        ipInput = new EditText(this);
        ipInput.setHint("Host IP veya IP:port");
        ipInput.setInputType(InputType.TYPE_CLASS_TEXT);
        ipInput.setTextColor(Color.WHITE);
        ipInput.setHintTextColor(Color.GRAY);
        root.addView(ipInput);

        Button scanBtn = button("QR tara");
        scanBtn.setOnClickListener(v -> {
            ScanOptions o = new ScanOptions();
            o.setPrompt("Host telefonun QR kodunu tarayin");
            o.setOrientationLocked(false);
            o.setBeepEnabled(false);
            scanLauncher.launch(o);
        });
        root.addView(scanBtn);

        Button connBtn = button("Baglan ve kontrol et");
        connBtn.setOnClickListener(v -> {
            String host = ipInput.getText().toString().trim();
            if (host.isEmpty()) { Toast.makeText(this, "IP girin", Toast.LENGTH_SHORT).show(); return; }
            Intent i = new Intent(this, ControllerActivity.class);
            i.putExtra("host", host);
            startActivity(i);
        });
        root.addView(connBtn);

        // --- AYARLAR ---
        root.addView(spacer(24));
        root.addView(sectionLabel("Ayarlar"));
        root.addView(hint("Yayin kalitesi ve baglanti. Degisiklikler bir sonraki yayinda gecerli olur."));

        edMaxDim  = labeledField(root, "Cozunurluk ust siniri (px, 200-2000)", Prefs.maxDim(this));
        edQuality = labeledField(root, "JPEG kalite (10-90)", Prefs.quality(this));
        edFps     = labeledField(root, "FPS (1-30)", Prefs.fps(this));
        edPort    = labeledField(root, "Port (1024-65535)", Prefs.port(this));

        Button saveBtn = button("Ayarlari kaydet");
        saveBtn.setOnClickListener(v -> saveSettings());
        root.addView(saveBtn);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void saveSettings() {
        try {
            int maxDim  = clamp(parse(edMaxDim, Prefs.DEF_MAX_DIM), 200, 2000);
            int quality = clamp(parse(edQuality, Prefs.DEF_QUALITY), 10, 90);
            int fps     = clamp(parse(edFps, Prefs.DEF_FPS), 1, 30);
            int port    = clamp(parse(edPort, Prefs.DEF_PORT), 1024, 65535);
            Prefs.save(this, maxDim, quality, fps, port);
            edMaxDim.setText(String.valueOf(maxDim));
            edQuality.setText(String.valueOf(quality));
            edFps.setText(String.valueOf(fps));
            edPort.setText(String.valueOf(port));
            Toast.makeText(this, "Ayarlar kaydedildi", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Gecersiz deger", Toast.LENGTH_SHORT).show();
        }
    }

    private void showQrDialog() {
        String payload = getLocalIp() + ":" + Prefs.port(this);
        try {
            Bitmap bmp = new BarcodeEncoder().encodeBitmap(payload, BarcodeFormat.QR_CODE, 700, 700);
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bmp);
            iv.setPadding(dp(16), dp(16), dp(16), dp(8));

            TextView tv = new TextView(this);
            tv.setText(payload);
            tv.setGravity(Gravity.CENTER);
            tv.setTextSize(16);
            tv.setPadding(0, 0, 0, dp(16));

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.addView(iv);
            box.addView(tv);

            new AlertDialog.Builder(this)
                    .setTitle("Controller bu kodu tarasin")
                    .setView(box)
                    .setPositiveButton("Kapat", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "QR olusturulamadi: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void requestProjection() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROJECTION && resultCode == Activity.RESULT_OK && data != null) {
            Intent svc = new Intent(this, HostService.class);
            svc.putExtra(HostService.EXTRA_RESULT_CODE, resultCode);
            svc.putExtra(HostService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
            else startService(svc);
            Toast.makeText(this, "Yayin basladi. IP: " + getLocalIp() + ":" + Prefs.port(this),
                    Toast.LENGTH_LONG).show();
        } else if (requestCode == REQ_PROJECTION) {
            Toast.makeText(this, "Ekran paylasimi iptal edildi", Toast.LENGTH_SHORT).show();
        }
    }

    // --- UI yardımcıları ---
    private EditText labeledField(LinearLayout parent, String label, int value) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.parseColor("#B0BEC5"));
        tv.setTextSize(13);
        tv.setPadding(0, dp(8), 0, 0);
        parent.addView(tv);
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(value));
        et.setTextColor(Color.WHITE);
        parent.addView(et);
        return et;
    }

    private int parse(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); }
        catch (Exception ex) { return def; }
    }

    private int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private TextView sectionLabel(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(18);
        tv.setPadding(0, dp(8), 0, dp(4));
        return tv;
    }

    private TextView hint(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(Color.parseColor("#B0BEC5"));
        tv.setTextSize(13);
        tv.setPadding(0, 0, 0, dp(8));
        return tv;
    }

    private Button button(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h)));
        return v;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    public static String getLocalIp() {
        try {
            List<NetworkInterface> ifaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : ifaces) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (ip != null && !ip.startsWith("169.254")) return ip;
                    }
                }
            }
        } catch (Exception ignored) {}
        return "bilinmiyor";
    }
}
