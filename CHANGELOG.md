# Changelog

All notable changes to Rock Edit are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versioning: [SemVer](https://semver.org/).

## [Unreleased]

### Planned
- Fisik-verifikasi USB OTG pada perangkat nyata (unit + browser path sudah tercakup CI)

## [0.17.0] - 2026-10-05

### Added
- **Grammar TextMate kustom** (`core/TmLanguageParser.kt` + `core/CustomGrammarStore.kt`, Settings): impor grammar TextMate berformat JSON (eksporan VS Code) untuk bahasa yang belum didukung highlighter — ekstraksi best-effort kata kunci (alternasi regex `\b(if|else)\b` dan grup `(?:…)`), komentar baris/blok (literal prefix), delimiter string; budget traversal 5.000 pattern (siklus include terminasi deterministik, rantai panjang ditolak informatif), batas 2 juta karakter, tolak JSON rusak/bukan-grammar dengan kode berlokasi; registry kustom diperiksa SEBELUM tabel bawaan (ekstensi case-insensitive); tersimpan persisten (satu extension = satu grammar), dikelola dari Settings (impor via SAF + daftar/hapus). 39 tes parser+store (24+15) + mirror Python 54 vektor.
- **Panel ganda full-app** (`ui/SplitEditorActivity` + `core/SplitSessionState.kt`): kedua panel kini dokumen aktif penuh — panel A punya pembuka SAF sendiri (sebelumnya hanya menerima handoff dari editor), konfirmasi replace saat panel kotor, simpan panel B dengan pilihan charset (encoder strict menolak sebelum menulis — tanpa kehilangan senyap), dan seluruh sesi split bertahan terhadap proses mati/rotasi (snapshot onStop, model "snapshot on stop" terdokumentasi: savedInstanceState > handoff > sesi tersimpan > kosong). 33 tes codec + mirror Python 57 vektor.

### Documentation
- docs/rockedit.md §4.16: keputusan ekstraksi grammar (heuristik 8 langkah), model sesi split, tabel jalur gagal.

## [0.16.0] - 2026-10-05

### Added
- **Statistik kode lanjutan** (`core/CodeStatistics.kt`, murni Kotlin): dialog Statistik kini menampilkan baris kode/kosong/komentar (deteksi komentar sadar string per-bahasa), baris terpanjang & rata-rata panjang, komposisi akhir baris (CRLF/LF/CR), profil indentasi (tab vs spasi + lebar umum), baris berekstensi-spasi, dan penghitung TODO/FIXME/HACK/XXX (hanya di komentar, word-boundary). 58 tes; `lineCount`/`wordCount` dijamin konsisten dengan TextStats lama.
- **Lab charset** (`core/CharsetLab.kt`, menu editor): tabel encoding untuk seluruh charset umum — ukuran byte, jumlah karakter tak terpetakan, status round-trip (eksak vs kehilangan data), plus deteksi BOM (UTF-8/16/32) dan delegasi juniversalchardet. 52 tes.
- **Ekspor gambar PNG** (`core/ImageExportPlanner.kt` + `ui/ImageExporter.kt`, menu editor): cuplikan kode sebagai gambar berwarna dengan palet terang/gelap (mengikuti mode malam sistem), nomor baris, judul dari nama file, wrap 20–200 kolom, token sintaks diwarnai; batas bitmap 8192 px dan 5000 baris dengan pesan informatif; penulisan SAF. 55 tes planner.
- **OAuth loopback browser in-app** (`core/LoopbackRedirectServer.kt` + `ui/InAppAuthActivity.kt`): alternatif alur tempel-kode — server redirect 127.0.0.1 (port 8642 disarankan; Google menerima port loopback variabel, Dropbox/OneDrive perlu URI terdaftar persis), state CSRF acak yang divalidasi constant-time, halaman sukses/kadaluarsa yang tenang, timeout 5 menit, tombol "Buka di aplikasi" di dialog Storage Manager. 29 tes socket nyata. Alur tempel-kode v0.15.0 tetap tersedia.

### Documentation
- docs/CLOUD_USB_BRACKETS.md diperluas: panduan registrasi redirect URI per penyedia + tabel skenario loopback.

## [0.15.0] - 2026-10-05

### Added
- **Bracket pair colorization** (backlog item 4 — selesai): kurung `( ) [ ] { }`
  diberi warna sesuai kedalaman bersarang (palet 4 warna, varian terang/gelap),
  sadar string/char/komentar, toggle di Settings; murni Kotlin
  (`core/BracketPairColorizer.kt`) dengan 15 tes cabang.
- **Cloud OAuth** (backlog Cloud OAuth — selesai): koneksi **Google Drive**,
  **Dropbox**, dan **OneDrive** di Storage Manager (`RemoteType` baru). Tanpa
  client ID bawaan — pengguna mendaftarkan klien OAuth-nya sendiri (privasi,
  FOSS): dialog menghasilkan URL otorisasi, kode ditukar token via endpoint
  resmi (`core/OAuthTokenExchanger.kt`), access/refresh token tersimpan
  **terenkripsi** (`core/OAuthTokenStore.kt`, Android Keystore), refresh
  otomatis saat kedaluwarsa (`core/StoreBackedCloudAuth.kt`). Klien transport
  `GoogleDriveRemoteClient` (resolusi path→id + cache), `DropboxRemoteClient`
  (API v2 + paging `has_more`), `OneDriveRemoteClient` (Graph, ikuti redirect
  pre-authenticated **tanpa** membocorkan Authorization). Semua cabang
  defensif ter-tes (HTTP error, JSON rusak, field hilang, konflik, paging,
  redirect).
- **USB OTG** (backlog USB OTG — selesai): perangkat mass-storage yang
  terpasang tampil di Storage Manager; izin USB → volume FAT dibuka via
  libaums 0.7.3 (`remote/LibAumsVolumeFs.kt`), file dijelajahi lewat browser
  remote dengan sentinel id, buka file langsung ke editor (undo, tab,
  encoding bekerja seperti file lokal). Logika murni di `core/UsbOtgLogic.kt`
  + `remote/UsbOtgRemoteClient.kt` (13 tes).
- Simpan koneksi kini memvalidasi sebelum menutup dialog — input tidak pernah
  hilang pada jalur galat (host/klien kosong, otorisasi gagal).

### Changed
- Koneksi cloud menyimpan `client_id` (polos) dan `client_secret`
  (terenkripsi); JSON lama tetap terbaca (kolom opsional).
- Menambahkan repositori JitPack untuk dependensi libaums.

## [0.14.1] - 2026-10-05

### Changed
- Pipeline rilis kini menerbitkan **Android App Bundle (AAB)** bersama APK di setiap
  GitHub Release — prasyarat unggah ke Google Play — dengan `SHA256SUMS.txt` gabungan
  untuk keduanya (`app-release.apk` + `app-release.aab`).
- Catatan rilis diperbarui: instruksi instalasi APK dan petunjuk pemakaian AAB.

## [0.14.0] - 2026-10-05

### Added
- **Code folding** (backlog item 4 P0): lipat/bentangkan blok dari menu editor
  (`core/CodeFolding.kt`, murni Kotlin). Dua profil struktur: kurung `{}` untuk
  keluarga C/Java/JS/Rust/protobuf dkk. (sadar string, char, multiline string,
  komentar baris & blok) dan **indentasi** untuk Python/Vyper/Ruby/Lua/Elixir/
  Julia/LaTeX; lipat semua, bentangkan semua, dan lipat/bentangkan di kursor.
  Baris tersembunyi digantikan placeholder `⟦⋯ N ⟧` (dengan ordinal `#k` bila
  jumlah baris sama agar kunci pemulihan selalu unik — tidak ada body yang
  tertukar), hasil masuk undo stack, batas 1 juta karakter & 256 lipatan aktif,
  lipatan bertingkat ditolak dengan pesan lokal, placeholder asing terdeteksi
  (PLACEHOLDER_AMBIGUOUS) sehingga dokumen tak pernah rusak diam-diam.
- **Formatter engine prettier via WebView (WASM/JS)** (backlog item 10 P1):
  JavaScript/JSX, TypeScript/TSX, HTML/Vue, Markdown, dan GraphQL kini
  diformat oleh **prettier 2.8.8** yang dibundel lokal (`assets/formatter/`,
  lisensi MIT dicantumkan) dan berjalan dalam WebView headless — tetap 100%
  offline, tanpa jaringan, tanpa telemetri. Ketika engine JS tak bisa jalan,
  formatter otomatis **terdegradasi** ke engine heuristik asli untuk
  JS/TS/GraphQL (jalur baru `ENGINE_UNAVAILABLE` untuk bahasa tanpa fallback).
  Kontrak payload/respone murni Kotlin (`core/WasmFormatterContract.kt`) dengan
  parser JSON manual tahan-escapes dan tes per cabang.
- **Ekspor PDF berwarna** (backlog item 12 P1): menu editor → pilih lokasi via
  SAF → dokumen dirender A4 monospace dengan **syntax highlight** (kata kunci/
  string/komentar/angka memakai palet cetak terang), nomor baris, header nama
  file + nomor halaman. Perencanaan halaman murni Kotlin (`core/
  PdfExportPlanner.kt`): wrap deterministik 88 kolom, pewarnaan lintas-wrap
  dipecah benar, batas 100 ribu baris (ditolak dengan jumlah aktual), guard
  TOKEN_MISMATCH. Renderer tipis (`ui/PdfExporter.kt`): PdfDocument, penulisan
  SAF, galat IO berlapis dengan pesan informatif.
- **Mode zen** (backlog item 13 P2): sembunyikan toolbar + strip tab, perbesar
  font +2sp (editor & gutter selaras), layar penuh imersif; Back keluar dari
  zen lebih dulu; status bertahan rotasi layar (snapshot pra-zen dipulihkan
  persis); mesin status murni Kotlin (`core/ZenModeState.kt`) — idempoten,
  anti-NaN, font di-clamp 8–40sp.

### Changed
- Registry formatter editor kini `withWasm(...)`: prettier mengambil alih
  klaim javascript/typescript/graphql dari `BraceFormatter` (konfigurasi
  bahasanya tetap untuk fallback terdegradasi); format dokumen, format
  seleksi, dan format-on-save memakai registry yang sama.
- Aset prettier 2.8.8 (standalone + 5 parser) dibundel ~2,4 MB; lisensi
  MIT ditambahkan ke layar Lisensi.

## [0.13.0] - 2026-10-05

### Added
- **Penampil hex**: byte mentah file tersimpan dari menu editor. Mesin dump
  murni Kotlin (`core/HexDump.kt`): offset 8 digit hex, kolom ascii
  (non-printable `.`), batas 1 MiB (ditolak dengan pesan), file kosong
  menampilkan "0 byte", tombol **Salin dump**, dan parser dump-tulang-balik
  ke byte dengan kesalahan berposisi (ODD_HEX/INVALID_HEX/EMPTY/TOO_LARGE);
  layar `HexViewerActivity` (RecyclerView monospace, banner kesalahan
  informatif, URI invalid tidak pernah crash).
- **Pasangkan tanda kurung**: pilih dari kurung di kursor ke pasangannya
  (`core/BraceMatcher.kt`, iteratif tanpa rekursi, budget pemindaian 1 MiB).
  Melewati string/char literal (escape backslash berjiritan), komentar baris
  `//` dan blok `/* */`; pasangan lain dilacak di stack agar interleave benar
  (`{ [(] }` tidak salah pasang); kursor tepat setelah kurung tutup juga
  dicoba; tak berpasangan ditolak dengan alasan spesifik, tanpa crash.
- **Pindai TODO**: temukan TODO/FIXME/HACK/XXX/BUG/NOTE (+tag `TODO(p1)`)
  di dokumen aktif (`core/TodoScanner.kt`): word-boundary, case-insensitive
  default, nomor baris/kolom/offset tepat (\n, \r\n, tab), pesan dipotong
  200 karakter, batas 1 juta karakter & 1.000 hasil (ditandai terpotong);
  ketuk hasil untuk lompat ke barisnya.
- **Riwayat papan klip**: tangkap otomatis saat editor aktif (best-effort,
  tanpa izin tambahan — Android 10+ hanya mengizinkan baca saat fokus),
  dedupe berurutan, pin (★), batas 200 entri / 100 ribu karakter per entri,
  eviksi terlama-tak-pin dulu, persist via KeyValueStore (data korup dimuat
  sebagai pustaka kosong fail-safe), pencarian case-insensitive; ketuk untuk
  menyisip di kursor (hormati mode read-only + undo stack), tombol Bersihkan.
- **Tampilan belah (split view)**: dua panel editor monospace berdampingan
  (`SplitEditorActivity`): panel A menerima buffer aktif (proses-lokal,
  batas 1 juta karakter), panel B memuat file via SAF (deteksi charset,
  tolak >1 juta karakter) dan simpan-ke UTF-8, tukar panel, ubah arah belah,
  penanda perubahan (●), dan konfirmasi buang saat keluar; undo native IME
  (keputusan v1, didokumentasikan).

### Changed
- Menu editor: 5 entri alat baru; manifest mendaftarkan `HexViewerActivity`
  dan `SplitEditorActivity`.
- Workflow **Autofix** baru: ktlint (pinned 1.3.1) `--format` dikomit
  otomatis (`[skip ci]`) pada push main, dispatch manual, dan jadwal mingguan.

## [0.12.0] - 2026-10-05

### Added
- **Diff viewer (Bandingkan dengan file…)**: bandingkan dokumen aktif dengan
  file lain (pemilih SAF, dekode otomatis, file biner ditolak via sniff NUL).
  Mesin diff LCS murni Kotlin (`core/DiffEngine.kt`): trim prefix/suffix,
  deterministik (tie-break DELETE), opsi abaikan-whitespace/abaikan-huruf,
  batas 100 ribu baris (ditolak dengan pesan) dan batas matriks 4 juta sel
  (fallback seluruh-blok dengan penanda `fellBack`, memori tetap terkendali).
  Layar `DiffActivity` merender baris `-`/`+` dengan sorotan warna
  terang/gelap, collapse baris tak berubah (konteks 3), ringkasan
  ditambah/dihapus/tak-berubah, batas render 2.000 baris; kedua file cache
  selalu dihapus setelah dibaca.
- **Snippet manager**: cuplikan kode bernama per bahasa + wildcard `all`
  (`core/SnippetStore.kt`, persist JSON via KeyValueStore). Sisipan dengan
  tabstop `$1..$9`, default `${1:teks}`, escape `$$`, posisi kursor akhir
  `$0`; placeholder cacat (`${x:...}`, `${` tanpa tutup, `$` tunggal)
  didegradasi literal sehingga tidak ada teks yang hilang. Validasi ketat:
  nama kosong/kepanjangan (>80), duplikat case-insensitive, isi
  kosong/kepanjangan (>64 ribu karakter); penyimpanan korup dimuat sebagai
  pustaka kosong (fail-safe). Dialog daftar (tap untuk sisip), form buat
  (tetap terbuka saat validasi gagal), dan daftar hapus; frekuensi pakai
  dihitung untuk pengurutan.

## [0.11.0] - 2026-10-04

### Added
- **Paket Utilitas Teks** (menu editor → "Alat teks"): Base64 enkode/dekode
  (alfabet standar + URL-safe, validasi UTF-8 ketat), URL enkode/dekode,
  escape/unescape HTML (entitas bernama + numerik), hash MD5/SHA-1/SHA-256,
  transformasi case (camelCase/snake_case/kebab-case), urutkan/hapus
  duplikat/balik baris (gaya line break file dipertahankan), escape/unescape
  JSON. Bekerja pada seleksi bila ada, selain itu seluruh dokumen; hasil masuk
  undo stack; input tidak valid ditolak dengan posisi kesalahan.
- **Pencarian multi-file (grep folder)** dari peramban folder: telusuri pohon
  SAF (batas 8 kedalaman / 400 file / 1 MB per file), mode regex/literal,
  peka huruf opsional, lewati file biner (sniff NUL), pratinjau baris
  terpotong 200 karakter, batas hasil per-file & total dengan penanda
  terpotong; ketuk hasil membuka file di editor.
- **Uji regex interaktif**: dialog live (debounce 250 ms) menampilkan kecocokan
  + capture group dengan offset, opsi ignore-case/multiline/dotall, batas
  input 1 juta karakter, proteksi zero-length match, timeout 3 detik —
  hanya-baca, dokumen tidak pernah berubah.
- **Pratinjau warna**: ekstrak `#RGB/#RGBA/#RRGGBB/#RRGGBBAA`, `rgb()/rgba()`,
  `hsl()/hsla()` (konversi standar), dan 148 nama warna CSS; swatch + nomor
  baris, ketuk untuk melompat; notasi cacat dilewati dengan penghitung,
  batas 1.000 kemunculan.
- **Format seleksi**: format hanya blok terpilih (mode lenient, tanpa final
  newline tambahan; aturan re-indent seragam dari indentasi baris pertama).
- **Format on save + .editorconfig**: toggle Lanjutan (default MATI); config
  subset `indent_style/indent_size/tab_width/end_of_line/
  trim_trailing_whitespace/insert_final_newline` diparse dari `.editorconfig`
  di folder file (glob exact/\*/\?), digabung last-wins, dipetakan ke
  FormatOptions; sepenuhnya fail-safe — kegagalan config/formatter tidak
  pernah mencegah penyimpanan.
- 5 core murni baru + 70 unit test per-cabang (TextUtilitiesTest,
  RegexTesterTest, FolderGrepTest, ColorExtractorTest, EditorConfigParserTest)
  + 2 E2E baru (TextToolsE2eTest). Verifikasi pra-push via port Python 1:1
  (121 kasus, 121 pass) yang menemukan 3 bug algoritma nyata sebelum CI.

## [0.10.0] - 2026-10-04

### Added
- **Code Formatter diperluas ke semua bahasa utama + smart contract** —
  arsitektur berjenjang (correctness first):
  - **BraceFormatter**: Kotlin, Java, C, C++, C#, Objective-C, Swift, Dart,
    JavaScript, TypeScript, Go, Rust, PHP, Scala, Groovy, Zig, R, PowerShell,
    Protobuf, GraphQL, **Solidity, Move, Cairo, Cadence, Motoko, Aiken, Leo,
    Fe** + alias ink!/CosmWasm/Soroban (→Rust) dan `sol` (→Solidity)
  - **IndentFormatter**: Python & Vyper (re-indent aman semantik: unit
    indentasi dideteksi via GCD lalu di-rescale — struktur tidak pernah
    diturunkan ulang; docstring dipertahankan verbatim) + Ruby, Lua, Elixir,
    Julia, LaTeX (struktur kata kunci `def…end` / `\begin{…}`)
  - **LispFormatter**: Clarity (Stacks), Michelson (Tezos), Clojure,
    ClojureScript, Scheme, Racket, Common Lisp (kedalaman kurung)
  - **YamlFormatter**: trim trailing di luar block scalar; block scalar
    (`|`/`>`) dipertahankan byte-per-byte; tab indentasi ditolak dengan
    nomor baris
- **Penyorotan sintaks +11 bahasa smart contract** (Solidity, Vyper, Move,
  Cairo, Clarity, Cadence, Motoko, Aiken, Leo, Fe, Michelson) dengan
  deteksi ekstensi `.sol .vy .move .cairo .clar .cdc .mo .aiken .leo .fe .tz`
- Opsi **lenient** pada FormatOptions: melanjutkan format best-effort
  ketika validasi struktural gagal (default tetap strict)
- 84 unit test baru: BraceFormatterTest (26), IndentFormatterTest (23),
  LispFormatterTest (11), YamlFormatterTest (9), SmartContractFormatterTest
  (15) — per-cabang, termasuk kasus batas, error berposisi, dan
  idempotensi format(format(x)) == format(x) untuk setiap bahasa kontrak

### Changed
- formatValidated kini menerima id bahasa (formatter tetap stateless dan
  thread-safe; konfigurasi per bahasa lewat parameter, bukan field mutable)
- applyFinalTouches mendapat parameter trimTrailing agar isi string
  multi-baris, komentar blok, dan block scalar tidak pernah diubah

### Verified
- Port Python 1:1 dari seluruh engine (scripts/formatter_verify_v2.py,
  64 kasus = kasus test JUnit yang sama) hijau penuh sebelum push —
  menemukan & memperbaiki 3 bug algoritma sebelum CI

## [0.9.0] - 2026-10-04

### Added
- **Code Formatter** di toolbar editor: JSON (parser ketat RFC 8259 buatan
  sendiri — angka dipertahankan verbatim, error berposisi baris/kolom),
  XML/SVG/plist (DOM re-serialization dengan proteksi XXE berlapis:
  pra-scan DTD/DOCTYPE + parser di-hardening), CSS (state machine: komentar,
  string, url(data:...) tidak pernah rusak; blok tidak seimbang dilaporkan
  beserta barisnya), dan fallback universal normalisasi whitespace untuk
  bahasa apa pun (LF/CR/CRLF, trim trailing, final newline)
- Pipeline formatter defensif: input kosong = "skip" (bukan error), batas
  2 juta karakter, anggaran waktu kooperatif (deadline), batas kedalaman
  nesting, jaring pengaman Throwable — tidak ada jalur eksekusi yang melempar
  exception ke editor
- Hasil format masuk ke undo stack (Format bisa di-undo), read-only
  dihormati, line break style file asli dipertahankan
- 62+ unit test baru (parser, renderer, registry, guard, idempotensi) +
  2 e2e (format JSON via toolbar, JSON rusak tidak pernah mengubah dokumen)

## [0.8.0] - 2026-10-04

### Added
- GitHub & GitLab via Personal Access Token di Storage Manager: repositori
  dimuat sebagai owner/repo/branch, telusuri pohon file, buka file ke editor,
  dan simpan kembali menciptakan commit sungguhan (payload base64, sha untuk
  pembaruan, .gitkeep untuk folder baru)
- Petunjuk dinamis pada dialog koneksi (owner/repo/branch, host GitLab);
  kata sandi (PAT) tetap terenkripsi Android Keystore
- API murni ter-unit-test: parsing /contents GitHub & /repository/tree GitLab,
  decode/encode base64, body PUT/DELETE/commit, navigasi GitPath (branch
  bersarang didukung)

## [0.7.0] - 2026-10-04

### Added
- Pratinjau dokumen: HTML dirender di WebView bawaan; Markdown dikonversi ke
  HTML oleh renderer murni (heading, bold/italic, kode inline & fenced, tautan,
  gambar, list, blockquote, hr) dengan tema terang/gelap mengikuti aplikasi;
  file lain tampil sebagai teks terformat
- Jalankan kode daring (opsional, default MATI): kirim dokumen ke layanan
  publik Piston (emkc.org, tanpa kunci) dan tampilkan stdout/stderr/compile
  output; dialog persetujuan eksplisit + sakelar di Pengaturan › Lanjutan;
  pemetaan 28 bahasa (Python, JS/TS, Java, C/C++, Go, Rust, Kotlin, dst.)
- Layar Bantuan bawaan (offline): FAQ lengkap multi-tab, folder, Storage
  Manager, backup/restore, bahasa, encoding, cetak, dan eksekusi daring

### Changed
- Unit test baru: MarkdownRenderer (7), PistonClient (5)

## [0.6.0] - 2026-10-04

### Added
- Storage Manager: koneksi FTP, FTPS (TLS eksplisit), SFTP (SSH), dan WebDAV —
  tambah/ubah/hapus koneksi, kata sandi disimpan terenkripsi (AES-GCM, kunci di
  Android Keystore, tidak pernah ditulis mentah ke penyimpanan)
- Peramban remote: daftar folder/file (folder di atas, honor sortir & filter
  tersembunyi), navigasi naik, buat folder, hapus; file dibuka ke editor lewat
  RemoteContentProvider sehingga tab, file terbaru, dan pemulihan sesi bekerja
  persis seperti file lokal (simpan kembali langsung ke server)
- Protokol: FTP/FTPS via Apache Commons Net, SFTP via sshj, WebDAV tanpa
  dependensi tambahan (PROPFIND/GET/PUT/MKCOL/DELETE); parser daftar FTP
  (Unix LIST) dan respons PROPFIND WebDAV murni dan ter-unit-test penuh
- Izin INTERNET kini dinyatakan — hanya dipakai untuk koneksi yang
  dikonfigurasi pengguna sendiri; tetap tanpa analitik/telemetri/iklan

### Changed
- Unit test baru: RemotePath (6), RemoteConnectionStore (5, termasuk uji
  kebocoran kata sandi mentah), FtpListParser (4), WebDavParser (3);
  E2E baru: StorageManagerE2eTest (tambah + daftar koneksi, tanpa jaringan)

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
