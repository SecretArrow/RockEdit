# Changelog

All notable changes to Rock Edit are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versioning: [SemVer](https://semver.org/).

## [Unreleased]

### Planned
- Storage Manager: FTP/FTPS/SFTP, WebDAV, Google Drive, Dropbox, OneDrive, GitHub, GitLab
- Preview HTML/Markdown/AsciiDoc

## [0.5.0] - 2026-10-04

### Added
- Penyorotan sintaks diperluas 20 → 48 bahasa: Lua, Perl, R, Objective-C, Dart,
  Scala, Groovy, Haskell, Erlang, Elixir, Clojure, F#, Visual Basic, Assembly,
  TOML, INI, Makefile, CMake, Batch, PowerShell, Vue, GraphQL, Julia, Nim,
  OCaml, LaTeX, Zig, Protocol Buffers — dengan varian komentar multi-baris khas
  (--[[ ]], {- -}, #[[ ]], <# #>, (* *) dan lainnya)
- File populer tanpa ekstensi kini dikenali: Makefile, Dockerfile (shell),
  Gemfile/Rakefile/Vagrantfile (ruby), CMakeLists.txt (cmake)
- Cetak dokumen via layar cetak Android (PDF A4 monospace, paginasi murni
  ter-unit-test: hard wrap 88 kolom, 47 baris/halaman)
- Cadangkan & pulihkan data (JSON via SAF): semua pengaturan + file terbaru +
  posisi kursor + bookmark + set tab terbuka; kunci asing dilewati dengan aman,
  ringkasan jumlah item dipulihkan/dilewati ditampilkan
- Layar Lisensi open-source (atribusi pustaka) dari dialog Tentang

### Changed
- Tokenizer: komentar blok yang lebih panjang kini diutamakan di posisi yang
  sama dengan penanda komentar baris (perbaikan Lua/Julia multi-baris)
- KeyValueStore: tambahan contains() untuk dukungan cadangan yang akurat
- Unit test baru: PrintLayout (6), BackupRestore (7), registry +4; total 181+ unit

## [0.4.0] - 2026-10-04

### Added
- Editor multi-tab (maks 10 tab): bilah tab di bawah toolbar dengan indikator
  perubahan belum disimpan (•), tutup per tab (✕ / tekan lama / menu), tutup tab
  lainnya, tab berikutnya, dan tombol + untuk tab baru
- Membuka file saat editor sudah terbuka otomatis menjadi tab baru
  (launchMode singleTask + onNewIntent), termasuk berbagi teks dari aplikasi lain
- Tab tanpa nama bisa diparalel (untitled, untitled 2, ...) dengan isi masing-masing
- Memulihkan set tab terbuka saat aplikasi dimulai ulang (persist URI + index aktif,
  muat isi tab secara lazy saat pertama diaktifkan; bisa dimatikan di pengaturan)
- Jendela "Buka folder": peramban SAF (DocumentFile) dengan navigasi naik/turun,
  breadcrumb, ikon folder, ukuran file, sortir folder-di-atas dan filter file
  tersembunyi (pengaturan baru), URI folder terakhir diingat
- Simpan semua saat keluar: dialog perubahan-belum-disimpan kini melaporkan jumlah
  file kotor dan menyimpan semua tab bertag URI sebelum menutup editor
- Auto-save kini menyimpan semua tab yang berubah (bukan hanya tab aktif)
- Snapshot rotasi per-tab: teks, caret, scroll, encoding, read-only dipertahankan
  saat rotasi untuk seluruh tab (dengan anggaran total 500 ribu karakter)
- Undo/redo, bookmark, encoding, read-only, dan posisi kursor kini per-tab

### Changed
- E2E baru: MultiTabE2eTest (dua file, pindah tab bolak-balik)
- Unit test baru: TabManager/EditorTab (17 kasus), TabPersistence (6), FolderSort (10),
  pengaturan Files&Tabs (2)
- Dependensi baru: androidx.documentfile 1.0.1

## [0.3.0] - 2026-10-04

### Added
- Penyorotan sintaks untuk 20 bahasa (Kotlin, Java, C, C++, C#, Go, Rust, JavaScript,
  TypeScript, Python, Ruby, PHP, Swift, Shell, SQL, JSON, YAML, XML, HTML, CSS):
  tokenizer murni Kotlin (keyword/string/komentar/angka), deteksi bahasa dari ekstensi
  file, palet terang & gelap/AMOLED, toggle dari menu editor
- Buka ulang file dengan encoding pilihan (15 charset: UTF-8/16/32, ISO-8859-1, ASCII,
  windows-1251/1252, Shift_JIS, GBK, GB18030, Big5, EUC-KR, KOI8-R) dengan konfirmasi
  bila ada perubahan yang belum disimpan
- Simpan dengan encoding pilihan (charset aktif dipakai saat menulis file)
- Pengaturan ukuran font editor (12–24sp, gutter ikut menyesuaikan)
- Pengaturan simpan otomatis saat activity ke background (default mati)
- Menu sisipkan tanggal/waktu (format yyyy-MM-dd HH:mm) pada posisi kursor
- E2E test penyorotan sintaks + unit test tokenizer/registry/charset/pengaturan baru

## [0.2.0] - 2026-10-03

### Added
- Operasi baris: duplikat baris, hapus baris, naik/turunkan baris (mendukung LF/CR/CRLF,
  terminator tetap utuh saat swap; tercatat di undo)
- Bookmark per-baris per-file: tandai/hapus dari menu, daftar penanda dengan lompat-ke-baris
- Pemulihan posisi: kursor + scroll per file tersimpan dan dipulihkan saat file dibuka ulang
- Tema Hitam (AMOLED) sebagai pilihan tema baru
- Mode layar penuh editor (sembunyikan status bar & navigation bar)
- Target & compile SDK 36; AGP 8.9.1; Gradle 8.11.1

### Changed
- Bump dependensi: core-ktx 1.19.1, material 1.14.0, coroutines 1.11.0, androidx.test 1.7.0/1.3.0
- Bump GitHub Actions: checkout v7, setup-java v6, gradle/actions v6, upload-artifact v7, gh-release v3

## [0.1.0] - 2026-10-03

### Added
- Editor teks inti: undo/redo (kapasitas 100), nomor baris tersinkron, word wrap, baca-saja
- Buka/simpan file via SAF tanpa izin storage; simpan-sebagai; persist URI permission
- Deteksi encoding otomatis (juniversalchardet) dengan fallback UTF-8 + peringatan binary
- Deteksi & normalisasi line break (LF/CR/CRLF); preferensi line break saat simpan
- Cari/Ganti (peka huruf, wrap-around, ganti satu/semua), Lompat-ke-baris, Statistik
- Daftar file terbaru (maks 40) dengan hapus per-item dan bersihkan via penghapusan
- Terima ACTION_SEND teks dari aplikasi lain; bagikan teks keluar
- Tema terang/gelap/ikuti-sistem; dialog perubahan-belum-disimpan saat keluar
- Pengaturan: tema, nomor baris, word wrap, line break
- Lokalisasi Indonesia + Inggris; ikon adaptif + monochrome
- CI/CD: lint + unit test + build (CI), emulator e2e dengan auto-retry (E2E),
  auto-release signed APK + SHA256SUMS lewat tag (Release)
