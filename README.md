# MotionLess

Araçta telefon kullanırken mide bulantısını azaltan Android uygulaması.

Ekranın kenarlarına noktalar koyar. Telefonun ivme sensörü aracın hızlanmasını, frenini ve dönüşlerini algılar; noktalar bu harekete ters yönde kayar. Böylece gözünün ekranda gördüğü, iç kulağının hissettiği hareketle uyuşur. Bulantının ana sebebi bu iki duyunun çelişmesidir.

## Özellikler

- Noktalar her uygulamanın üzerinde görünür ve dokunmaları engellemez.
- Telefon hangi açıyla tutulursa tutulsun, yerçekimi yönüne göre aracın ileri ve yan ivmesi ayrıştırılır.
- Üç mod: **Kapalı**, **Oto**, **Açık**. Oto modda ekran açıkken GPS hızı ölçülür; hız eşiği (varsayılan 15 km/s) geçilince ipuçları kendiliğinden açılır, hız eşiğin yarısının altında bir dakika kalınca kapanır. Ekran kapanınca GPS durur.
- Kalıcı bildirimden modlar arasında geçilir (Aç / Oto / Kapat); bildirim anlık hızı gösterir.
- Hızlı Ayarlar kutucuğu her dokunuşta Kapalı → Oto → Açık arasında döner.
- Noktalar Apple'daki gibi kenarlarda düzensiz dağılır; nokta sayısı, hassasiyet, boyut, opaklık, renk ve yerleşim (kenarlar veya tam ekran) ayarlanabilir.
- İsteğe bağlı: hareket yokken noktalar kendiliğinden kaybolur.
- Türkçe ve İngilizce arayüz. Android 8.0 ve üstü.

## Kurulum

[Releases](https://github.com/yunusemreakdeniz/MotionLess/releases/latest) sayfasından son APK'yı indir ve telefona kur. İlk açılışta "Diğer uygulamaların üzerinde gösterme" iznini ver.

## Derleme

```bash
gradle assembleRelease
```

JDK 17 ve Android SDK (API 34) gerekir. `v*` etiketi push edildiğinde GitHub Actions APK'yı imzalayıp release'e ekler. İmza anahtarı repo sırlarında tutulur: `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
