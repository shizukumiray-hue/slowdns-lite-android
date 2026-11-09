# Dokumentasi Desain & Arsitektur: SlowDNS Lite (Android)

File ini mendefinisikan arsitektur lengkap aplikasi Android klien VPN ultra-ringan (**SlowDNS Lite**) yang menggantikan kompleksitas HTTP Custom. Aplikasi hanya memuat 2 protokol: **SSH over SlowDNS** dan **V2Ray (VLESS/VMess) over SlowDNS**, dipasangkan langsung dengan server di `slowdns-reversal/`.

---

## 1. Prinsip Utama Desain (Zero Bloat & Battery First)

| Masalah di HTTP Custom | Solusi di SlowDNS Lite | Dampak Efisiensi |
|---|---|---|
| Biner Go monolith 42 MB (`libaio.so`) | Engine Go mini (`libcore.so` / `.aar`), target < 8 MB | RAM turun dari ~180 MB ke < 35 MB |
| Multi-hop context switch (TUN -> C tun2socks -> Local SOCKS5 -> Trilead SSH -> dnstt Go) | **Single-Process Zero-Copy Pipeline** (TUN fd langsung di-handle Netstack Go internal) | Salin memori berkurang 75%, CPU cycle turun drastis |
| Polling KCP agresif 10ms | Dynamic KCP clock (Default 50ms battery-saver, 20ms performance mode) | Radio modem dapat turun ke RRC idle, baterai tahan 3x lipat |
| WakeLock permanen tanpa opsi | **Toggleable WakeLock** + `JobIntentService`/Foreground Service hemat daya | Mencegah *battery drain* saat layar mati |
| UI Java XML kuno menumpuk (Activity/Adapter/Fragment) | **100% Kotlin + Jetpack Compose + Material 3** (Single-Activity MVI/MVVM) | Beban rendering GPU/CPU minim, nol boilerplate |
| Resource lokal minim | **100% Cloud Build via GitHub Actions CI/CD** (Build Go via GoMobile + Build APK Gradle) | Mesin lokal tidak terbebani kompilasi CGO/Android SDK |

---

## 2. Diagram Alur Jaringan Zero-Copy

```text
[Aplikasi Android / Browser]
           │
           ▼
[Android VpnService TUN Interface] (Kotlin)
           │
           ▼ (Pass TUN File Descriptor via JNI)
┌─────────────────────────────────────────────────────────────┐
│                 NATIVE CORE GO (libcore.so)                 │
│                                                             │
│  [gVisor Netstack / TUN Handler]                            │
│           │                                                 │
│           ├──────────────────────┬──────────────────────┐   │
│           ▼                      ▼                      │   │
│     [SSH Client]           [V2Ray Client]               │   │
│  (golang.org/x/crypto)     (VLESS / VMess)              │   │
│           │                      │                      │   │
│           └──────────┬───────────┘                      │   │
│                      ▼                                  │   │
│         [dnstt Client Core (Go)]                        │   │
│         - Noise_NK Handshake (Curve25519)               │   │
│         - ChaCha20-Poly1305 AEAD                        │   │
│         - KCP Packetizer + FEC                          │   │
│         - Base32 DNS Query Builder (TXT / CNAME)        │   │
│                      │                                  │   │
│                      ▼                                  │   │
│         [SocketProtector Bridge (JNI)]                  │   │
└──────────────────────┼──────────────────────────────────────┘
                       │ (VpnService.protect(fd) → bypass TUN)
                       ▼
             [UDP Socket :53 Fisik]
                       │
                       ▼
         [ISP / Recursive DNS Resolver]
                       │ (Query NS autoritatif)
                       ▼
         [VPS: slowdns Server (Port 53)]
                       │
         ┌─────────────┴─────────────┐
         ▼                           ▼
  [Dropbear SSH :22]         [V2Ray Core :10085]
```

---

## 3. Komponen Arsitektur Android (Kotlin + Compose)

### 3.1 Struktur Paket
```text
com.daisy.mashiro/
├── core/
│   ├── bridge/             # JNI / GoMobile bridge (TunnelCore.aar)
│   ├── service/            # VpnService, NotificationManager, WakeLockManager
│   └── logger/             # In-memory circular log buffer + Export helper
├── data/
│   ├── model/              # Data class: ProfileConfig, TunnelMode, DnsRecordType
│   └── repository/         # DataStore Preferences (CRUD Profil & Settings)
└── ui/
    ├── theme/              # Material 3 Theme, Typography, ColorScheme
    ├── navigation/         # Compose Navigation Graph
    ├── screens/
    │   ├── home/           # Dashboard: Tombol Connect, Status, Ping, Data Counter
    │   ├── config/         # Form Input SSH / V2Ray, Server Key, Domain, Resolver
    │   ├── settings/       # Toggle WakeLock, KCP Interval, DNS Record Mode
    │   └── logs/           # Real-time Log Viewer + Tombol "Share / Kirim Log"
    └── viewmodel/          # MainViewModel (UI StateFlow)
```

### 3.2 Manajemen Baterai & WakeLock
- **Mode Normal (Default)**: Tidak mengunci CPU wakelock saat layar mati. Bergantung pada alarm interval ringan untuk keepalive socket.
- **Mode WakeLock Aktif (Toggleable)**:
  ```kotlin
  class WakeLockManager(private val context: Context) {
      private var wakeLock: PowerManager.WakeLock? = null

      fun acquire() {
          if (wakeLock == null) {
              val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
              wakeLock = powerManager.newWakeLock(
                  PowerManager.PARTIAL_WAKE_LOCK,
                  "SlowDnsLite::TunnelWakeLock"
              )
          }
          wakeLock?.acquire(10 * 60 * 1000L /* 10 menit timeout pengaman */)
      }

      fun release() {
          if (wakeLock?.isHeld == true) wakeLock?.release()
      }
  }
  ```

### 3.3 Sistem Debug & Ekspor Log
- Circular buffer 1.000 baris di memori untuk mencegah overhead I/O disk.
- Ekspor log langsung via Android Share Sheet (`Intent.ACTION_SEND`):
  ```kotlin
  fun exportLogs(context: Context, logText: String) {
      val file = File(context.cacheDir, "slowdns_debug_${System.currentTimeMillis()}.log")
      file.writeText(logText)
      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      
      val sendIntent = Intent(Intent.ACTION_SEND).apply {
          type = "text/plain"
          putExtra(Intent.EXTRA_STREAM, uri)
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      context.startActivity(Intent.createChooser(sendIntent, "Kirim Log Debug"))
  }
  ```

---

## 4. Desain Go Native Core (`libcore`)

Menggabungkan 3 modul utama dalam 1 package Go tanpa dependensi C library:

1. **TUN Interface**: Menggunakan `gvisor.dev/gvisor/pkg/tcpip/adapters/gonet` dan `pkg/tcpip/stack` (pure Go userspace TCP/IP stack).
2. **Klien SSH**: Menggunakan `golang.org/x/crypto/ssh`. Dial langsung ke tunnel virtual dnstt.
3. **Klien V2Ray**: Parser protokol VLESS/VMess ultra-ringan (tanpa framework Xray raksasa yang membengkak).
4. **dnstt Engine**:
   - Noise Handshake `Noise_NK_25519_ChaChaPoly_BLAKE2s`.
   - Kompatibel penuh dengan flag server di `slowdns-reversal`:
     - Base32 QNAME subdomain encoding.
     - Support `-record-type`: TXT (`1`), CNAME (`2`), atau `auto`.
     - Target upstream VPS: `127.0.0.1:22` (Dropbear) atau `127.0.0.1:10085` (V2Ray).

---

## 5. Pipeline CI/CD GitHub Actions

Kompilasi dilakukan di cloud GitHub Actions (gratis 2.000 menit/bulan) untuk mengatasi keterbatasan CPU/RAM lokal.

Alur 2-Job di `.github/workflows/build.yml`:
1. **Job 1: Build Go Core (GoMobile)**
   - OS: `ubuntu-latest`.
   - Setup Go 1.22 + Android NDK r26b.
   - Install `gomobile`: `go install golang.org/x/mobile/cmd/gomobile@latest`.
   - Compile: `gomobile bind -v -target=android/arm64,android/arm -o app/libs/TunnelCore.aar ./core`.
   - Simpan artifact `TunnelCore.aar`.
2. **Job 2: Build Android APK (Gradle)**
   - Setup JDK 17.
   - Download artifact `TunnelCore.aar` ke `app/libs/`.
   - Jalankan `./gradlew assembleRelease` atau `assembleDebug`.
   - Upload file APK (`app-release.apk`) ke GitHub Releases atau GitHub Actions Artifacts.

---

## 6. Checklist Implementasi

- [x] Repository setup & template `.github/workflows/build.yml`.
- [x] Source Go `core/` (dnstt client + tun2netstack + ssh/vless + sync.Pool + atomic counters).
- [x] Binding JNI / Gomobile interface `TunnelCore`.
- [x] Project Android Compose + Material 3 (Gradle kts).
- [x] Layanan `VpnService` + `SocketProtector` + traffic tracker.
- [x] Fitur UI: Status Dashboard, Penghitung Paket Keluar/Masuk (Tx/Rx), Riwayat Kuota (Hari ini, Kemarin, Minggu ini, Bulan ini), Setting WakeLock, Screen Log & Export.
- [x] Dukungan Format: V2Ray SlowDNS RAW TCP (`type=tcp&security=none`) + SSH SlowDNS RAW TCP (Nol Host).
- [x] Mode Normal: SSH Direct / Enhanced (SSL + Payload) & V2Ray Full Core (WS/gRPC/TCP/xhttp/Mux).
- [x] Pengaturan DNS: Pilihan Local DNS (bawaan ISP) atau Custom DNS manual + 9 Tipe Record DNS (TXT, AUTO, CNAME, dll).
- [x] ABI Output: armeabi-v7a (v7 / 32-bit), arm64-v8a (v8 / 64-bit), dan Universal APK.
