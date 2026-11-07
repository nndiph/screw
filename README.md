# ScrewBot (Android)

Aplikasi auto clicker pemindai baut yang berjalan langsung di HP, dengan panel melayang di atas game.
Alur: tangkap layar (MediaProjection) -> pindai baut (OpenCV) -> ketuk lewat layanan aksesibilitas.

## Membangun APK (tanpa Android Studio)
1. Buat repo GitHub, unggah SEMUA isi folder ini ke akar repo (termasuk folder `.github`).
2. Tab **Actions** -> workflow **Build APK** -> tunggu hijau (sekitar 5 menit; bisa juga **Run workflow**).
3. Buka run yang selesai -> **Artifacts** -> unduh `screwbot-debug-apk` -> ekstrak -> pasang `app-debug.apk`.
Alternatif: buka folder ini di Android Studio lalu Run.

## Pemasangan di HP (sekali saja)
1. Buka ScrewBot -> langkah 1: izinkan "Tampil di atas aplikasi lain".
2. Langkah 2: Pengaturan -> Aksesibilitas -> Layanan terpasang -> ScrewBot -> aktifkan.
   Android 13+: bila abu-abu, Info aplikasi ScrewBot -> titik tiga -> "Izinkan setelan terbatas".
3. Langkah 3: "Tampilkan panel ScrewBot" -> izinkan rekam layar -> buka game.

## Memakai
- **PINDAI**: hanya menggambar lingkaran di baut yang terdeteksi selama 3 detik (tidak mengetuk). Pakai ini dulu.
- **MULAI / JEDA**: bot berjalan. Lingkaran putih = baut yang akan diketuk.
- **X**: tutup. Panel bisa digeser lewat tulisan "ScrewBot".
- Bot berhenti sendiri bila layar bukan layar game (iklan, popup, level selesai) atau tidak ada baut yang bisa diketuk.

## Batas
- Deteksi klasik: sekitar 60-65% baut terdeteksi per tampilan; sisanya muncul setelah objek diputar bot.
- Posisi kotak dan lubang dikalibrasi dari satu HP (rasio layar 1159x2576). HP lain: ubah `Hud` di `Logic.kt`.
- Belum menangani popup iklan/hadiah atau pindah level otomatis.
- Kode ini dikompilasi dan diuji logikanya di luar Android; build penuh dilakukan oleh GitHub Actions.
