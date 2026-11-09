package com.screencopy.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
    private static final int BG = 0xFF0E1116;
    private static final int TEXT = 0xFFE6EAF0;
    private static final int MUTED = 0xFF8A94A6;
    private static final int ACCENT = 0xFF2D7FF9;

    private EditText ipInput;
    private EditText edMaxDim, edQuality, edFps, edPort;

    private final ActivityResultLauncher<ScanOptions> scanLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() != null) {
                    String content = result.getContents().trim().replace("screencopy://", "");
                    ipInput.setText(content);
                    Toast.makeText(this, "Okundu: " + content, Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(28));
        root.setBackgroundColor(BG);

        TextView title = new TextView(this);
        title.setText("ScreenCopy");
        title.setTextSize(30);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(TEXT);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("IP:  " + getLocalIp());
        sub.setTextColor(ACCENT);
        sub.setTextSize(15);
        sub.setPadding(0, dp(4), 0, dp(18));
        root.addView(sub);

        // ---------- HOST ----------
        LinearLayout host = card(root, "Yayınla  ·  Host",
                "Bu telefonun ekranını paylaş. Arka planda, kilitli ekranda ve ekran kapalıyken çalışır.");
        host.addView(primaryButton("Yayını başlat", v -> requestProjection()));
        host.addView(secondaryButton("Erişilebilirlik izni  (kontrol için)", v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "Listeden ScreenCopy'i açın", Toast.LENGTH_LONG).show();
        }));
        host.addView(secondaryButton("Ekran kapalıyken görüntü izni  (üstten çizim)", v -> requestOverlay()));
        host.addView(secondaryButton("QR göster  (controller okutsun)", v -> showQrDialog()));
        host.addView(secondaryButton("Yayını durdur", v -> {
            stopService(new Intent(this, HostService.class));
            Toast.makeText(this, "Yayın durduruldu", Toast.LENGTH_SHORT).show();
        }));

        // ---------- CONTROLLER ----------
        LinearLayout ctrl = card(root, "Kontrol et  ·  Controller",
                "Karşı telefonu tam ekran görüp dokunarak yönet. Aynı Wi-Fi ağında olun.");
        ipInput = field("Host IP  veya  IP:port");
        ipInput.setInputType(InputType.TYPE_CLASS_TEXT);
        ctrl.addView(ipInput);
        ctrl.addView(primaryButton("Bağlan ve kontrol et", v -> {
            String h = ipInput.getText().toString().trim();
            if (h.isEmpty()) { Toast.makeText(this, "IP girin veya QR tarayın", Toast.LENGTH_SHORT).show(); return; }
            Intent i = new Intent(this, ControllerActivity.class);
            i.putExtra("host", h);
            startActivity(i);
        }));
        ctrl.addView(secondaryButton("QR tara", v -> {
            ScanOptions o = new ScanOptions();
            o.setPrompt("Host QR kodunu tarayın");
            o.setOrientationLocked(false);
            o.setBeepEnabled(false);
            scanLauncher.launch(o);
        }));

        // ---------- AYARLAR ----------
        LinearLayout settings = card(root, "Ayarlar  ·  Kalite",
                "Değişiklikler sonraki yayında geçerli olur. Yüksek değer = daha net, daha fazla veri.");
        edMaxDim  = labeledField(settings, "Çözünürlük üst sınırı (px)", Prefs.maxDim(this));
        edQuality = labeledField(settings, "JPEG kalite (" + Prefs.MIN_QUALITY + "-" + Prefs.MAX_QUALITY + ")", Prefs.quality(this));
        edFps     = labeledField(settings, "FPS (" + Prefs.MIN_FPS + "-" + Prefs.MAX_FPS + ")", Prefs.fps(this));
        edPort    = labeledField(settings, "Port", Prefs.port(this));
        settings.addView(primaryButton("Ayarları kaydet", v -> saveSettings()));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(root);
        setContentView(scroll);
    }

    // ---------- eylemler ----------
    private void requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "İzin zaten verilmiş", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "ScreenCopy için izni açın", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void saveSettings() {
        int maxDim  = clamp(parse(edMaxDim, Prefs.DEF_MAX_DIM), Prefs.MIN_MAX_DIM, Prefs.MAX_MAX_DIM);
        int quality = clamp(parse(edQuality, Prefs.DEF_QUALITY), Prefs.MIN_QUALITY, Prefs.MAX_QUALITY);
        int fps     = clamp(parse(edFps, Prefs.DEF_FPS), Prefs.MIN_FPS, Prefs.MAX_FPS);
        int port    = clamp(parse(edPort, Prefs.DEF_PORT), 1024, 65535);
        Prefs.save(this, maxDim, quality, fps, port);
        edMaxDim.setText(String.valueOf(maxDim));
        edQuality.setText(String.valueOf(quality));
        edFps.setText(String.valueOf(fps));
        edPort.setText(String.valueOf(port));
        Toast.makeText(this, "Ayarlar kaydedildi", Toast.LENGTH_SHORT).show();
    }

    private void showQrDialog() {
        String payload = getLocalIp() + ":" + Prefs.port(this);
        try {
            Bitmap bmp = new BarcodeEncoder().encodeBitmap(payload, BarcodeFormat.QR_CODE, 760, 760);
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bmp);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundColor(0xFF141A22);
            box.setPadding(dp(18), dp(18), dp(18), dp(10));
            iv.setPadding(0, 0, 0, dp(10));
            box.addView(iv);
            TextView tv = new TextView(this);
            tv.setText(payload);
            tv.setGravity(Gravity.CENTER);
            tv.setTextColor(TEXT);
            tv.setTextSize(17);
            box.addView(tv);
            new AlertDialog.Builder(this)
                    .setView(box)
                    .setPositiveButton("Kapat", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "QR oluşturulamadı", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "Yayın başladı · " + getLocalIp() + ":" + Prefs.port(this),
                    Toast.LENGTH_LONG).show();
        } else if (requestCode == REQ_PROJECTION) {
            Toast.makeText(this, "Ekran paylaşımı iptal edildi", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- UI yapı taşları ----------
    private LinearLayout card(LinearLayout parent, String titleText, String hintText) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(getDrawable(R.drawable.card_bg));
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        c.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(titleText);
        t.setTextColor(TEXT);
        t.setTextSize(18);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        c.addView(t);

        TextView h = new TextView(this);
        h.setText(hintText);
        h.setTextColor(MUTED);
        h.setTextSize(13);
        h.setPadding(0, dp(4), 0, dp(10));
        c.addView(h);

        parent.addView(c);
        return c;
    }

    private Button primaryButton(String text, View.OnClickListener l) {
        return styledButton(text, l, R.drawable.btn_primary, Color.WHITE);
    }

    private Button secondaryButton(String text, View.OnClickListener l) {
        return styledButton(text, l, R.drawable.btn_secondary, 0xFFCDD5E0);
    }

    private Button styledButton(String text, View.OnClickListener l, int bg, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(textColor);
        b.setBackground(getDrawable(bg));
        b.setStateListAnimator(null);
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(9);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private EditText field(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextColor(TEXT);
        et.setHintTextColor(MUTED);
        et.setTextSize(15);
        et.setBackground(getDrawable(R.drawable.field_bg));
        et.setPadding(dp(14), dp(13), dp(14), dp(13));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        et.setLayoutParams(lp);
        return et;
    }

    private EditText labeledField(LinearLayout parent, String label, int value) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(MUTED);
        tv.setTextSize(13);
        tv.setPadding(0, dp(10), 0, dp(2));
        parent.addView(tv);
        EditText et = field("");
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(value));
        parent.addView(et);
        return et;
    }

    private int parse(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); }
        catch (Exception ex) { return def; }
    }

    private int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
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
