# SlowDNS Lite (Android)

Klien VPN Android modern, ultra-ringan, dan hemat baterai untuk **SSH SlowDNS** dan **V2Ray SlowDNS**, dirancang untuk dipasangkan dengan server `slowdns-reversal/` (Dropbear + V2Ray + dnstt fork).

---

## 1. Fitur Utama
- **Zero Bloat**: Hanya mendukung SSH SlowDNS dan V2Ray SlowDNS.
- **UI Modern**: 100% Kotlin + Jetpack Compose + Material 3.
- **Hemat Baterai**:
  - Pilihan interval KCP dinamis (50ms Battery Saver vs 20ms High Performance).
  - Mode WakeLock opsional (mencegah sleep hanya jika diaktifkan pengguna).
- **Diagnostik & Debug**:
  - Live console viewer di dashboard.
  - Tombol **Share Log** untuk mengirim file debug langsung lewat WhatsApp / Telegram / Email.
- **Zero-Copy Network Pipeline**: Single-process Go core (gVisor Netstack + dnstt client + crypto).

---

## 2. Cara Build di GitHub Actions (Cloud Build)

Karena resource lokal terbatas, seluruh proses build Go AAR (GoMobile) dan kompilasi APK Gradle berjalan otomatis di GitHub Actions.

1. **Inisialisasi Git & Push ke GitHub**:
   ```bash
   cd slowdns-lite-android
   git init
   git add .
   git commit -m "feat: initial commit slowdns lite android"
   git branch -M main
   git remote add origin https://github.com/<USERNAME>/<REPO_NAME>.git
   git push -u origin main
   ```

2. **Otomasi di GitHub Actions**:
   - Masuk ke tab **Actions** di repositori GitHub kamu.
   - Workflow `Build SlowDNS Lite APK` akan berjalan otomatis.
   - Job 1 (`build-go-core`): Mengompilasi `core/` menjadi `TunnelCore.aar` via GoMobile.
   - Job 2 (`build-apk`): Mengompilasi `app/` menjadi file `slowdns-lite-debug-apk.apk`.

3. **Unduh APK**:
   - Setelah selesai, unduh artifact APK di bagian bawah halaman ringkasan run workflow.
