# Changelog

All notable changes to Rock Edit are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versioning: [SemVer](https://semver.org/).

## [Unreleased]

### Planned
- Syntax highlighting (TextMate grammars) untuk bahasa populer
- Multi-tab editing
- Storage Manager: FTP/FTPS/SFTP, WebDAV, Google Drive, Dropbox, OneDrive, GitHub, GitLab
- Folder drawer, bookmark folder, backup/restore JSON
- Preview HTML/Markdown/AsciiDoc

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
