# Fair Blocks

Puzzle balok 8×8 dengan RNG fair, mode Daily, Replay, dan **Versus 1v1**: vs AI, LAN (tanpa internet), atau Online (Firebase).

## Cara bikin APK (lewat GitHub, tanpa install apa-apa di laptop)

1. Buat repo baru di GitHub, misalnya `fair-blocks` (boleh private).
2. Upload **semua isi folder ini** lewat web GitHub (*Add file → Upload files*).
   Termasuk folder tersembunyi `.github/workflows/build-apk.yml`.
   Kalau folder `.github` tidak ikut ter-upload, buat file baru di GitHub dengan nama persis
   `.github/workflows/build-apk.yml` lalu tempel isinya.
3. Buka tab **Actions** → workflow **Build APK** jalan otomatis (±5–8 menit).
   Bisa juga dijalankan manual: *Build APK → Run workflow*.
4. Setelah centang hijau, buka run-nya → bagian **Artifacts** → download **FairBlocks-apk** (zip berisi `app-debug.apk`).
5. Install di kedua HP (izinkan "install dari sumber tidak dikenal").

## Main LAN

- Kedua HP di **Wi-Fi yang sama**, atau satu HP menyalakan **hotspot** dan HP lain tersambung ke hotspot itu.
- HP 1: *Versus → LAN → Buat Room*. Pengaturan mode (Classic/Item, Survival/Race) diambil dari HP ini.
- HP 2: *Versus → LAN → Cari Room* → tap nama room.
  Kalau room tidak muncul (beberapa router/hotspot memblokir pencarian), ketik IP yang tampil di HP 1 lalu *Sambung*.
- Tanpa internet sama sekali.

## Main Online (beda jaringan) — setup Firebase sekali saja

1. Buka https://console.firebase.google.com → **Add project** (nama bebas, misal `fair-blocks`). Google Analytics boleh dimatikan.
2. Menu kiri **Build → Realtime Database → Create Database**. Lokasi: **Singapore (asia-southeast1)**. Pilih **Start in test mode**.
3. Tab **Rules**, ganti isinya dengan ini lalu **Publish**:
   ```json
   {
     "rules": {
       "rooms": { ".read": true, ".write": true },
       ".read": false,
       ".write": false
     }
   }
   ```
4. Salin URL database di bagian atas tab **Data**, contoh:
   `https://fair-blocks-default-rtdb.asia-southeast1.firebasedatabase.app`
5. Pilih salah satu:
   - **Paling praktis:** buka `www/index.html`, cari `const FIREBASE_DB_URL=''` dan isi URL-nya di antara tanda kutip, lalu upload ulang → APK baru otomatis.
   - **Tanpa edit file:** di game, *Versus → Online*, tempel URL di kolom "URL Database Firebase" (sekali saja di tiap HP).
6. Main: HP 1 *Buat Room* → muncul kode 4 angka. HP 2 *Gabung pakai Kode*. Pengaturan mode diambil dari pembuat room.

Paket gratis (Spark) lebih dari cukup untuk 2 orang. Room dihapus otomatis saat pembuat room keluar.
Catatan: rules di atas membuka folder `rooms` untuk siapa pun yang tahu URL-nya — aman untuk main berdua, jangan simpan data penting di database ini.

## Isi folder

| File | Fungsi |
|---|---|
| `www/index.html` | Seluruh game (satu file) |
| `android-src/LanPlugin.java` | Plugin native LAN: server TCP port 47800 + pencarian room (NSD/mDNS) |
| `android-src/MainActivity.java` | Mendaftarkan plugin, layar tetap menyala |
| `scripts/patch-android.js` | Menyalin plugin, menambah izin jaringan, mengunci layar potret, memasang ikon & splash |
| `android-res/` | Ikon aplikasi (semua ukuran + adaptive) dan layar splash |
| `.github/workflows/build-apk.yml` | Build APK otomatis di GitHub Actions |

## Update game

Cukup ganti `www/index.html` di repo (upload ulang) → Actions build APK baru otomatis.

## Catatan

- Font Nunito dimuat dari Google Fonts. Tanpa internet, game memakai font cadangan bawaan HP.
- Sound effect dari kamu sudah tertanam di `www/index.html` (tidak perlu file terpisah).
- APK ini versi *debug* untuk pemakaian pribadi.
