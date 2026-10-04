# Rock Edit

![CI](https://github.com/SecretArrow/RockEdit/actions/workflows/ci.yml/badge.svg)
![E2E](https://github.com/SecretArrow/RockEdit/actions/workflows/e2e.yml/badge.svg)

**Rock Edit** adalah editor teks & kode yang cepat, stabil, dan **100% perangkat lunak bebas**
(GPL-3.0) untuk Android — tanpa iklan, tanpa akun, tanpa telemetri. Kredensial Anda
terenkripsi dengan Android Keystore; file hanya keluar perangkat bila Anda sendiri
menghubungkan layanan (Storage Manager / eksekusi daring).

## Fitur (v0.8.0)

- Buka & simpan file teks apa pun lewat Storage Access Framework (tanpa izin storage!)
- **Editor multi-tab** (maks 10): indikator perubahan, tutup per tab / tutup lainnya,
  pemulihan set tab terbuka antar-sesi (muat isi secara lazy), undo/bookmark/encoding per-tab
- **Penyorotan sintaks 48 bahasa** (Kotlin, Java, C/C++, C#, Go, Rust, JS/TS, Python, Ruby,
  PHP, Swift, Shell, SQL, JSON, YAML, XML, HTML, CSS, Lua, Perl, R, ObjC, Dart, Scala,
  Groovy, Haskell, Elixir, Clojure, F#, VB, Assembly, TOML, INI, Makefile, CMake, Batch,
  PowerShell, Vue, GraphQL, Julia, Nim, OCaml, LaTeX, Zig, Protobuf) + nama populer
  tanpa ekstensi (Makefile, Dockerfile, Gemfile) — palet terang/gelap/AMOLED
- **Buka ulang dengan encoding** & **simpan dengan encoding** (UTF-8/16/32, Shift_JIS, GBK,
  Big5, EUC-KR, dan lainnya) di atas deteksi otomatis
- **Buka folder** (peramban SAF: breadcrumb, folder di atas, filter file tersembunyi,
  folder terakhir diingat) — file langsung menjadi tab
- **Storage Manager: FTP, FTPS, SFTP, WebDAV** — file remote dibuka seperti file lokal,
  simpan = unggah balik; kata sandi terenkripsi (Android Keystore, AES-GCM)
- **GitHub & GitLab via Personal Access Token** — telusuri repositori (owner/repo/branch),
  simpan = commit sungguhan
- **Pratinjau HTML & Markdown** (renderer Markdown murni, tema mengikuti aplikasi)
- **Jalankan kode daring** via Piston (28 bahasa) — opt-in, default MATI
- **Cetak** dokumen (PDF A4 via layar cetak Android)
- **Backup / restore data JSON** via SAF: pengaturan, file terbaru, sesi kursor,
  bookmark, dan set tab terbuka
- **Operasi baris** (duplikat/hapus/naik/turun), **bookmark per-baris**, **pemulihan posisi
  kursor+scroll per file**, **cari/ganti** (peka huruf, wrap-around), **lompat ke baris**,
  **statistik**, **sisipkan tanggal/waktu**, ukuran font, auto-save
- Daftar **file terbaru** (maks. 40) + terima teks dari aplikasi lain (share-in)
- Tema terang/gelap/**hitam AMOLED**/ikuti sistem, mode **layar penuh**, bahasa Indonesia + Inggris
- **Bantuan** offline (FAQ), layar **Lisensi** open-source

## Roadmap

Rencana lengkap & status: cloud OAuth (Drive/Dropbox/OneDrive — perlu registrasi klien),
USB OTG & mode root (butuh perangkat fisik), AsciiDoc, lokalisasi 30+ bahasa.

## Privasi

- Tanpa analitik, tanpa crash-report pihak ketiga, tanpa iklan
- Izin INTERNET hanya dipakai untuk koneksi yang Anda konfigurasikan sendiri
  (Storage Manager) dan eksekusi daring yang bersifat opt-in
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
