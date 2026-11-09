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

## Cara bot memutuskan
1. Baca warna kotak yang terbuka dan sisa lubangnya (kotak abu-abu "MEMBUKA" diabaikan).
2. Cari kandidat baut, nilai tiap kandidat dengan classifier kecil (MLP, `ModelWeights.kt`) yang dilatih dari
   ~2100 kandidat berlabel di 7 screenshot (level 1, 2, 5).
3. Warna diambil dari bagian dalam kepala baut, palet: merah, coklat, oranye, khaki, kuning, hijau, toska, biru,
   lavender, ungu, pink, putih. Warna di celah antar pita = ragu, tidak diketuk.
4. Hanya baut yang warnanya sama dengan kotak, sebanyak sisa lubang, dan titik ketuknya tidak dekat baut warna lain.
5. Tidak ada yang cocok -> putar objek. Penyangga abu-abu tidak diisi (kecuali diizinkan di pengaturan).
6. Penyangga terisi tanpa disengaja -> mode hati-hati (ketuk satu-satu); 3 kali -> bot berhenti.

## Batas
- Uji pada screenshot berlabel (tanpa melihat gambar uji saat latih): 0 ketukan ke baut warna salah.
  Sebagian baut belum terdeteksi per tampilan; bot memutar objek untuk mencarinya.
- Posisi kotak dan lubang dikalibrasi dari satu HP (rasio layar 1159x2576). HP lain: ubah `Hud` di `Logic.kt`.
- Belum menangani popup iklan/hadiah atau pindah level otomatis.
- Kode ini dikompilasi dan diuji logikanya di luar Android; build penuh dilakukan oleh GitHub Actions.
