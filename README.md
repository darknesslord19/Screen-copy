# ScreenCopy

İki Android telefon arasında **aynı Wi-Fi ağı** üzerinden ekran aktarma ve
uzaktan dokunmatik kontrol uygulaması. Genel amaçlı kullanım içindir:
bir telefonun ekranını diğerinden tam ekran görüp dokunarak kontrol
edebilirsiniz (örn. ekranı/dokunmatiği kısmen bozuk bir telefonu yönetmek,
ikinci bir cihazı uzaktan kullanmak).

Tek APK, iki mod:

- **Host (yayınlayan):** Ekranını paylaşır ve gelen dokunmaları uygular.
- **Controller (kontrol eden):** Host'un ekranını tam ekran görür, dokunarak
  kontrol eder.

## APK'yı indirme

Her `main` push'unda GitHub Actions APK'yı otomatik derler:

- **Releases → ScreenCopy (son sürüm) → `ScreenCopy.apk`**, veya
- Actions sekmesindeki ilgili çalışmanın **Artifacts** bölümü.

## Kullanım

Her iki telefona da aynı APK'yı kurun ve ikisini de **aynı Wi-Fi ağına** bağlayın.

### Yayınlanacak (Host) telefon
1. Uygulamayı açın.
2. **Erişilebilirlik iznini aç** → listeden **ScreenCopy**'i etkinleştirin
   (bu izin, uzaktan dokunmanın ekrana uygulanabilmesi için gereklidir).
3. **Yayını başlat** → ekran paylaşımını onaylayın.
4. Ekranın üstünde yazan **IP adresini** not alın.

### Kontrol eden (Controller) telefon
1. Uygulamayı açın.
2. Alt bölümdeki kutuya host telefonun **IP adresini** yazın.
3. **Bağlan ve kontrol et** → host ekranı tam ekran açılır; dokunmalarınız
   karşı telefona iletilir.

## Tam kontrol (gerçek telefon gibi)

Controller ekranının altındaki çubuk, donanım tuşlarını taklit eder:

- **◀ Geri**, **● Ana ekran**, **■ Son uygulamalar**, **🔔 Bildirim paneli**
- **⌨ Yaz**: açılan kutuya yazdığınız metin karşı telefonda odaktaki alana girilir.
- Ekranda dokunma, kaydırma, basılı tutma (uzun basma) jestleri doğrudan iletilir.

## QR ile bağlanma

- Host telefonda **QR goster** → ekranda IP:port QR kodu çıkar.
- Controller telefonda **QR tara** → kamerayla okutun, IP otomatik dolar, **Baglan**.

## Ayarlar

Ana ekrandaki **Ayarlar** bölümünden değiştirip **kaydedin** (sonraki yayında geçerli):

- **Çözünürlük üst sınırı** (px) — düşürürsen daha akıcı, yükseltirsen daha net.
- **JPEG kalite** (10–90), **FPS** (1–30), **Port** (1024–65535).

## Arka plan / kilitli ekran / ekran kapalı

Host bir **foreground service** olarak çalışır; CPU **wake lock** ve **Wi‑Fi lock**
ile ekran kapanıp kilitlense bile yayın ve bağlantı sürer. (Bazı cihazlarda ekran
fiziksel olarak kapandığında görüntü kararabilir; kontrol ve bağlantı yine çalışır.)
Telefonun pil optimizasyonundan uygulamayı muaf tutmak kesintisizliği artırır.

## Notlar ve sınırlar

- Yalnızca **yerel ağ (LAN)** üzerinde çalışır; şifreleme yoktur, güvenilir
  ağda kullanın. Port: `7843`.
- Gecikme ve kalite ağa bağlıdır; yayın çözünürlüğü bant genişliği için
  düşürülür (`HostService` içindeki `TARGET_MAX_DIM`, `JPEG_QUALITY`,
  `MIN_FRAME_INTERVAL_MS` ile ayarlanabilir).
- Uzaktan kontrol; dokunma, kaydırma ve basılı tutmayı destekler
  (Erişilebilirlik jestleri ile). Android 7.0+ (API 24) gerekir.
- Bozuk telefonun ekranı tamamen görüntü vermiyorsa, ekran paylaşımını ilk
  kez onaylamak için OTG fare veya HDMI çıkışı gerekebilir.

## Yerel derleme

```bash
./gradlew assembleDebug
# Çıktı: app/build/outputs/apk/debug/app-debug.apk
```

Android SDK kurulu bir ortam gerekir (CI bunu otomatik kurar).
