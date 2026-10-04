# Rock Edit

![CI](https://github.com/SecretArrow/RockEdit/actions/workflows/ci.yml/badge.svg)
![E2E](https://github.com/SecretArrow/RockEdit/actions/workflows/e2e.yml/badge.svg)

**Rock Edit** adalah editor teks & kode yang cepat, stabil, dan **100% perangkat lunak bebas**
(GPL-3.0) untuk Android — tanpa iklan, tanpa akun, tanpa telemetri, tanpa izin internet.
Semua file dan kredensial Anda tidak pernah meninggalkan perangkat.

## Fitur (v0.3.0)

- Buka & simpan file teks apa pun lewat Storage Access Framework (tanpa izin storage!)
- Editor multi-baris dengan **undo/redo tanpa batas praktis** (100 langkah berkapasitas penuh)
- **Penyorotan sintaks untuk 20 bahasa** (Kotlin, Java, C/C++, C#, Go, Rust, JS/TS, Python,
  Ruby, PHP, Swift, Shell, SQL, JSON, YAML, XML, HTML, CSS) — deteksi otomatis dari ekstensi,
  palet terang/gelap/AMOLED, dapat dimatikan dari menu
- **Buka ulang dengan encoding** & **simpan dengan encoding** (UTF-8/16/32, Shift_JIS, GBK,
  Big5, EUC-KR, dan lainnya) di atas deteksi otomatis
- **Ukuran font** editor dapat diatur (12–24sp) + **simpan otomatis** saat pindah aplikasi (opsional)
- Nomor baris + gutter tersinkron, word wrap (on/off), mode baca-saja
- **Operasi baris**: duplikat, hapus, naik/turunkan baris (LF/CR/CRLF aman, masuk undo)
- **Bookmark per-baris**: tandai baris penting, lompat lewat daftar penanda
- **Pemulihan posisi**: kursor & scroll per file diingat saat dibuka ulang
- **Cari / Ganti** dengan opsi peka-huruf, wrap-around, ganti satu/semua
- **Lompat ke baris** + **statistik** karakter/kata/baris + **sisipkan tanggal/waktu**
- **Deteksi encoding otomatis** (juniversalchardet) + peringatan file binary
- **Deteksi & pertahankan line break** file (LF / CR / CRLF), dapat dipaksa di pengaturan
- Daftar **file terbaru** (maks. 40) + terima teks dari aplikasi lain (share-in)
- Tema terang/gelap/**hitam AMOLED**/ikuti sistem, mode **layar penuh**, bahasa Indonesia + Inggris
- Bagikan teks ke aplikasi lain
- Dialog "perubahan belum disimpan" saat keluar — pekerjaan Anda tidak hilang begitu saja

## Roadmap

Rencana lengkap menuju paritas fitur penuh ada di dokumen blueprint proyek:
highlight syntax untuk ratusan bahasa, multi-tab, folder drawer, Storage Manager
(FTP/SFTP/WebDAV/Drive/Dropbox/OneDrive/GitHub/GitLab), preview HTML/Markdown,
backup/restore, dan lainnya — dirilis bertahap lewat pipeline CI/CD ini.

## Privasi

- **Tanpa izin internet** — secara teknis mustahil mengirim data Anda ke mana pun
- Tanpa analitik, tanpa crash-report pihak ketiga, tanpa iklan
- Kode sumber terbuka penuh: audit sendiri kapan pun

## Build

```bash
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:testDebugUnitTest  # unit tests (JVM)
./gradlew :app:lintDebug          # Android lint
```

### CI/CD (GitHub Actions)

| Workflow | Pemicu | Isi |
|---|---|---|
| `CI` | push ke `main`, PR | lint + unit test + build APK debug, cache Gradle penuh |
| `E2E` | push ke `main` | uji instrumented nyata di emulator Android 10; **auto-retry 1×** bila flaky |
| `Release` | push tag `v*` | build release **tertanda + signed**, SHA256SUMS, publikasi GitHub Release otomatis |

Rilis cepat: commit → tunggu CI hijau → `git tag v0.1.1 && git push origin v0.1.1`.
`versionName` diambil dari tag; `versionCode` = jumlah commit.

### Tools auto-fix loop

```bash
GITHUB_TOKEN=... scripts/ci-watch.sh ci       # pantau status CI terbaru
GITHUB_TOKEN=... scripts/ci-logs.sh <run-id>  # unduh log run untuk diagnosa
```

### Signing

Keystore release di-commit **secara sengaja** (`keystore/rockedit-release.jks`, kata sandi di
`gradle.properties`) sebagai bagian dari filosofi FOSS: siapa pun dapat membangun ulang APK
bertanda tangan identik. Ganti ke secret repo bila kelak diperlukan penandatanganan privat.

## Kontribusi

PR diterima dengan senang hati — pastikan CI hijau (lint + unit + build).

## Lisensi

GPL-3.0 — lihat [LICENSE](LICENSE).
Copyright © 2026 SecretArrow.
