package com.screencopy.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PROJECTION = 1001;
    private EditText ipInput;

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
        ip.setText("Bu cihazin IP adresi:\n" + getLocalIp());
        ip.setTextColor(Color.parseColor("#90CAF9"));
        ip.setTextSize(16);
        ip.setPadding(0, dp(12), 0, dp(20));
        root.addView(ip);

        // --- HOST bolumu ---
        root.addView(sectionLabel("Bu telefonu yayinla (Host)"));
        TextView hostHelp = hint(
                "Bozuk/kaynak telefonda bunu secin. Ekran paylasimini onaylayin. " +
                "Diger telefondan dokunarak kontrol icin once asagidaki Erisilebilirlik " +
                "iznini de acin.");
        root.addView(hostHelp);

        Button accBtn = button("Erisilebilirlik iznini ac (kontrol icin)");
        accBtn.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "Listeden ScreenCopy'i acin", Toast.LENGTH_LONG).show();
        });
        root.addView(accBtn);

        Button hostBtn = button("Yayini baslat");
        hostBtn.setOnClickListener(v -> requestProjection());
        root.addView(hostBtn);

        Button stopBtn = button("Yayini durdur");
        stopBtn.setOnClickListener(v -> {
            stopService(new Intent(this, HostService.class));
            Toast.makeText(this, "Yayin durduruldu", Toast.LENGTH_SHORT).show();
        });
        root.addView(stopBtn);

        // --- CONTROLLER bolumu ---
        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));
        root.addView(sp);

        root.addView(sectionLabel("Diger telefonu kontrol et (Controller)"));
        root.addView(hint("Kontrol eden telefonda, karsi telefonun IP adresini yazip baglanin. " +
                "Ikisi ayni Wi-Fi aginda olmali."));

        ipInput = new EditText(this);
        ipInput.setHint("Host IP (orn. 192.168.1.42)");
        ipInput.setInputType(InputType.TYPE_CLASS_PHONE);
        ipInput.setTextColor(Color.WHITE);
        ipInput.setHintTextColor(Color.GRAY);
        root.addView(ipInput);

        Button connBtn = button("Baglan ve kontrol et");
        connBtn.setOnClickListener(v -> {
            String host = ipInput.getText().toString().trim();
            if (host.isEmpty()) {
                Toast.makeText(this, "IP girin", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(this, ControllerActivity.class);
            i.putExtra("host", host);
            startActivity(i);
        });
        root.addView(connBtn);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            Toast.makeText(this, "Yayin basladi. IP: " + getLocalIp(), Toast.LENGTH_LONG).show();
        } else if (requestCode == REQ_PROJECTION) {
            Toast.makeText(this, "Ekran paylasimi iptal edildi", Toast.LENGTH_SHORT).show();
        }
    }

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

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

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
