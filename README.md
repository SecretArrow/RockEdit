# Rock Edit

![CI](https://github.com/SecretArrow/RockEdit/actions/workflows/ci.yml/badge.svg)
![E2E](https://github.com/SecretArrow/RockEdit/actions/workflows/e2e.yml/badge.svg)

**Rock Edit** adalah editor teks & kode yang cepat, stabil, dan **100% perangkat lunak bebas**
(GPL-3.0) untuk Android — tanpa iklan, tanpa akun, tanpa telemetri. Kredensial Anda
terenkripsi dengan Android Keystore; file hanya keluar perangkat bila Anda sendiri
menghubungkan layanan (Storage Manager / eksekusi daring).

## Fitur (v0.21.0)

- **Manajemen file di browser folder (v0.21.0)**: buat file/folder baru dari toolbar, tekan-lama entri untuk Buka / Ganti nama / Hapus (dengan konfirmasi) — validasi nama ketat (karakter terlarang, "." / "..", batas 255 byte) dijelaskan dengan pesan, tanpa penulisan ulang diam-diam
- **Keluar zen mode dengan satu ketukan (v0.20.0)**: tombol melayang khusus tampil hanya selama zen aktif di pojok kanan bawah; tekan Back juga langsung keluar zen (bukan menutup editor)
- **Menu editor terkelompok per prioritas (v0.20.0)**: Berkas → Tab → Sunting & Navigasi → Alat → Tampilan → Data & Encoding → Bagikan & Ekspor → Bantuan, dengan pemisah antar kelompok
- **Dialog About dengan kredit kreator (v0.19.0)**: "Tentang Rock Edit" menampilkan ikon, versi, tagline, kreator **Maragung**, catatan FOSS/GPL-3.0 + privasi, dan tautan kode sumber — tersedia dari menu layar utama dan menu editor
- Buka & simpan file teks apa pun lewat Storage Access Framework (tanpa izin storage!) — Open File / Open Recent / Save / Save As / Save All tersedia langsung di menu editor (v0.18.0)
- **Tata letak sadar bar sistem (v0.18.0)**: tidak ada layar yang menabrak status bar (atas) atau menu navigasi Android — Recent/Back/Home (bawah), termasuk saat keyboard terbuka; ikon bar menyesuaikan tema
- **Editor multi-tab** (maks 10): indikator perubahan, tutup per tab / tutup lainnya,
  pemulihan set tab terbuka antar-sesi (muat isi secara lazy), undo/bookmark/encoding per-tab
- **Penyorotan sintaks 59 bahasa** (Kotlin, Java, C/C++, C#, Go, Rust, JS/TS, Python, Ruby,
  PHP, Swift, Shell, SQL, JSON, YAML, XML, HTML, CSS, Lua, Perl, R, ObjC, Dart, Scala,
  Groovy, Haskell, Elixir, Clojure, F#, VB, Assembly, TOML, INI, Makefile, CMake, Batch,
  PowerShell, Vue, GraphQL, Julia, Nim, OCaml, LaTeX, Zig, Protobuf + **11 bahasa smart
  contract: Solidity, Vyper, Move, Cairo, Clarity, Cadence, Motoko, Aiken, Leo, Fe,
  Michelson**) + nama populer tanpa ekstensi (Makefile, Dockerfile, Gemfile) — palet
  terang/gelap/AMOLED
- **Buka ulang dengan encoding** & **simpan dengan encoding** (UTF-8/16/32, Shift_JIS, GBK,
  Big5, EUC-KR, dan lainnya) di atas deteksi otomatis
- **Cloud & USB (v0.15.0)**: Google Drive / Dropbox / OneDrive via OAuth klien Anda sendiri (token terenkripsi) + akses USB OTG (FAT) — semua lewat Storage Manager
- **Warna pasangan kurung (v0.15.0)**: kurung berwarna per kedalaman bersarang, sadar string/komentar
- **Code Formatter** (v0.10.0 — semua bahasa utama + smart contract, 100% offline):
  JSON (angka verbatim, error berposisi), XML/SVG (proteksi XXE berlapis), CSS
  (string & data-URI aman), **C-family + smart contract** (Solidity, Move, Cairo,
  Cadence, Motoko, Aiken, Leo, Fe, ink!/CosmWasm/Soroban), **Python & Vyper**
  (re-indent aman semantik), Ruby/Lua/Elixir/Julia/LaTeX (struktur kata kunci),
  **Clarity & Michelson** (struktur kurung), YAML (block scalar aman) — fallback
  whitespace untuk bahasa lain; strict/lenient; hasil masuk undo stack,
  gagal = dokumen tidak pernah berubah
- **Alat teks** (Base64/URL/HTML/JSON, MD5/SHA-1/SHA-256, camel/snake/kebab,
  urutkan/dedupe/balik baris), **uji regex interaktif**, dan **pratinjau warna**
  CSS/HTML (hex/rgb/hsl + nama CSS) — semua 100% offline (v0.11.0)
- **Cari di folder** (grep multi-file regex pada pohon SAF, hasil dapat
  diklik untuk membuka file) (v0.11.0)
- **Format seleksi** + **format on save** dengan dukungan **.editorconfig**
  (fail-safe, opt-in) (v0.11.0)
- **Bandingkan dengan file** (diff viewer): mesin LCS deterministik murni Kotlin,
  baris `-`/`+` tersorot warna, ringkasan perubahan, opsi abaikan
  whitespace/huruf besar-kecil, aman untuk file besar (batas + fallback) (v0.12.0)
- **Snippet manager**: cuplikan per bahasa + wildcard `all`, tabstop `$1`/
  `${1:default}`/`$$`/`$0`, validasi ketat, persist lokal terenkripsi-app
  (v0.12.0)
- **Penampil hex** (byte mentah file, batas 1 MiB, salin dump, parser
  dua arah dengan kesalahan berposisi), **pasangkan tanda kurung** (sadar
  string/komentar), **pindai TODO** (TODO/FIXME/HACK/XXX/BUG/NOTE + tag,
  lompat ke baris), **riwayat papan klip** (dedupe, pin, cari, sisip di
  kursor), dan **tampilan belah** (dua panel, muat/simpan panel B via SAF)
  (v0.13.0)
- **Code folding**: lipat/bentangkan blok kurung `{}` (sadar string &
  komentar) atau struktur indentasi (Python dkk.) — lipat semua / di
  kursor, placeholder `⟦⋯ N ⟧` aman-undo, batas jelas, tanpa lipatan
  bertingkat yang merusak dokumen (v0.14.0)
- **Formatter prettier via WebView**: JS/TS/JSX/TSX/HTML/Markdown/GraphQL
  memakai prettier 2.8.8 yang dibundel dan berjalan 100% lokal (offline);
  fallback otomatis ke engine heuristik bila engine JS tak tersedia
  (v0.14.0)
- **Ekspor PDF berwarna**: A4 monospace dengan syntax highlight + nomor
  baris + header halaman, penuh via SAF, batas 100 ribu baris (v0.14.0)
- **Mode zen**: layar penuh imersif tanpa toolbar/tab, font +2sp, Back
  keluar dulu dari zen, tahan rotasi (v0.14.0)
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
