# ROCK EDIT — Dokumen Riset Produk & Spesifikasi Teknis Lengkap

> **Dokumen internal untuk pengembangan aplikasi "Rock Edit"**
> Tanggal: 5 Oktober 2026 | Versi dokumen: 1.1 (disinkronkan dengan rilis aplikasi v0.13.0)
> Sumber riset utama: halaman produk resmi dan pusat bantuan aplikasi editor teks referensi (rhmsoft.com/qedit.html beserta 25 halaman user manual, changelog 2014–2026, privacy policy, dan halaman lisensi open source-nya).

---

## Daftar Isi

1. [Pendahuluan & Tujuan Dokumen](#1-pendahuluan--tujuan-dokumen)
2. [Ringkasan Produk Referensi](#2-ringkasan-produk-referensi)
3. [Visi & Positioning Rock Edit](#3-visi--positioning-rock-edit)
4. [Spesifikasi Fitur Lengkap](#4-spesifikasi-fitur-lengkap)
5. [Daftar Lengkap Bahasa Sintaks (170+)](#5-daftar-lengkap-bahasa-sintaks-170)
6. [Daftar Lengkap Encoding Karakter](#6-daftar-lengkap-encoding-karakter)
7. [Daftar Bahasa Antarmuka Aplikasi (30+)](#7-daftar-bahasa-antarmuka-aplikasi-30)
8. [Keyboard Shortcut Lengkap](#8-keyboard-shortcut-lengkap)
9. [Spesifikasi Halaman Settings Lengkap](#9-spesifikasi-halaman-settings-lengkap)
10. [Model Monetisasi & Packaging](#10-model-monetisasi--packaging)
11. [Analisis Arsitektur & Tech Stack](#11-analisis-arsitektur--tech-stack)
12. [Strategi Privasi & Kepatuhan Rock Edit](#12-strategi-privasi--kepatuhan-rock-edit)
13. [Batasan Legal — Boleh & Tidak Boleh Disalin](#13-batasan-legal--boleh--tidak-boleh-disalin)
14. [Roadmap Pengembangan Rock Edit](#14-roadmap-pengembangan-rock-edit)
15. [Risiko & Metrik Keberhasilan](#15-risiko--metrik-keberhasilan)
16. [Lampiran A — Timeline Evolusi Produk Referensi](#lampiran-a--timeline-evolusi-produk-referensi)
17. [Lampiran B — FAQ Teknis Penting](#lampiran-b--faq-teknis-penting)
18. [Lampiran C — Sumber Riset](#lampiran-c--sumber-riset)

---

## 1. Pendahuluan & Tujuan Dokumen

Dokumen ini adalah **hasil riset menyeluruh (deep-dive)** terhadap sebuah aplikasi editor teks Android yang sukses dan telah dirilis sejak September 2014, yang akan dijadikan **referensi fungsional 100%** untuk membangun aplikasi baru bernama **Rock Edit**. Seluruh fitur, struktur UX, perilaku, spesifikasi teknis, model monetisasi, dan kebijakan privasi dari produk referensi telah diteliti dari situs resminya (halaman produk + 25 halaman manual pengguna + changelog lengkap dari versi 0.1.0 tahun 2014 hingga versi 1.12.12 tahun 2026 + kebijakan privasi + halaman lisensi open source) dan didokumentasikan di sini sebagai **blueprint pengembangan**.

Tujuan dokumen ini:

- Menjadi **sumber kebenaran tunggal (single source of truth)** untuk seluruh tim pengembang Rock Edit.
- Menjabarkan **spesifikasi fitur sedetail mungkin** sehingga Rock Edit mencapai paritas fungsi (feature parity) 100% dengan produk referensi.
- Menetapkan **batasan legal dan privasi** agar Rock Edit dibangun secara bersih (clean-room), patuh hukum, dan lebih ramah privasi daripada produk referensi.
- Menyusun **roadmap implementasi bertahap** yang realistis.

**Prinsip clean-room yang berlaku untuk seluruh proyek:** Rock Edit dibangun dari nol dengan kode orisinal. Tidak ada kode, aset, ikon, tangkapan layar, teks deskripsi, atau materi berhak cipta apa pun dari produk referensi yang digunakan, didekompilasi, atau disalin. Yang direplikasi hanyalah **konsep dan perilaku fungsional** (yang tidak dilindungi hak cipta), dengan branding, nama, ikon, dan implementasi yang sepenuhnya orisinal. Nama produk referensi **tidak boleh muncul di mana pun** di dalam aplikasi Rock Edit, listing store, maupun materi publiknya.

---

## 2. Ringkasan Produk Referensi

Bagian ini merangkum fakta-fakta produk referensi sebagai konteks riset. Informasi ini **tidak boleh** dipublikasikan ulang di materi Rock Edit — hanya untuk pemahaman internal tim.

| Aspek | Fakta Produk Referensi |
|---|---|
| Kategori | Editor teks & kode untuk Android (notepad + code editor) |
| Tagline resmi | "Fast, stable and full featured text editor for Android. Optimized for both phone and tablet!" |
| Rilis perdana | 18 September 2014 (versi awal 0.1.0, September 2014) |
| Versi terbaru per riset ini | 1.12.12 (15 September 2026), menargetkan Android 16 / API 36 |
| Model bisnis | Freemium: versi gratis dengan iklan (AdMob + Facebook Audience Network) + versi Pro berbayar tanpa iklan dengan fitur identik |
| Posisi pasar | Salah satu editor teks Android paling populer; puluhan juta instalasi kumulatif di Google Play sejak 2014; rating tinggi dan sangat banyak ulasan |
| Dukungan perangkat | Ponsel, tablet, Chromebook, Samsung DeX, multi-window, layar lebar |
| Jumlah bahasa sintaks didukung | 170+ bahasa pemrograman/markup |
| Jumlah bahasa antarmuka | 30+ bahasa terjemahan |
| Platform minimum historis | Android 4.0.3 (versi lama), kini menuntut API modern |

**Kunci kesuksesannya (analisis):**

1. **Performa** — editing mulus pada file 10.000+ baris tanpa lag, feedback real-time; ini differentiator utamanya sejak awal.
2. **Kelengkapan** — mencakup kebutuhan notepad biasa (catatan, log, INI, TXT) sampai kebutuhan programmer (syntax highlight, cloud, Git hosting).
3. **Konektivitas** — Storage Manager yang mendukung FTP/FTPS/SFTP, WebDAV, Google Drive, Dropbox, OneDrive, GitHub, GitLab — hampir tidak ada pesaing yang selengkap ini.
4. **Kebangkitan data (session recovery)** — pekerjaan pengguna tidak pernah hilang; dialog pemulihan sesi bahkan dikunci 10 detik agar tidak ter-cancel tak sengaja (v1.11.1).
5. **Optimasi perangkat luas** — ponsel, tablet, Chromebook, DeX, USB, SD card, perangkat rooted.
6. **Lokalisasi agresif** — 30+ bahasa dengan translator sukarela, termasuk Bahasa Indonesia.
7. **Evolusi konsisten 12 tahun** — changelog panjang menunjukkan perbaikan berkelanjutan, adaptasi tiap versi Android baru (dari 4.0.3 sampai 16), dan menambah fitur secara incremental.

---

## 3. Visi & Positioning Rock Edit

**Rock Edit** adalah editor teks & kode untuk Android yang **memiliki paritas fungsi 100%** dengan produk referensi, tetapi dengan dua pembeda strategis:

1. **Privasi-first (Privacy by Design)** — tanpa akun, tanpa analitik pihak ketiga secara default, tanpa iklan pihak ketiga di versi inti, kredensial koneksi dienkripsi lokal dengan Android Keystore, dan file pengguna **tidak pernah** menyentuh server pengembang. Ini menjawab kelemahan produk referensi yang mengumpulkan data analitik, crash report Firebase, dan iklan AdMob/FAN.
2. **Tanpa menyebut referensi** — seluruh identitas (nama, ikon, warna, copywriting, screenshot) 100% orisinal milik Rock Edit.

### Positioning statement

> "Rock Edit — editor teks & kode cepat untuk Android yang menghormati privasimu: semua file dan kredensial tetap di perangkatmu."

### Target pengguna

- Pengguna umum yang butuh notepad canggih (catatan, file TXT/LOG/INI, konfigurasi).
- Programmer & sysadmin yang butuh editor kode ringan di ponsel/tablet (edit di server via SFTP, edit di cloud, quick-fix kode).
- Pengguna advanced yang butuh edit file sistem (perangkat rooted) dan folder `Android/data` (Android 11+).

### Prinsip desain yang diwarisi dari keberhasilan referensi

| Prinsip | Implementasi di Rock Edit |
|---|---|
| Cepat di file besar | Rendering virtualisasi + engine highlight efisien; target mulus ≥ 10.000 baris |
| Tidak pernah kehilangan kerja | Session recovery default aktif + auto-save dapat dikonfigurasi |
| Semua lokasi file satu app | Storage Manager: lokal, SD, USB, FTP/FTPS/SFTP, WebDAV, Drive, Dropbox, OneDrive, GitHub, GitLab |
| Nyaman di semua perangkat | Layout responsif ponsel/tablet, Chromebook/DeX/multi-window, shortcut keyboard fisik |
| Ringan terasa | Tanpa iklan banner; versi gratis pun bersih |

---

## 4. Spesifikasi Fitur Lengkap

Ini adalah inti dokumen. Setiap sub-bagian merinci perilaku yang harus direplikasi Rock Edit. Penanda *(ref vX.Y.Z)* adalah versi produk referensi saat fitur muncul — berguna sebagai prioritas implementasi.

### 4.1 Editor Inti (Core Editor)

**Identitas aplikasi ganda.** Rock Edit harus berfungsi ganda: (a) notepad standar untuk file teks biasa, dan (b) code editor untuk file pemrograman — cocok untuk penggunaan umum maupun profesional.

**Daftar fitur inti (parity target):**

1. **Notepad yang disempurnakan** — pengganti notepad bawaan dengan banyak improvement.
2. **Code editor + syntax highlight untuk 170+ bahasa** (daftar lengkap di Bagian 5). Highlight diwarnai sesuai tema; terdapat opsi "Tanpa syntax highlight". Bahasa dideteksi otomatis dari ekstensi file (mis. `Main.java` → Java); bisa dipaksa manual via dialog pilihan bahasa (dengan quick-search filter — *ref v1.12.4*).
3. **Performa tinggi real-time** — tanpa lag pada file teks besar (lebih dari 10.000 baris). Arsitekturnya memuat seluruh file ke memori (tanpa pagination) — lihat FAQ teknis di Lampiran B untuk batas praktisnya.
4. **Navigasi multi-tab** — mudah berpindah antar file terbuka; tab disembunyikan saat hanya 1 file terbuka (di ponsel); tab selalu terlihat di tablet landscape; flag "kotor (dirty)" ditampilkan di action bar saat tab tidak terlihat.
5. **Nomor baris** — bisa ditampilkan/disembunyikan.
6. **Undo/Redo tanpa batas** — stack modifikasi tanpa limit; undo via tombol back bersifat opsional (default mati).
7. **Indentasi** — menampilkan, menambah, mengurangi indentasi (untuk baris terpilih/terseleksi); auto-indent baris baru (default aktif, bekerja untuk file kode dan markup seperti HTML/XML); karakter indentasi dapat dipilih: tab atau spasi (mis. 4 spasi).
8. **Seleksi & editing cepat** — lihat detail 4.6.
9. **Scrolling mulus** — vertikal dan horizontal, dengan scrollbar vertikal cepat (fast scroller) dan scrollbar horizontal.
10. **Goto Line** — lompat langsung ke nomor baris tertentu.
11. **Search & Replace cepat** — lihat detail 4.7.
12. **Insert Color** — dialog color picker HSV untuk menyisipkan hex warna (mis. `#80FF00`) ke posisi kursor.
13. **Insert Timestamp** — menyisipkan timestamp dengan beberapa pilihan format *(ref v1.6.1, format diperluas v1.6.4)*.
14. **Deteksi encoding otomatis** — lihat Bagian 6; mendukung file dengan BOM (Byte Order Mark) *(ref v0.8.0)*.
15. **Deteksi line break otomatis** — mendukung LF, CR, CRLF; mendeteksi dari file asli, dapat dipaksa di settings *(ref v1.0.0, auto-detect v1.3.3)*.
16. **Word wrap** — toggle on/off (default off karena memengaruhi performa render); toggle tersedia di toolbar *(ref v0.7.0, toggle toolbar v1.7.0)*.
17. **Preview HTML, CSS, AsciiDoc & Markdown** — lihat detail 4.8.
18. **Font & ukuran** — pilihan font: Normal, Sans Serif, Serif, Monospace, atau font eksternal file TTF/OTF (picker hanya menampilkan folder & file font); ukuran 12sp–56sp; line spacing 0–6sp.
19. **Buka file dari Recent Opened & Recently Added** — lihat 4.5.
20. **Edit file sistem di perangkat rooted** — mode root untuk membaca/menulis file sistem *(ref v0.6.0)*.
21. **Akses file dari FTP/cloud** — lihat 4.4.
22. **Mode Read-Only** — toggle dari menu More; accessory view disembunyikan saat preview/read-only *(ref v0.7.4)*.
23. **Full screen mode** — menyembunyikan status bar untuk ruang editing maksimal *(ref v0.9.0)*.
24. **Statistik** — jumlah kata dan karakter *(ref v1.4.2)*.
25. **Print** — cetak file teks (Android 4.4+); margin cetak 10mm + dukungan syntax warna saat cetak *(ref v1.4.2, margin v1.11.3)*.
26. **Share** — bagikan file utuh ke app lain (Gmail dll.), atau bagikan teks terseleksi *(ref v1.1.6)*.
27. **Terima teks bersama (share-in)** — menerima teks yang dibagikan dari app lain *(ref v1.2.2)*; membuka file dari app pihak ketiga dengan dukungan luas *(ref v0.9.1, v1.5.1)*.
28. **Menu "Translate"** pada selection toolbar untuk menerjemahkan teks terseleksi *(ref v1.10.8)*.
29. **Mini/bottom toolbar** — bar aksi cepat di bawah editor; tombol dapat dikustomisasi (visibilitas + urutan) *(ref v1.6.7, kustomisasi v1.11.2)*; berisi tombol termasuk paste dan kursor *(ref v1.10.1)*.
30. **Themed app icon** — ikon adaptif mengikuti wallpaper Android 13+ *(ref v1.9.5)*.

### 4.2 Struktur UI & Navigasi

**A. Navigation Drawer (kiri)** — panel yang slide dari tepi kiri layar, berisi navigasi utama:

- **Grup "Files":**
  - Internal Storage — buka file dari penyimpanan internal.
  - SD Card — hanya tampil jika perangkat punya SD card eksternal.
  - USB Drive *(ref v1.8.4, via libaums)*.
  - Bookmarks — buka folder yang di-bookmark.
  - Recent Files — daftar file terakhir.
  - Storage Manager — kelola koneksi FTP/cloud/Git.
  - Setiap koneksi FTP/cloud yang dibuat juga muncul sebagai lokasi di drawer.
- **Grup "Others":**
  - Recommend — ajak teman (share app).
  - Settings — pengaturan aplikasi.
  - Help — buka dokumen bantuan.
  - Exit — keluar aplikasi.

  *Catatan Rock Edit (full free): tidak ada item Remove Ads/Upgrade Pro — grup ini hanya
  Recommend/Settings/Help/Exit. Halaman Licenses diletakkan di dalam Settings.*

**B. Folder Drawer (kanan)** — fitur kunci produktivitas *(ref v1.6.0)*: panel yang slide dari tepi kanan layar (swipe kanan→kiri di tepi kanan), menampilkan **file tree dari storage tempat file aktif berada**. Karakteristik:

- Bisa membuka/mengganti file **tanpa meninggalkan pekerjaan** saat ini — sangat membantu mengedit banyak file dalam satu proyek.
- Path segment di atas drawer dapat di-scroll dan diklik untuk navigasi ke folder induk.
- Root folder TIDAK bisa diganti di drawer (mis. dari internal ke SD/cloud); penggantian root hanya di halaman Open File.
- Konten drawer mengikuti tab aktif: file Google Drive aktif → tree menampilkan Drive; file SD card di tab lain → tree menampilkan SD card.
- Bisa dimatikan lewat setting "Folder Drawer".

**C. Toolbar (atas)** — menampilkan nama file, path, dan 3 menu utama:

- **File**: New (file baru kosong), Open (dari device/network), Open (SAF — Storage Access Framework), Save (aktif hanya saat ada modifikasi), Save As (ke lokasi/nama baru, nama harus unik di folder tujuan; navigasi drawer tampil otomatis saat Save As), Close (tutup file aktif).
- **Edit**: Undo, Redo, Select All, Paste, Insert Color, Insert Timestamp, Increase Indent, Decrease Indent.
- **More**: Search, Share, Goto Line, Syntax, Encoding (reload file dengan encoding lain), Visual Styles (ganti warna highlight + background), Statistics, Execute (preview/compile — hanya tampil untuk format yang didukung), Print, Mini Toolbar (toggle), Word Wrap (toggle), Read Only (toggle).

**D. Visual Styles** — kumpulan tema warna sintaks + background; tersedia puluhan gaya visual (referensi menambah 36 gaya di v1.5.2 dan 20 gaya lagi di v1.12.1), dengan palet berbeda untuk tema terang/gelap/hitam.

**E. Sistem tema aplikasi** — Light, Dark, Black (hemat AMOLED), Dark/Light otomatis, Black/Light otomatis *(ref v0.8.0 black, v1.11.5 auto-switch)*; warna aksen per tema; warna seleksi teks mengikuti warna aksen; navigation bar di-tint sesuai tema.

### 4.3 Manajemen File Lokal

**Halaman Open File** (file picker bawaan — bukan sekadar SAF):

- Navigation drawer sendiri berisi semua lokasi (internal, SD, FTP, cloud, bookmark).
- Toolbar berisi: **Sort By** (termasuk sort by last modified time), **Bookmark** (bintang — bookmark folder saat ini), **File Filter** (corong — sembunyikan tipe file tak dikenal; matikan jika file yang dicari tidak muncul).
- **Path view** menampilkan path penuh; setiap segmen path dapat diklik untuk lompat ke folder mana pun di jalur tersebut.
- Menu per file: **Rename**, **Delete** (permanen). Folder tidak punya menu ini.
- **Floating Action Button**: Create Folder, Create File.
- **Fast scroller** di daftar file *(ref v1.7.5)*.
- Dukungan **Storage Access Framework** (Android 4.4+); open dokumen via framework Android dihilangkan karena bermasalah — gunakan storage manager + file open activity bawaan.
- SD card eksternal: read-only di Android 4.4; di Android 5.0+ perlu grant write permission lewat document picker (pilih root folder SD card).
- USB drive didukung di Android 11+.
- **Folder `Android/data`** (lihat 4.9).
- Mode root (perangkat rooted) untuk file sistem.

**Save / Save As:**
- Save hanya aktif saat ada modifikasi.
- Save As: nama file harus unik di folder tujuan; nama dan ekstensi dipisah di field input; encoding bisa diganti saat Save As.
- Save As ke lokasi remote (FTP/cloud/GitHub) via navigation drawer di halaman save.
- Validasi nama file untuk mencegah error penyimpanan *(ref v1.11.6)*.

**Auto Save:** opsi aktifasi + interval 30 detik s.d. 10 menit; dialog auto save tidak meredupkan background *(ref v1.8.1)*.

**Session Resume (wajib):** mengingat file-file sesi terakhir dan membukanya lagi saat aplikasi diluncurkan berikutnya; mengingat modifikasi saat aplikasi dimatikan sistem; mengembalikan posisi seleksi terakhir saat file dibuka ulang *(ref v0.8.3)*; mengembalikan indeks tab aktif terakhir *(ref v1.12.6)*; dialog pemulihan sesi hanya bisa di-cancel setelah 10 detik untuk mencegah kehilangan data *(ref v1.11.1)*.

### 4.4 Storage Manager (Konektivitas — Fitur Kunci)

Halaman untuk mengelola koneksi remote; tombol FAB untuk menambah koneksi. **Tipe koneksi yang didukung (10 jenis):**

| Tipe | Metode autentikasi | Catatan teknis |
|---|---|---|
| FTP | host + user + password | Initial path bisa didefinisikan di field server, mis. `192.168.4.47/data`; dukungan symbolic link folder *(ref v1.5.0)* |
| FTPS | host + user + password | Perbaikan truncation file *(ref v1.11.4)*; enhancement encoding *(ref v1.12.11)* |
| SFTP | password **atau key file** | Key file auth *(ref v1.3.1)*; implementasi diperbarui untuk dukungan server lebih banyak *(ref v1.9.8)*; stabilitas ditingkatkan *(ref v1.12.2)* |
| WebDAV | URL + kredensial | *(ref v1.6.3, via Sardine)* |
| Google Drive | OAuth (grant akses) | Hanya token akses yang disimpan |
| Dropbox | OAuth (short-lived access token) | *(ref v1.0.0, short-lived token v1.8.5)* |
| OneDrive | OAuth | SDK ter-update *(ref v1.4.2, fix sign-in v1.11.4, fix koneksi v1.12.12)* |
| GitHub | Personal Access Token | Akses gists & repositories *(ref v1.7.0)*; performa akses ditingkatkan *(ref v1.11.6)* |
| GitLab | Personal Access Token | Bisa mendefinisikan alamat server GitLab sendiri (self-hosted) *(ref v1.7.5)* |

**Prinsip keamanan yang harus diikuti Rock Edit (dipertahankan dari referensi):** kredensial layanan pihak ketiga disimpan **terenkripsi di perangkat lokal** dan **tidak pernah** dikirim ke server pengembang. Semua transfer file terenkripsi langsung antara perangkat dan server cloud, **tidak melewati** server pengembang. Pengembang tidak menyimpan salinan file pengguna. Rock Edit wajib menaikkan standar: enkripsi via Android Keystore + EncryptedSharedPreferences/DataStore.

Operasi file di storage remote **identik dengan file lokal** (buka, edit, simpan, save as, rename, delete, bookmark).

**Status implementasi Rock Edit (v0.15.0):** FTP/FTPS/SFTP/WebDAV/GitHub/GitLab terkoneksi penuh; **Google Drive/Dropbox/OneDrive via OAuth** dengan prinsip "klien OAuth milik pengguna" (tanpa client ID bawaan — lebih ketat dari referensi), access/refresh token terenkripsi Android Keystore dan refresh otomatis; **USB OTG** (volume FAT pertama via libaums) terbuka dari Storage Manager dengan alur izin USB standar.

### 4.5 Bookmarks & Recent Files

**Bookmarks** *(ref v1.3.0)*:
- Membuat: klik ikon bintang di toolbar halaman file (lokal maupun remote).
- Akses 1: dari navigation drawer main activity — halaman daftar semua bookmark; bisa **rename** dan **delete** bookmark.
- Akses 2: langsung dari navigation drawer di file activity.

**Recent Files:**
- **Recently Opened**: riwayat file yang pernah dibuka; maksimal 40 file, urut waktu terakhir dibuka; bisa **Clear History** sekaligus dari toolbar; bisa hapus satu record dengan swipe kanan→kiri.
- **Recently Added**: menganalisis file teks yang baru dibuat/ditambahkan di perangkat 7 hari terakhir — baik dibuat oleh aplikasi, diunduh browser, maupun disimpan dari Gmail; maksimal 40 file, urut waktu terakhir dibuka.

### 4.6 Seleksi & Editing

- **Selector (offset)**: single-tap posisi teks untuk menaruh kursor; selector bisa di-drag; menghilang otomatis setelah ±4 detik tanpa aksi.
- **Seleksi scope**: double-click memilih satu kata; bisa drag handle untuk memperluas; otomatis masuk selection mode; handle tidak hilang sampai keluar mode.
- **Selection mode**: long-press langsung masuk selection mode *(ref v1.11.9)*; long-press pada area kosong menampilkan context menu, long-press pada teks menampilkan selection menu *(ref v1.11.10)*.
- **Selection toolbar** berisi: Select All, Cut, Copy, Paste (hanya tampil jika clipboard berisi teks), Share, Increase Indent, Decrease Indent; item "Translate" *(ref v1.10.8)*.
- **Floating selection toolbar** pada Android 6.0+ *(ref v1.9.2)* — harus tidak menutupi teks.
- Algoritma seleksi yang baik: mudah memindahkan kursor/seleksi bahkan melintasi halaman.
- Dukungan copy/paste/select dari berbagai input method.

### 4.7 Search & Replace

**Search dialog** (dibuka dari More→Search atau Ctrl+F):

| Kontrol | Perilaku |
|---|---|
| Case Sensitive | ON: huruf besar/kecil dibedakan; OFF: dianggap sama |
| Regular Expression | ON: pattern regex aktif di search & replace (default OFF); sintaks mengikuti `java.util.regex.Pattern` |
| Wrap Around | ON: pencarian berlanjut dari awal dokumen jika tak ditemukan; OFF: berhenti di akhir file |

**Tombol**: Search (temukan & highlight), Replace (temukan, beri kesempatan ganti per kejadian), Replace All (ganti semua).

**Search action mode** (setelah search): Previous, Next, Replace.

**Fitur lanjutan:**
- **Back references**: gunakan `$1`–`$9` di string replacement untuk grup regex. Contoh: replace `(123)(45)` → `$2$1` mengubah semua "12345" menjadi "45123".
- **Search/replace karakter line break `\n`** di dialog pencarian *(ref v1.2.1)*.
- **Riwayat pencarian sebagai suggestion** *(ref v1.3.3 / v1.1.3)*.
- **Escape characters** didukung dengan baik saat mencari *(ref v1.6.4, fix v1.6.5)*.
- Performa search & replace harus dioptimalkan untuk file besar *(ref v1.1.0)*.

### 4.8 Preview & Execute (HTML / Markdown / AsciiDoc / Kompilasi Kode)

**A. Preview HTML:**
- Preview file HTML langsung di web view bawaan — mempercepat workflow pembuatan website.
- Menggunakan **local web server** saat preview *(ref v1.6.4)* (di referensi: NanoHttpd).
- Path relatif hanya render benar untuk file di storage lokal (css, gambar).
- **Console debugging mobile** terintegrasi (di referensi: Eruda) untuk debug halaman web — fitur pembeda yang penting.
- Bisa **evaluasi kode JavaScript** *(ref v1.6.4)*; dukungan JS di preview diperkuat *(ref v1.8.0)*.
- Zoom in/out di preview *(ref v1.6.6)*; tema gelap + tombol toggle di mode preview *(ref v1.7.7)*; buka di browser eksternal *(ref v1.8.0)*.

**B. Preview Markdown:**
- Render dinamis markdown di web view bawaan, cara kerjanya sama seperti preview HTML.
- Keterbatasan sama: path relatif ke resource hanya benar untuk file lokal.
- Engine markdown perlu diperbarui berkala (di referensi: markdown4j → flexmark-java); preview markdown diperkuat di v1.12.12.

**C. Preview AsciiDoc** *(ref v1.12.3, via asciidoctor.js)*.

**D. Compile & Execute (kompiler online):**
- Terintegrasi **kompiler online** yang dapat mengkompilasi & menjalankan kode dalam **30+ bahasa**: Assembly, Bash, Basic, C, C#, C++, Clojure, Elixir, Erlang, F#, Fortran, Go, Haskell, HTML, Java, JavaScript, Kotlin, Lua, Markdown, OCaml, Pascal, Perl, PHP, Prolog, Python, R, Ruby, Rust, Scala, SQL, Swift, Visual Basic.
- Tombol Execute otomatis tampil saat bahasa didukung; bahasa dideteksi dari ekstensi file; file tanpa ekstensi → pilih syntax manual agar tombol muncul.
- Modul Python umum seperti **numpy dan scipy** didukung *(ref v1.9.10)*.
- Versi compiler mengikuti versi stabil terbaru (referensi memakai OpenJDK 21.0.2 dan Python 3.13.2 di v1.11.6).
- UI hasil eksekusi yang baik *(ref v1.10.0)*.
- **Tiga keterbatasan yang harus didokumentasikan di Rock Edit juga**: (1) hanya single-file, tidak bisa compile seluruh proyek; (2) kompiler online tidak bisa menerima input pengguna (stdin interaktif tidak didukung); (3) kode Java harus punya class bernama `Main`.
- Tidak ada code assistant/autocomplete — Rock Edit boleh mempertahankan batasan ini di v1 (paritas), atau menjadikannya pembeda di v2.

> **Catatan implementasi Rock Edit:** karena kompiler adalah layanan pihak ketiga online, Rock Edit wajib (a) menampilkan keterangan jelas bahwa kode dikirim ke layanan eksekusi eksternal, (b) mendesainnya sebagai **opsional** yang bisa dimatikan total (privacy-first), dan (c) mempertimbangkan self-host judge API (mis. Judge0 open source) atau eksekusi lokal terbatas.

### 4.9 Edit Folder Data di Android 11+ (Kasus Khusus)

Sejak Android 11, folder `Android/data/[paket_app]` tidak bisa diakses app pihak ketiga. Perilaku yang harus direplikasi:

- **Android 11 & 12**: saat pengguna membuka folder `/storage/emulated/0/Android/data`, tampilkan dialog grant akses yang mengarah ke folder tersebut → pengguna klik "use this folder". Validasi: jika pengguna memilih folder salah, dialog grant muncul lagi. Setelah grant sukses, akses folder data seperti normal.
- **Android 13+**: OS melarang grant permission ke folder Android/data. Solusinya: **hand-off ke file manager sistem bawaan** (yang punya izin) — pengguna browse dan membuka file dari sana, lalu file diedit oleh Rock Edit. Bekerja di mayoritas perangkat.

### 4.10 Backup & Restore *(ref v1.11.0)*

- Backup: dari halaman Settings → menu Backup; memilih lokasi penyimpanan (default folder Documents).
- Yang dibackup: **settings, koneksi storage manager, dan bookmarks** — disimpan sebagai **file JSON** bernama `RE-backup.json` (di Rock Edit gunakan prefix `RE-`).
- Restore: dari menu Restore, pilih file backup; selesai dalam hitungan detik; bisa lintas perangkat (copy file backup ke device baru).
- Format backup versi gratis dan Pro **identik** dan saling kompatibel.
- Peringatan yang harus ditampilkan: hanya pilih file backup yang dibuat aplikasi; file lain menyebabkan error restore.

### 4.11 Code Formatter *(Rock Edit v0.9.0→v0.10.0 — fitur baru, bukan paritas referensi)*

Fitur pemformatan kode yang berjalan **100% offline di perangkat** (tanpa layanan daring, konsisten dengan prinsip privasi). Akses: ikon **Format** di toolbar editor; bahasa dideteksi otomatis dari ekstensi file; hasil masuk ke undo stack sehingga bisa di-undo seperti edit biasa; dokumen read-only dihormati; line break style file asli (LF/CR/CRLF) dipertahankan.

**v0.10.0 — dukungan struktural untuk semua bahasa utama + smart contract.** Arsitektur berjenjang (correctness first): bahasa yang strukturnya bisa diturunkan secara andal baris-per-baris mendapat re-indentasi struktural; sisanya tetap di fallback whitespace yang tidak mungkin merusak kode.

| Tier | Engine | Bahasa | Catatan |
|---|---|---|---|
| 1 | JsonFormatter | JSON | Parser ketat RFC 8259 buatan sendiri; angka verbatim, error berposisi, minify |
| 1 | XmlFormatter | XML / SVG / plist | DOM + proteksi XXE berlapis; error posisi SAX |
| 1 | CssFormatter | CSS | State machine satu-pass; string & `url(data:...)` verbatim |
| 1 | **BraceFormatter** (v0.10.0) | Kotlin, Java, C, C++, C#, Objective-C, Swift, Dart, JavaScript, TypeScript, Go, Rust, PHP, Scala, Groovy, Zig, R, PowerShell, Protobuf, GraphQL + **smart contract: Solidity, Move, Cairo, Cadence, Motoko, Aiken, Leo, Fe** + alias **ink!, CosmWasm, Soroban** (→Rust), `.sol` (→Solidity) | Struktur `{}`; scanner sadar-string/komentar; raw string (`"""`, backtick) verbatim; preprocessor `#` kolom 0; lifetime Rust aman |
| 1 | **IndentFormatter** (v0.10.0) | **Python, Vyper** (REINDENT), Ruby, Lua, Elixir, Julia, LaTeX (KEYWORD) | Python/Vyper: unit indentasi dideteksi (GCD delta) lalu di-rescale — struktur TIDAK diturunkan ulang sehingga kode yang bekerja tidak mungkin rusak; docstring dipertahankan verbatim. KEYWORD: blok `def…end`, `\begin{…}` dsb. diturunkan dari kata kunci anchor |
| 1 | **LispFormatter** (v0.10.0) | **Clarity (Stacks), Michelson (Tezos)**, Clojure, ClojureScript, Scheme, Racket, Common Lisp | Kedalaman = kurung `(`/`[` tak tertutup; string & komentar blok aman |
| 1 | **YamlFormatter** (v0.10.0) | YAML | Konservatif: trim trailing di luar block scalar (`|`/`>` dipertahankan byte-per-byte — whitespace di sana adalah data); tab di indentasi ditolak dengan nomor baris |
| 2 | WhitespaceFormatter (fallback) | Semua bahasa lain (Markdown, Shell, SQL, Perl, Haskell, VB, Erlang, Fortran, Makefile, ...) | Normalisasi line break, trim trailing whitespace, final newline — aman universal |

**Perilaku defensif v0.10.0 (template defensive programming):**

1. **Pemetaan kasus sebelum coding**: input kosong → `Skipped`; whitespace saja → `Skipped`; >2 juta karakter → `INPUT_TOO_LARGE`; CRLF/CR/LF campur → dinormalisasi LF internal lalu dikembalikan sesuai style file; emoji/non-ASCII dilewati utuh; baris kosong dipertahankan.
2. **Kondisional lengkap**: setiap cabang punya pasangan (strict vs lenient, verbatim vs normal, blank vs berisi); `when/else` eksplisit; guard clause sebelum proses.
3. **Validasi sebelum proses**: `FormatOptions` dan `FormatRequest` memvalidasi invariant di konstruktor (fail fast); `lenient=false` default — input curiga ditolak dengan **kode error + nomor baris** (kurung tidak seimbang, string tak tertutup, tab indentasi, indentasi bukan kelipatan unit, kurung/kata kunci blok tidak seimbang).
4. **Error informatif**: pesan menjelaskan APA dan DI MANA (baris 1-based); `FormatResult` sealed (`Success`/`Failure`/`Skipped`) — pemanggil tidak mungkin melewatkan cabang.
5. **Tidak ada jalur mati**: pipeline guard menangkap `TimeoutSignal`, `DepthSignal` (nesting >512), `StackOverflowError`, `OOM`, dan `Exception` apa pun → `INTERNAL_ERROR`; formatter stateless (thread-safe, konfigurasi bahasa lewat parameter, bukan field mutable).
6. **Null & async**: null dilarang di level tipe (non-null Kotlin + validasi konstruktor); format berjalan di `Dispatchers.Default` dengan deadline kooperatif yang bisa disuntik untuk test deterministik.
7. **Bukti kelengkapan**: port Python 1:1 (`scripts/formatter_verify_v2.py`) menjalankan **64 kasus test** (happy path, string/komentar/char-literal/raw-string, tak seimbang, tak tertutup, lenient, CRLF, preprocessor, lifetime, depth cap, minify, tab, idempotensi, 12 bahasa smart contract) — semua hijau sebelum push; suite JUnit: `BraceFormatterTest`, `IndentFormatterTest`, `LispFormatterTest`, `YamlFormatterTest`, `SmartContractFormatterTest` (per-cabang, termasuk kasus batas & error).
8. **Asumsi eksplisit**: regex JS berisi kutip = keterbatasan terdokumentasi (strict melapor baris, lenient lanjut); indentasi Python adalah struktur — formatter menormalkan lebar, tidak menurunkan ulang struktur; case label indent seperti body switch; HTML mengikuti fallback whitespace (DOM ketat tidak cocok untuk HTML).

Kontrak defensif v0.9.0 tetap dijaga: input kosong = `Skipped` (bukan error); batas 2 juta karakter; anggaran waktu kooperatif (deadline, clock dapat disuntik untuk test); batas kedalaman nesting; registry gagal-cepat pada konfigurasi ganda; **tidak ada jalur eksekusi yang melempar exception ke editor** — kegagalan apa pun tampil sebagai toast terlokalisasi dan dokumen tidak pernah berubah saat gagal.

### 4.12 Backlog Fitur yang Disarankan (hasil evaluasi v0.9.0, diperbarui v0.10.0, v0.11.0 & v0.13.0)

Prioritas P0 (nilai tinggi, biaya rendah, tetap offline):
1. **Paket Utilitas Teks** (✅ v0.11.0) — Base64/URL/HTML-entity encode-decode, hash MD5/SHA-1/SHA-256, transform case (camel/snake/kebab), sort/dedupe/reverse baris, JSON escape/unescape. Murni Kotlin (`core/TextUtilities.kt`), 5 pengurai dengan error berposisi, bekerja pada seleksi atau dokumen penuh, hasil masuk undo stack.
2. **Pencarian multi-file (grep folder)** (✅ v0.11.0) — regex/literal di seluruh pohon SAF folder (batas kedalaman/jumlah/ukuran, lewati biner, penanda terpotong), hasil dapat diklik untuk membuka file; core murni `core/FolderGrep.kt` + `ui/GrepActivity.kt`.
3. **Regex tester interaktif** (✅ v0.11.0) — live match & capture group dengan offset, ignore-case/multiline/dotall, proteksi zero-length match, timeout; hanya-baca (`core/RegexTester.kt`).
4. **Code folding** (✅ v0.14.0) — lipat/bentangkan blok dari menu editor (`core/CodeFolding.kt` murni Kotlin): profil kurung `{}` sadar string/char/multiline-string/komentar untuk keluarga C/Java/JS/Rust/protobuf dkk., profil **indentasi** untuk Python/Vyper/Ruby/Lua/Elixir/Julia/LaTeX; lipat semua, bentangkan semua, lipat/bentangkan di kursor; body diganti placeholder `⟦⋯ N ⟧` (ordinal `#k` saat jumlah baris sama — kunci arsip selalu unik, body tak pernah tertukar); hasil masuk undo stack; batas 1 juta karakter & 256 lipatan aktif; lipatan bertingkat ditolak informatif; placeholder asing terdeteksi (PLACEHOLDER_AMBIGUOUS). Sisa item (warna kurung berpasangan) selesai di v0.15.0 — lihat 4.14.
5. **Preview warna CSS/HTML/XML** (✅ v0.11.0) — ekstraksi `#hex`/`rgb()/rgba()/hsl()/hsla()` + 148 nama warna CSS (`core/ColorExtractor.kt`); dialog swatch, tap melompat ke kemunculan.
6. **Format-on-save + dukungan `.editorconfig`** (✅ v0.11.0) — toggle per aplikasi (default mati); `core/EditorConfigParser.kt` membaca subset `indent_style/indent_size/tab_width/end_of_line/trim_trailing_whitespace/insert_final_newline` dari `.editorconfig` di folder file (glob exact/`*`/`?`, merge last-wins) dan mengganti `FormatOptions` otomatis; seluruh jalur fail-safe.
7. **Format seleksi** (✅ v0.11.0) — format hanya blok terpilih dengan mode lenient, tanpa final newline tambahan; re-indent seragam dari indentasi baris pertama; sinkron dengan undo stack.

Prioritas P1 (diferensiasi):
8. **Diff viewer** (✅ v0.12.0) — bandingkan dokumen aktif dengan file apa pun via pemilih SAF; mesin LCS murni Kotlin (`core/DiffEngine.kt`, deterministik, trim prefix/suffix, batas 100 ribu baris + fallback whole-block saat matriks > 4 juta sel) dengan render `-`/`+` bersorot warna, collapse baris sama (konteks 3), ringkasan added/removed/unchanged; opsi abaikan whitespace/huruf diekspos di engine; file biner ditolak via sniff NUL; cache file selalu dibersihkan. UI pembanding dua panel samping-per-sisi penuh ditunda (layout tablet).
9. **Snippet manager** (✅ v0.12.0) — cuplikan bernama per bahasa + wildcard `all` (`core/SnippetStore.kt`, JSON via KeyValueStore); sisipan dengan tabstop `$1..$9`, default `${1:teks}`, escape `$$`, caret akhir `$0`; placeholder cacat didegradasi literal (tidak ada teks hilang); validasi nama/isi + duplikat case-insensitive; storage korup → pustaka kosong (fail-safe); counter pemakaian mengurutkan daftar; navigasi antar-tabstop ganda ditunda (butuh plumbing editor).
10. **Formatter engine lanjutan via WASM/WebView** (✅ v0.14.0) — **prettier 2.8.8** dibundel lokal (`assets/formatter/`, MIT) dan berjalan dalam WebView headless 100% offline untuk JavaScript/JSX, TypeScript/TSX, HTML/Vue, Markdown, GraphQL (`core/WasmFormatterCatalog.kt` + `WasmFormatterContract.kt` + `WasmCodeFormatter.kt` + `ui/WasmFormatterHost.kt`); payload/respone JSON dibangun-diurai manual (tahan semua escape), batas 1 juta karakter, timeout mengikuti budget; bila engine JS tak bisa jalan, JS/TS/GraphQL **terdegradasi** ke engine heuristik asli, bahasa tanpa fallback dilaporkan `ENGINE_UNAVAILABLE` berlokalisasi. clang-format-style untuk C-family tetap pada engine heuristik v0.10.0 (keputusan: bundel LLVM-WASM terlalu besar untuk aplikasi FOSS hemat ukuran).
11. **Split view / dual editor** (✅ v0.13.0) — dua panel editor monospace berdampingan (`ui/SplitEditorActivity.kt`): panel A menerima buffer aktif (handoff proses-lokal satu-kali-baca, batas 1 juta karakter), panel B memuat file via SAF (deteksi charset, tolak >1 juta karakter) dan simpan-ke UTF-8, tukar panel, ubah arah belah (horizontal/vertikal), penanda dirty (●) per panel, dan konfirmasi buang perubahan saat keluar; undo native IME pada v1 (keputusan terdokumentasi — undo stack kustom butuh plumbing editor). Panel ganda full-app dengan dua dokumen aktif penuh ditunda (layout tablet).
12. **Ekspor kode ke PDF/gambar dengan syntax highlight** (✅ v0.14.0 — bagian PDF) — menu editor → SAF → A4 monospace berwarna (`core/PdfExportPlanner.kt` murni Kotlin: wrap deterministik 88 kolom, pewarnaan lintas-wrap dipecah benar, nomor baris, batas 100 ribu baris dengan jumlah aktual, guard TOKEN_MISMATCH; `ui/PdfExporter.kt`: PdfDocument, palet cetak terang, header nama file + N/halaman, galat IO berlapis). Ekspor ke gambar ditunda (butuh rasterisasi bitmap besar).

Prioritas P2 (polish):
13. **Zen mode** (✅ v0.14.0) — sembunyikan toolbar + strip tab, font +2sp (editor & gutter selaras), layar penuh imersif sticky; Back keluar dari zen lebih dulu; status bertahan rotasi via snapshot idempoten (`core/ZenModeState.kt` murni Kotlin: anti-NaN/Infinity, font di-clamp 8–40sp); keputusan v1: tidak dipersistenkan antar sesi aplikasi.
14. **Charset lab** — pratinjau file dengan encoding berbeda berdampingan + deteksi mojibake + konversi; melengkapi alat encoding v0.3.0.
15. **Grammar TextMate kustom** — impor `.tmLanguage` dari file lokal untuk bahasa yang belum didukung highlighter.
16. **Statistik kode** — LOC per bahasa, distribusi baris kosong/komentar/kode dari file yang dibuka.

### 4.13 Paket Alat Editor v0.13.0 (5 fitur tersisa dari daftar saran — selesai)

Seluruh fitur mengikuti template defensive programming yang sama (pemetaan kasus → kode tanpa jalur mati → tabel kasus di KDoc → tes per cabang):

1. **Penampil hex** (`core/HexDump.kt` + `ui/HexViewerActivity.kt`) — byte mentah file tersimpan: offset 8 digit, kolom ascii (non-printable `.`), grup hex rata; batas 1 MiB (ditolak dengan pesan ukuran aktual), file kosong = "0 byte", **Salin dump** ke clipboard; parser dua arah (dump → byte) dengan kesalahan berposisi `ODD_HEX`/`INVALID_HEX`/`EMPTY`/`TOO_LARGE`, baris non-dump dilewati (toleran); deteksi biner via NUL 8 KB pertama; URI invalid/IO gagal = banner informatif, tidak pernah crash. 34+ tes.
2. **Pasangkan tanda kurung** (`core/BraceMatcher.kt`) — pilih dari kurung di kursor ke pasangannya; iteratif tanpa rekursi; budget pemindaian 1 MiB; sadar string/char-literal (escape backslash berjiritan) dan komentar `//`/`/* */`; pasangan lain dilacak stack agar interleave benar (`{ [(] }` tidak salah pasang); kursor tepat setelah kurung tutup dicoba otomatis; indeks di luar batas = `Unmatched` (tidak pernah throw — dipanggil pada setiap pergerakan kursor); tak berpasangan ditolak dengan alasan spesifik. 25+ tes.
3. **Pindai TODO** (`core/TodoScanner.kt`) — TODO/FIXME/HACK/XXX/BUG/NOTE (set kustom bisa disuntik), word-boundary, tag `TODO(p1)` (maks 20 char, cacat = dibiarkan di pesan), case-insensitive default, baris/kolom/offset tepat untuk `\n`/`\r\n`/tab tunggal, pesan dipotong 200 char + ellipsis, batas 1 juta karakter (`INPUT_TOO_LARGE`) dan 1.000 hasil (flag `truncated`, berhenti awal); asumsi terdokumentasi: pemindaian seluruh teks tanpa kesadaran komentar (gaya grep) — penanda di URL bisa match. 40 tes.
4. **Riwayat papan klip** (`core/ClipboardHistoryStore.kt`) — tangkap otomatis saat editor fokus (best-effort: Android 10+ hanya mengizinkan baca saat fokus, kegagalan non-actionable diabaikan terdokumentasi), dedupe berurutan (naik ke atas, pin dipertahankan), pin ★ dilindungi eviksi (urutan: terlama-tak-pin dulu; fallback terlama keseluruhan agar add selalu sukses untuk teks valid), batas entri di-clamp 5..200 dan 100 ribu char/entri (ditolak dengan pesan ukuran aktual), persist JSON via KeyValueStore — data korup = pustaka kosong fail-safe; list: pin dulu lalu terbaru, pencarian contains case-insensitive; UI: ketuk = sisip di kursor (hormati read-only + undo stack), tombol Bersihkan. 22 tes.
5. **Tampilan belah** — lihat item 11 di bagian 4.12.

Kontrak lintas fitur: tanpa izin baru, tanpa jaringan, seluruh state persisten lewat KeyValueStore, dan seluruh pesan galat terlokalisasi (values-in). Workflow **Autofix** (ktlint 1.3.1 `--format`, komit `[skip ci]` pada push main/dispatch/mingguan) menutup lingkaran CI/CD.

### 4.14 Paket Lipatan, Prettier-Lokal, PDF & Zen v0.14.0 (backlog items 4, 10, 12, 13 — selesai)

Empat fitur terakhir dari backlog §4.12, dengan templat defensive programming yang sama (pemetaan kasus → kode tanpa jalur mati → tabel kasus di KDoc → tes per cabang → verifikasi Python pra-CI):

1. **Code folding** (`core/CodeFolding.kt`) — dua profil struktur (kurung sadar string/komentar; indentasi), tiga aksi menu (lipat semua / bentangkan semua / toggle di kursor). Placeholder `⟦⋯ N ⟧` menggantikan baris isi; ordinal `#k` otomatis saat dua lipatan aktif memiliki jumlah baris sama sehingga kunci arsip pemulihan **selalu unik** (temuan verifikasi port: pasangan LIFO dapat menukar body dua lipatan kembar — korupsi diam-diam; kini mustahil). Batas 1 juta karakter / 256 lipatan aktif; lipatan bertingkat dan placeholder asing ditolak dengan kode berlokalisasi; REGION_GONE dibersihkan fail-safe. 50+ tes.
2. **Prettier 2.8.8 via WebView** (`core/WasmFormatterCatalog.kt`, `core/WasmFormatterContract.kt`, `core/WasmCodeFormatter.kt`, `ui/WasmFormatterHost.kt`, `assets/formatter/host.html`) — engine penuh untuk JS/TS/JSX/TSX/HTML/Vue/Markdown/GraphQL, 100% offline; envelope JSON manual tahan-escapes (diuji termasuk `</script>` dan \uXXXX); host WebView tunggal dengan latch+timeout, rekreasi otomatis bila crash; degradasi ke engine heuristik untuk JS/TS/GraphQL. E2E nyata di emulator (`WasmFormatterE2eTest`): format JS dari toolbar menghasilkan output prettier persis.
3. **Ekspor PDF berwarna** (`core/PdfExportPlanner.kt`, `ui/PdfExporter.kt`) — preprocess kanonik (CRLF→LF, tab→4 spasi) SEBELUM tokenisasi agar offset token konsisten; wrap 88 kolom dengan pemecahan segmen lintas-baris-cetak yang benar; palet cetak terang tetap; batas 100.000 baris + TOKEN_MISMATCH guard; penulisan SAF dengan penanganan galat berlapis. 45 tes.
4. **Mode zen** (`core/ZenModeState.kt`) — state machine immutable: enter idempoten (rotasi tidak menimpa snapshot asli), exit memulihkan persis, clamp font 8–40sp, NaN/Infinity → default 14sp; integrasi EditorActivity: toolbar+tab disembunyikan, editor & gutter diskalakan bersama, Back keluar zen dulu. 23 tes.

Kontrak tetap: tanpa izin baru, tanpa jaringan (prettier dibundel; WebView memuat aset lokal saja), seluruh pesan galat terlokalisasi (strings_v014*.xml EN/ID), lisensi prettier MIT dicantumkan di layar Lisensi. Total unit test kini 800+; sisa backlog: bracket pair colorization, ekspor gambar, charset lab, TextMate kustom, statistik kode, panel ganda tablet.

### 4.14 Bracket Colors, Cloud OAuth & USB OTG (v0.15.0 — selesai)

1. **Bracket pair colorization** (`core/BracketPairColorizer.kt`, murni Kotlin) — kurung `( ) [ ] { }` berwarna per kedalaman bersarang (palet 4 warna, terang/gelap/AMOLED), sadar string/char/komentar baris & blok; string tak tertutup di-reset per baris (fail-safe), kurung tak berpasangan di-clamp agar tak pernah negatif; toggle di Settings (default nyala); dijalankan dalam pipeline re-highlight dengan batas 150 ribu karakter. 15 tes cabang.
2. **Cloud OAuth (Google Drive / Dropbox / OneDrive)** — tanpa client ID bawaan (privasi/FOSS): pengguna mendaftarkan klien sendiri, dialog menyediakan generator URL otorisasi + tempel kode; penukaran token via endpoint resmi (`core/OAuthTokenExchanger.kt`, semua kegagalan HTTP/JSON/jaringan berkode informatif), penyimpanan access/refresh token **terenkripsi** (`core/OAuthTokenStore.kt`), refresh otomatis dengan pelestarian refresh token lama (`core/StoreBackedCloudAuth.kt`). Transport: `GoogleDriveRemoteClient` (resolusi path→id ber-cache, multipart upload, PATCH saat file ada), `DropboxRemoteClient` (API v2, paging `has_more` dibatasi, konflik mkdir no-op-safe), `OneDriveRemoteClient` (Graph, redirect pre-authenticated diikuti **tanpa** Authorization). Hapus koneksi = hapus token. Bukti lengkap: `docs/CLOUD_USB_BRACKETS.md`.
3. **USB OTG** — perangkat mass-storage terdeteksi (device/interface class 0x08), izin USB standar, volume FAT pertama dibuka via libaums 0.7.3 (`remote/LibAumsVolumeFs.kt`); file dijelajahi lewat browser remote (sentinel id) dan dibuka ke editor dengan undo/tab/encoding penuh; logika murni diuji tanpa hardware (`core/UsbOtgLogic.kt` + `remote/UsbOtgRemoteClient.kt`, 13 tes); verifikasi perangkat fisik tetap langkah manual pra-rilis.

Semua fitur mengikuti template defensive programming (pemetaan kasus → kode tanpa jalur mati → tabel kasus → tes per cabang → asumsi eksplisit) dan tanpa izin baru yang berbahaya (USB memakai alur izin runtime bawaan Android).

### 4.15 Statistik Kode, Lab Charset, Ekspor Gambar & OAuth Loopback (v0.16.0 — selesai)

Empat fitur backlog berikutnya, dengan templat defensive programming yang sama:

1. **Statistik kode lanjutan** (`core/CodeStatistics.kt`, murni Kotlin) — dialog Statistik ditingkatkan: baris kode/kosong/komentar (deteksi komentar sadar string dengan profil per-bahasa melalui `CommentProfiles.forFileName`), baris terpanjang & rata-rata, komposisi akhir baris (CRLF/LF/CR), profil indentasi (tab vs spasi + lebar umum {1,2,3,4,6,8}), baris berekstensi-spasi, penghitung TODO/FIXME/HACK/XXX (hanya di wilayah komentar, word-boundary). Keputusan terdokumentasi: string tak tertutup dikonfinasi ke barisnya sendiri (apostrof prosa tak lagi melumpuhkan deteksi komentar), triple-string Python spanning baris, komentar blok tak tertutup memakan sisa dokumen (fail-safe), `lineCount`/`wordCount` konsisten penuh dengan TextStats. 58 tes.
2. **Lab charset** (`core/CharsetLab.kt`, murni JVM, menu editor) — tabel per charset umum: ukuran byte, jumlah karakter tak terpetakan (encoder REPORT manual, dihitung per sekuens), status round-trip eksak/kehilangan data, hex 32 byte pertama; deteksi BOM UTF-8/16LE/16BE/32LE/32BE (urutan prefiks yang tumpang tindih ditangani) + delegasi juniversalchardet; nama charset tidak dikenal dilaporkan informatif (tidak pernah melempar). Asumsi: lab menganalisis TEKS saat ini (editor tidak menyimpan byte mentah asli). 52 tes.
3. **Ekspor gambar PNG** (`core/ImageExportPlanner.kt` + `ui/ImageExporter.kt`, menu editor) — cuplikan kode sebagai gambar berwarna: palet terang/gelap mengikuti mode malam sistem, nomor baris (nomor sumber diulang pada baris wrap — keputusan v1), judul dari nama file, wrap 20–200 kolom (clamp bukan gagal), warna per token sintaks; arsitektur planner-pure/renderer sepert PDF export (duplikasi algoritma wrap disengaja agar modul independen); batas bitmap 8192 px & 5000 baris dengan pesan informatif; penulisan SAF dengan jalur gagal berlapis. 55 tes planner.
4. **OAuth loopback browser in-app** (`core/LoopbackRedirectServer.kt` + `ui/InAppAuthActivity.kt`) — alternatif alur tempel-kode: server redirect di `127.0.0.1:8642` (satu-satu: callback pertama menang, permintaan berikutnya halaman "sudah terpakai"; non-GET 405; request rusak 400 tanpa callback), state CSRF 128-bit SecureRandom divalidasi constant-time, WebView in-app dengan timeout 5 menit, tombol "Buka di aplikasi" di dialog Storage Manager; Google menerima port loopback variabel, Dropbox/OneDrive butuh URI terdaftar persis (panduan di `docs/CLOUD_USB_BRACKETS.md`); penukaran kode memakai redirect yang sama persis dengan otorisasi. Alur tempel-kode tetap utuh. 29 tes socket 127.0.0.1 nyata.

Total unit test kini 1.060+; sisa backlog: grammar TextMate kustom, panel ganda full-app tablet; verifikasi fisik USB OTG tetap langkah manual pra-rilis.

### 4.16 Grammar TextMate Kustom & Panel Ganda Full-App (v0.17.0 — selesai)

Dua item backlog terakhir dari §4.12/§4.14, templat defensive programming yang sama:

1. **Grammar TextMate kustom** (`core/TmLanguageParser.kt` + `core/CustomGrammarStore.kt`) — impor `.tmLanguage` berformat JSON: heuristik ekstraksi 8 langkah terdokumentasi (validitas scopeName+patterns; traversal iteratif dengan budget 5.000 pattern — siklus `#include` terminasi via expanded-set, rantai panjang → PATTERN_BUDGET_EXCEEDED informatif; kata kunci dari alternasi regex literal — escape huruf/digit di-drop, grup pembungkus `\b(?:…)\b` dibedah; komentar baris/blok dari literal prefix dengan batas 3/6 karakter tanda baca; delimiter string dari `string.quoted` begin dengan fallback berlapis; normalisasi ekstensi `.PY`/`*.py` → `py`; caseInsensitive tetap false v1; include eksternal diabaikan TAPI pattern miliknya sendiri tetap dijalan). Registry: `SyntaxRegistry.registerCustomLanguage/clearCustomLanguages/customLanguages()` — bahasa kustom diperiksa SEBELUM tabel bawaan. Store: satu kunci KeyValueStore (`custom_grammars_v1`), JSON korup → kosong fail-safe, satu extension = satu grammar (impor baru menggantikan pemilik lama), `loadIntoRegistry()` melewati entri gagal dengan laporan. UI Settings: impor via SAF (baca dibatasi budget+1 byte), dialog nama+ekstensi, daftar/hapus. 24+15 tes; mirror Python 54 vektor menemukan 2 bug ekspektasi hand-computed sebelum CI.
2. **Panel ganda full-app** (`ui/SplitEditorActivity` + `core/SplitSessionState.kt`) — panel A kini membuka file sendiri via SAF (sebelumnya hanya handoff proses-lokal), konfirmasi replace saat kotor, simpan panel B dengan pilihan charset dari COMMON_CHARSETS (encoder strict menolak teks tak terpetakan SEBELUM menulis — tanpa kehilangan senyap); sesi bertahan proses-mat via `SplitSessionCodec` (snapshot onStop; urutan pemulihan terdokumentasi: savedInstanceState > handoff > sesi tersimpan > kosong; gabungan teks > 2 juta karakter → persist dilewati dengan toast; charset tak dikenal → UTF-8; teks > 1 juta karakter per panel → pemotongan fail-safe terdokumentasi). 33 tes codec; mirror Python 57 vektor.

### 4.17 Sadar Bar Sistem + Operasi Berkas Editor (v0.18.0 — selesai)

**Masalah**: aplikasi menarget SDK 36; Android 15+ (API 35+) memaksa edge-to-edge sehingga konten semua activity menggambar di bawah status bar (atas) dan navigation bar / area gestur Recent-Back-Home (bawah). Sebelum v0.18.0 hanya zen mode yang menyentuh insets.

**Solusi** (`ui/SystemBars.kt`, dipasang di seluruh 14 activity):

| Skenario | Penanganan |
|---|---|
| API 29+ normal | `enableEdgeToEdge()` + `setDecorFitsSystemWindows(false)`; padding root = union per sisi dari `systemBars + displayCutout + ime` |
| API 26-28 | DecorView tetap memfit konten; listener menerima insets yang sudah dikonsumsi (nol) → tanpa padding ganda |
| Bar disembunyikan (zen/full screen) | Insets melapor 0 → padding jatuh ke baseline; keluar mode memulihkan padding lewat dispatch berikutnya |
| Keyboard terbuka | `ime()` digabung ke tipe insets → padding bawah mengikuti keyboard (perilaku adjustResize yang wajib ditangani sendiri sejak Android 11 saat tidak memfit) |
| Cutout landscape | `displayCutout()` digabung → padding kiri/kanan menjauh dari notch |
| Dispatch berulang | Padding selalu baseline (padding root saat install) + insets saat ini → idempoten, tidak terakumulasi |
| Root ber-padding sendiri (InAppAuthActivity) | Baseline menangkap padding 16 dp yang ada, insets ditambahkan di atasnya |
| Ikon bar | `isAppearanceLightStatusBars/NavigationBars` mengikuti mode malam efektif (gelap di tema terang, terang di gelap/AMOLED) |

Tes: `InsetsE2eTest` (emulator API 30) memastikan paddingTop > 0 dan paddingBottom > 0 untuk MainActivity dan EditorActivity — regresi tabrakan bar terdeteksi CI.

**Operasi berkas editor (v0.18.0)**: menu editor kini memiliki **Open File** (SAF `OpenDocument` → `ACTION_EDIT` intent → `addTabFromIntent`; memakai ulang seluruh cabang defensif: dedup URI, batas tab, persistable permission, pemuatan lazy; picker dibatalkan → no-op), **Open Recent** (dialog dari `RecentFilesStore`; baca store gagal → kosong + toast; entri basi → jalur muat normal dengan error terlokalisasi; indeks di luar jangkauan → diabaikan via `getOrNull`), **Save All** (`dirtyFileTabs()`; kosong → toast "tidak ada yang perlu disimpan"; `writeTo` defensif per tab sehingga satu kegagalan I/O tidak menghentikan tab lain). Save/Save As yang sudah ada sebelumnya tetap.

### 4.18 Dialog About dengan Kredit Kreator (v0.19.0 — selesai)

**Permintaan pengguna**: tambahkan About; kreator aplikasi adalah **Maragung**.

**Solusi** — dialog About ditingkatkan dari teks sederhana menjadi komponen kelas satu:

| Skenario | Penanganan |
|---|---|
| Versi null/blank | `AboutInfo.displayVersion()` → placeholder "unknown" (dialog tidak pernah menampilkan "null") |
| Versi dengan spasi/marker `v` | Trim + strip satu marker `v`/`V` terdepan (`vv0.19.0` → `v0.19.0`; tanpa loop) |
| Versi ekstrem panjang | Dipotong ke 32 karakter — layout satu baris aman |
| Label terlokalisasi blank | Fallback label EN ("Created by") — kredit tetap tampil |
| Inflasi layout gagal | Dialog teks minimal (nama + kredit) tetap muncul — About tidak pernah gagal senyap |
| Tombol Lisensi gagal membuka activity | Ditangkap; dialog tetap terbuka (Lisensi juga terjangkau dari menu utama) |
| Context finishing/destroyed | `show()` keluar tanpa melakukan apa pun (anti window-leak) |

**Penggunaan ganda**: satu komponen `ui/AboutDialog.kt` dipakai menu layar utama **dan** menu editor (entri `action_about` baru). Logika murni-JVM `core/AboutInfo.kt` diuji penuh (18 @Test, semua cabang); `AboutE2eTest` membuka overflow menu → About → memverifikasi kredit "Maragung" tampil (nama kreator tidak dilokalkan — asersi aman lintas perangkat).

---

Total unit test kini 1.150+. Backlog roadmap §4.12/§4.14/§4.15/§4.16 LENGKAP; tersisa hanya verifikasi fisik USB OTG (manual pra-rilis, butuh perangkat keras).

---

## 5. Daftar Lengkap Bahasa Sintaks (170+)

Rock Edit harus mendukung highlight untuk seluruh daftar berikut (dikelompokkan agar mudah dicek; urutan alfabetis mengikuti referensi):

1C, ABNF, ARM Assembler, ASP, AVR Assembler, Access Logs, ActionScript, Ada, AngelScript, Apache, AppleScript, Arcade, Arduino (C++ w/Arduino libs), AsciiDoc, AspectJ, AutoHotkey, AutoIt, Awk, BASIC, BNF, Bash, Brainfuck, C, C#, C++, C/AL, CMake, COBOL, CSP, CSS, Cache Object Script, Cap'n Proto, Clojure, CoffeeScript, Coq, Crmsh, Crystal, D, DNS Zone File, DOS, DTS (Device Tree), Dart, Delphi, Diff, Django, Dockerfile, Dust, EBNF, Elixir, Elm, Embedded Ruby (ERB), Clean, Flix, LLVM IR, WebAssembly (5 bahasa tambahan ref v1.12.10), Erlang, Excel, F#, FIX, Fortran, G-Code, GAUSS, Gams, Gherkin, Go, Golo, Gradle, GraphQL, Groovy, HTML, HTTP, Haml, Handlebars, Haskell, Haxe, Hy, IRPF90, Inform7, JSON, JSP, Java, JavaScript, Julia, Julia REPL, Kotlin, LDIF, LaTeX, Lasso, Leaf, Less, Lisp, LiveCode Server, LiveScript, Logcat, Lua, MIPS Assembler, Makefile, Markdown, Mathematica, Matlab, Maxima, Maya Embedded Language, Mercury, Mizar, Mojolicious, Monkey, Moonscript, N1QL, NSIS, Nginx, Nim, Nix, OCaml, Objective C, OpenGL Shading Language, OpenSCAD, Oracle Rules Language, Oxygene, PF, PHP, Parser3, Perl, Pony, PostgreSQL & PL/pgSQL, PowerShell, Processing, Prolog, Properties, Protocol Buffers, Puppet, Python, Python Profiler Results, Python REPL, Q, QML, R, ReasonML, RenderMan RIB, RenderMan RSL, Roboconf, Ruby, Rust, SAS, SCSS, SML, SQL, STEP Part 21, Scala, Scheme, Scilab, Shell, Smali, Smalltalk, Stan, Stata, Stylus, SubUnit, Swift, TOML/INI, TP, Tcl, Test Anything Protocol, Thrift, Twig, TypeScript, VB.Net, VBScript, VHDL, Vala, Verilog, Vim Script, X++, XL, XML, XQuery, YAML, Zephir, dsconfig, x86 Assembly.

**Fitur pendukung daftar bahasa:**

- Deteksi otomatis dari ekstensi file.
- Dialog pilih bahasa manual dengan **quick-search filter**.
- Opsi "Tanpa syntax highlight" (plain text).
- **File Association** di settings: memetakan ekstensi ↔ bahasa; ekstensi bisa dipetakan ulang ke bahasa lain; ekstensi baru bisa ditambahkan; ekstensi kosong = default syntax untuk semua file tanpa ekstensi.
- Puluhan **visual styles** (kombinasi warna highlight + background), berbeda untuk tema terang/gelap.

> **Rekomendasi implementasi Rock Edit:** gunakan **highlight.js** sebagai engine highlight (dipilih referensi pada v1.12.0 — upgrade performa 100–400% dibanding engine lama mereka) dijalankan di dalam WebView/JS engine headless, ATAU library **sora-editor** (open source, engine highlight native Android berbasis editor, mendukung TextMate grammar) bila ingin pendekatan 100% native. Keduanya legal dan bebas lisensi.

---

## 6. Daftar Lengkap Encoding Karakter

**Auto-detect charset (default)** dari isi file, dengan daftar encoding yang didukung:

UTF-8, UTF-16BE, UTF-16LE, UTF-32BE, UTF-32LE, Shift_JIS, ISO-2022-JP, ISO-2022-CN, ISO-2022-KR, GB18030, Big5, EUC-JP, EUC-KR, ISO-8859-1, ISO-8859-2, ISO-8859-5, ISO-8859-6, ISO-8859-7, ISO-8859-8, ISO-8859-9, windows-1250, windows-1251, windows-1252, windows-1253, windows-1254, windows-1255, windows-1256, KOI8-R, IBM866 (untuk Rusia, ref v0.8.0).

**Perilaku encoding yang harus direplikasi:**

1. Default: auto-deteksi dari konten file (gunakan library deteksi charset seperti juniversalchardet).
2. Perubahan encoding manual: via More→Encoding; **hanya diizinkan saat file belum dimodifikasi**; setelah diganti, konten file di-reload dengan encoding baru.
3. **Default Encoding** di settings: bisa memaksa encoding tertentu (mis. selalu UTF-8) atau tetap Auto Detect.
4. Encoding bisa diubah saat **Save As**.
5. Dukungan file dengan **BOM** (byte order mark).
6. Deteksi karakter line break dari file asli (LF/CR/CRLF).
7. Troubleshooting umum yang harus didokumentasikan di help Rock Edit: karakter tampil kacau (garbled) → kemungkinan deteksi salah → pilih encoding manual sesuai bahasa (contoh: ISO-8859-7 untuk Yunani).

---

## 7. Daftar Bahasa Antarmuka Aplikasi (30+)

Rock Edit harus dilokalisasi minimal ke daftar berikut (referensi mendukung 30+):

English, Arabic, Belarusian, Bengali, Bulgarian, Czech, Dutch, Estonian, German, Greek, French, Hindi, Hungarian, Italian, Lithuanian, Japanese, Korean, **Bahasa Indonesia**, Persian, Polish, Romanian, Russian, Spanish, Thai, Turkish, Slovak, Ukrainian, Vietnamese, European Portuguese, Brazilian Portuguese, Simplified Chinese, Traditional Chinese, Uzbek (ref v1.10.6), Norwegian (ref v1.4.0).

**Perilaku bahasa:**

- Default mengikuti bahasa sistem Android; bisa diganti per-aplikasi dari settings (app-level language override, ref v1.4.7) — implementasi modern: `AppCompatDelegate.setApplicationLocales()` / per-app language Android 13.
- Sebagian besar teks berubah dinamis; beberapa perangkat butuh restart aplikasi.
- Model **translator sukarela** (community translation) terbukti efektif bagi referensi — Rock Edit bisa pakai platform seperti Crowdin/Weblate untuk mengumpulkan kontributor.

---

## 8. Keyboard Shortcut Lengkap

Untuk keyboard fisik (Chromebook, DeX, keyboard Bluetooth, dll.). Semua harus diimplementasikan di Rock Edit:

### File
| Shortcut | Fungsi |
|---|---|
| Ctrl+S | Simpan file saat ini |
| Ctrl+Z | Undo modifikasi terakhir |
| Ctrl+Y | Redo |
| Ctrl+F | Cari di file saat ini |
| Ctrl+A | Select all |
| Ctrl+W | Tutup tab file saat ini |

### Kursor
| Shortcut | Fungsi |
|---|---|
| Page Up / Page Down | Halaman sebelumnya / berikutnya |
| Home / Alt+Left | Awal baris |
| End / Alt+Right | Akhir baris |
| Ctrl+Left / Ctrl+Right | Lompat per kata (mundur/maju) |
| Ctrl+Home / Alt+Up | Awal file |
| Ctrl+End / Alt+Down | Akhir file |

### Seleksi
| Shortcut | Fungsi |
|---|---|
| Shift+Left/Right | Perluas seleksi per karakter |
| Shift+Up/Down | Perluas seleksi per baris |
| Shift+PageUp/PageDown | Perluas seleksi per halaman |
| Shift+Ctrl+Left/Right | Perluas seleksi per kata |
| Shift+Alt+Left/Right | Perluas seleksi ke awal/akhir baris |
| Shift+Home | Seleksi dari awal baris ke kursor |
| Shift+End | Seleksi dari kursor ke akhir baris |
| Shift+Ctrl+Home/End | Seleksi ke awal/akhir file (ref v1.4.8) |

### Editing
| Shortcut | Fungsi |
|---|---|
| Ctrl+Backspace | Hapus kata sebelumnya |
| Shift+Tab | Hapus indentasi (tab) sebelumnya |
| Ctrl+C / Ctrl+X / Ctrl+V | Copy / Cut / Paste (ref v0.6.0) |

Catatan tambahan: arrow keys untuk navigasi kursor (ref v0.6.0); kombinasi Ctrl+Shift+Arrow dan Alt+Shift+Arrow (ref v0.9.1); kompatibilitas Samsung S-Pen (ref v0.7.3), Samsung keyboard (perbaikan v1.4.5, v1.9.6, v1.12.11), Samsung DeX Station (ref v1.3.1), Chromebook (fix fokus v1.7.6), multi-window Samsung (ref v1.1.2), dan mode multi-window modern.

---

## 9. Spesifikasi Halaman Settings Lengkap

Struktur settings Rock Edit (paritas penuh):

### General
1. **Language** — bahasa aplikasi berbeda dari bahasa sistem.
2. **Default Encodings** — default encoding saat membuka/membuat file; opsi pertama = Auto Detect.
3. **File Association** — kelola pemetaan ekstensi file ↔ bahasa syntax (tambah/ubah; ekstensi kosong = default untuk file tanpa ekstensi).
4. **Line Break** — karakter line break saat menyimpan:
   - *Automatic* (default): deteksi dari file asli; jika tidak ada, pakai CRLF.
   - *LF (\n)*: Unix/Unix-like (Android, Linux, macOS modern, FreeBSD, dst.).
   - *CR (\r)*: sistem klasik (Commodore, Apple II, macOS ≤9, dst.).
   - *CR+LF (\r\n)*: Windows, DOS, dan kebanyakan OS non-Unix awal.
5. **Indentation Character** — tab atau spasi (mis. 4 spasi).
6. **Mini Toolbar** — kustomisasi tombol yang tampil di bottom toolbar (visibilitas + urutan).
7. **Word Wrap** — default mati (performa render lebih lambat saat aktif — perlu dijelaskan ke pengguna).
8. **Folder Drawer** — aktif/matikan navigasi folder di layar utama.
9. **Auto Indent** — indentasi otomatis baris baru (default aktif).
10. **Undo By Back Button** — undo via tombol back (default mati).
11. **File Filter** — sembunyikan tipe file tak dikenal di dialog open.
12. **Resume Session** — buka ulang file sesi terakhir (default aktif).

### Input Method
13. **Show Suggestion** — saran kata saat mengetik; opsi:
    - *ON*: saran aktif (untuk menulis artikel).
    - *OFF* (default): saran mati (untuk coding).
    - *OFF (Aggressive)*: paksa matikan saran bila OFF biasa tidak berfungsi di keyboard tertentu.
14. **Auto Capitalize** — kapitalisasi otomatis awal kalimat (tergantung dukungan keyboard).
15. **Accessory View** — baris keyboard tambahan di bawah editor berisi karakter umum: tab, `<`, `>`, `{`, `}`, `&`, `!`, `=`, dll. (disembunyikan saat preview/read-only).

### View
16. **Line Number** — tampil/sembunyi.
17. **Font Type** — Normal / Sans Serif / Serif / Monospace / External (file TTF/OTF via picker khusus).
18. **Font Size** — 12sp s.d. 56sp.
19. **Line Spacing** — 0 s.d. 6sp.

### Auto Save
20. **Auto Save** — aktif/matikan (default mati).
21. **Auto Save Interval** — 30 detik s.d. 10 menit (hanya tampil bila auto save aktif).

### Look And Feel
22. **Theme** — Light / Dark / Black / Dark-Light otomatis / Black-Light otomatis; warna highlight berubah mengikuti tema.
23. **Full Screen** — sembunyikan status bar.

### Backup & Restore
24. **Backup** — ekspor settings + koneksi storage + bookmarks ke file JSON `RE-backup.json`.
25. **Restore** — impor dari file backup; kompatibel lintas versi gratis/Pro.

### About
26. **Version** — versi aplikasi (link ke halaman store).
27. **Developer** — kredit pengembang Rock Edit.
28. **Send Feedback** — kanal feedback/masalah (bisa GitHub issues atau email; referensi memakai thread XDA).
29. **Licenses / Sumber Terbuka** — daftar library pihak ketiga beserta lisensinya (FOSS attribution).

---

## 10. Model Monetisasi & Packaging

**KEPUTUSAN FINAL (terkunci): Rock Edit adalah 100% perangkat lunak bebas dan gratis — tanpa iklan,
tanpa versi berbayar, tanpa IAP, tanpa langganan.** Seluruh fitur tersedia lengkap untuk semua
pengguna tanpa pembayaran dalam bentuk apa pun. Keputusan ini juga menyederhanakan privasi dan
kepatuhan store: tidak ada SDK iklan, tidak ada Play Billing, tidak ada disclosure iklan di
Data Safety, dan tidak ada perbedaan fitur antar pengguna.

Model referensi (dicatat hanya sebagai konteks riset, **tidak** diikuti):

| Paket (referensi) | Harga | Isi | Keterangan |
|---|---|---|---|
| Versi gratis | Rp0 | Fitur 100% sama + iklan | Iklan via AdMob & Facebook Audience Network |
| Versi Pro | Bayar sekali (paid app terpisah) | Fitur identik, tanpa iklan | Package terpisah |

**Pelajaran desain dari referensi (untuk dokumentasi, bukan untuk diterapkan):**

1. Banner ad dihapus di referensi karena mengganggu ruang editing (v0.9.0).
2. Interstitial setelah save/close terbukti paling mengganggu dan akhirnya diganti native ad (v1.5.2).
3. Kesimpulan untuk Rock Edit: dengan menghilangkan iklan sepenuhnya, seluruh masalah UX di atas
   tidak pernah muncul — alur editing tetap bersih dari layar saat pertama hingga terakhir.

**Konsekuensi keputusan full free untuk Rock Edit:**

- Tidak ada menu "Upgrade/Remove Ads"; halaman About hanya menampilkan versi, kredit, dan daftar
  lisensi open source.
- Pendanaan pengembangan (bila diperlukan) melalui donasi/sponsor terpisah dari aplikasi
  (mis. GitHub Sponsors) tanpa mengubah fungsionalitas apa pun di dalam aplikasi.
- Roadmap fitur tidak dibelokkan oleh pertimbangan upsell; semua fitur dirilis ke semua pengguna.

---

## 11. Analisis Arsitektur & Tech Stack

### 11.1 Tech stack produk referensi (terbongkar dari halaman lisensi open source-nya)

Halaman "Open Source Licenses" referensi mengungkap komponen internalnya — sangat berharga sebagai peta arsitektur:

| Komponen | Peran | Lisensi |
|---|---|---|
| highlight.js (sejak v1.12.0) | Engine syntax highlight (JS, dijalankan via WebView) | BSD-3 |
| juniversalchardet | Deteksi otomatis charset | MPL 1.1 |
| Apache Commons Net | Klien FTP/FTPS | Apache 2.0 |
| JSch + sshj | Klien SFTP/SSH | BSD-style / Apache 2.0 |
| Sardine Android | Klien WebDAV | Apache 2.0 |
| NanoHttpd | Local web server untuk preview HTML | BSD-3 |
| Markdown4j → flexmark-java | Render markdown | BSD-style |
| asciidoctor.js | Render AsciiDoc | MIT |
| Eruda | Console debug untuk web preview | MIT |
| Java GitHub API (org.kohsuke) | Integrasi GitHub | MIT |
| GitLab Java API Wrapper | Integrasi GitLab | Apache 2.0 |
| OneDrive SDK for Android | Integrasi OneDrive | MIT |
| Dropbox Core SDK for Java | Integrasi Dropbox | MIT |
| libaums | Akses USB mass storage | Apache 2.0 |
| MaterialRatingBar, Material-ish Progress, FloatingActionButton, Holo ColorPicker, Sweet Alert Dialog, RecyclerView-FastScroll, Material Design Icons | Komponen UI | Apache 2.0 / MIT |
| Google Play Services, Firebase Analytics, Firebase Crashlytics, AdMob, Google OAuth | Layanan & monetisasi (di versi berarsitektur iklan) | Proprietary |

**Insight arsitektur penting:** referensi bermigrasi ke highlight.js (berbasis JS) pada v1.12.0 dan mendapat lompatan performa highlight 100–400%. Artinya pendekatan **highlight berbasis JS dalam WebView** terbukti layak dan cepat untuk editor Android pada file besar, ketika dibungkus dengan rendering pipeline yang baik.

### 11.2 Stack yang direkomendasikan untuk Rock Edit (2026)

| Lapisan | Pilihan rekomendasi | Alternatif |
|---|---|---|
| Bahasa & platform | Kotlin, minSdk 26 (Android 8), target API terbaru (36) | minSdk 24 bila perlu jangkauan |
| UI | Jetpack Compose + View interop untuk editor custom | Material 3 Components |
| Engine editor | **sora-editor** (open source editor Android: line numbers, multi-tab, undo/redo, TextMate/tree-sitter highlight) | Custom view + highlight.js via WebView (jalur terbukti referensi) |
| Grammar highlight | TextMate grammar / tree-sitter | highlight.js + tema CSS |
| Deteksi charset | juniversalchardet (MPL) atau ICU4J | — |
| FTP/FTPS | Apache Commons Net | ftp4j |
| SFTP | sshj | JSch |
| WebDAV | Sardine | — |
| Google Drive | Google Drive API v3 + AppAuth (OAuth) | — |
| Dropbox | Dropbox SDK v2 (short-lived token) | REST langsung |
| OneDrive | MS Graph SDK | REST langsung |
| GitHub/GitLab | REST API v3 / v4 + PAT | org.kohsuke GitHub API |
| Web preview | WebView + local HTTP server (NanoHttpd atau Ktor embedded) + console ala Eruda (Eruda itu sendiri, MIT) | — |
| Markdown/AsciiDoc | flexmark-java / asciidoctor.js | commonmark-java |
| USB | libaums / Storage Access Framework | — |
| Penyimpanan data | Room (recents, bookmarks) + DataStore (settings) | — |
| Kredensial terenkripsi | Android Keystore + EncryptedSharedPreferences / EncryptedFile | SQLCipher |
| Pembelian | Google Play Billing Library v6+ | RevenueCat |
| Crash & error | **ACRA (lokal/email) atau dimatikan default** — privacy-first; tidak memakai Crashlytics pihak ketiga | Crashlytics bila pengguna opt-in |
| Analitik | **Tidak ada secara default**; opt-in & self-host (Umami/Plausible) bila dibutuhkan | — |
| Background | WorkManager (auto-save, sync) | — |
| Print | Android Print Framework (PrintHelper/PrintDocumentAdapter) | — |
| Lokalisasi | res/values-XX + platformCrowdin/Weblate | — |

### 11.3 Pertimbangan performa untuk file besar (kunci kualitas)

1. Seluruh konten dimuat ke memori (tanpa pagination) — sesuai perilaku referensi; batas praktis: file teks polos ±5 MB / 50.000+ baris tetap lancar tanpa highlight; file kode dengan highlight ±10.000+ baris lancar; file jauh lebih besar berisiko crash — tampilkan peringatan ukuran file.
2. Gunakan `SpannableStringBuilder`/custom line-based layout atau editor engine berbasis gap buffer/piece table; hindari re-layout penuh per keystroke.
3. Virtualisasi rendering: hanya render baris terlihat + buffer.
4. Highlight non-blocking (background thread / JS async) dan bertahap (viewport-first).
5. Debounce untuk auto-save dan statistik.
6. Perf test wajib: file 1 MB, 5 MB, 10 MB; perangkat low-end (2 GB RAM).

---

## 12. Strategi Privasi & Kepatuhan Rock Edit

### 12.1 Analisis kebijakan privasi produk referensi (yang dipakai sebagai baseline, lalu ditinggalkan)

Kebijakan referensi (terakhir diubah Januari 2024) memuat praktik berikut:

- Mengumpulkan **info pendaftaran akun** dan info pembelian (via Google Play).
- Kredensial layanan pihak ketiga disimpan terenkripsi lokal, tidak dikirim ke server pengembang (praktik baik — dipertahankan Rock Edit).
- **Pengumpulan otomatis**: tipe perangkat, device ID unik, alamat IP, OS, browser, cara penggunaan app.
- **Error log** dapat dikirim ke pengembang (model, versi OS, lokasi error).
- Layanan pihak ketiga: **Google Play Services, Google Analytics for Firebase, Firebase Crashlytics, Google AdMob, Google OAuth**.
- Pembagian data: ke pemerintah/hukum, provider tepercaya, jaringan iklan & analitik, serta dalam skenario merger/akuisisi.
- Penargetan anak <13 tidak dilakukan; retensi selama diperlukan; kebijakan bisa berubah kapan saja.
- Klaim penting yang baik: semua transfer file device↔cloud terenkripsi dan **tidak melewati server pengembang**; pengembang tidak menyimpan salinan file pengguna.

### 12.2 Desain privasi Rock Edit (lebih ketat dari referensi — differentiator utama)

| Praktik | Referensi | Rock Edit |
|---|---|---|
| Akun pengguna | Ada (registrasi) | **Tidak ada** — tanpa akun sama sekali |
| Analitik penggunaan | Firebase Analytics | **Tidak ada** (atau opt-in eksplisit, self-host) |
| Crash report | Firebase Crashlytics | **Opt-in**; tanpa PII; bisa dinonaktifkan total |
| Iklan | AdMob + Meta Audience Network | **Tidak ada** — full free software, tanpa SDK iklan sama sekali |
| Kredensial FTP/cloud | Terenkripsi lokal | Terenkripsi lokal via **Android Keystore** (standar lebih tinggi, hardware-backed) |
| File pengguna | Tidak dikirim ke server dev | Sama — **arsitektur tidak mungkin** mengirim file; semua transfer device↔server storage langsung |
| Kode pengguna di kompiler online | Terkirim ke layanan kompiler | Sama, tetapi **fitur opsional yang default tidak aktif** dengan dialog persetujuan sebelum pakai pertama |
| Telemetri | IP, device ID | Tidak dikumpulkan |
| Anak-anak | Tidak menarget <13 | Sama + praktik Play Families bila relevan |

### 12.3 Kepatuhan platform & regulasi yang wajib dipenuhi Rock Edit

1. **Google Play Data Safety form** — deklarasikan jujur: data tidak dibagikan ke pihak ketiga; tanpa iklan; tanpa pengumpulan otomatis; kredensial disimpan lokal terenkripsi.
2. **GDPR / UU PDP Indonesia (UU No. 27/2022)** — karena tanpa telemetri, basis legal sederhana: pemrosesan terbatas pada data yang disimpan di perangkat pengguna. Sertakan privacy policy jujur dan ringkas.
3. **Play Permissions policy** — jangan pakai `MANAGE_EXTERNAL_STORAGE` (izin dibatasi Play, butuh justifikasi khusus); gunakan **Storage Access Framework + document tree URI** (persis jalur yang dipakai referensi untuk SD card & folder data).
4. **Target API level** wajib mengikuti kebijakan Play terkini (saat ini API 35/36).
5. **Scoped Storage Android 11+** — pola akses `Android/data` seperti di 4.9 (grant SAF di A11/12, hand-off file manager sistem di A13+).
6. **OSS attribution** — layar "Licenses" di settings wajib mencantumkan semua library open source + lisensinya (Apache 2.0: sertakan NOTICE; MIT/BSD: sertakan copyright; MPL juniversalchardet: sertakan notice MPL).
7. **Tanpa Play Billing & tanpa iklan** — keputusan full free menghilangkan seluruh kewajiban kepatuhan Payments Policy dan Play Ads Policy. Jika kelak keputusan ini berubah, audit ulang kedua kebijakan tersebut sebelum rilis.

---

## 13. Batasan Legal — Boleh & Tidak Boleh Disalin

Prinsip: **fitur dan konsep tidak dilindungi hak cipta; ekspresi (kode, aset, teks, branding) dilindungi.**

### BOLEH (dilindungi hukum untuk ditiru)

- Membangun aplikasi dengan **set fitur yang identik** (editor cepat, syntax highlight, multi-tab, cloud, dll.) — fungsionalitas tidak bisa dihakciptakan.
- Meniru **pola UX/interaksi** umum (drawer kiri/kanan, tab, dialog pencarian, long-press selection, dsb.).
- Menggunakan **library open source yang sama** (semua yang tercantum berlisensi permisif: Apache 2.0, MIT, BSD, MPL) dengan memenuhi kewajiban atribusinya.
- Membuat dokumentasi help dengan **struktur topik serupa** (open/save/search/settings...) dalam bahasa sendiri.
- Meniru **model monetisasi** (gratis+iklan / Pro).

### TIDAK BOLEH (melanggar hukum / kebijakan Play)

- Menggunakan nama, logo, ikon, warna branding, screenshot, video, atau aset grafis referensi dalam bentuk apa pun.
- **Mendekompilasi / reverse-engineer APK** referensi dan menyalin kodenya.
- Menyalin **teks** deskripsi produk, privacy policy, EULA, FAQ, atau konten help secara verbatim (hak cipta teks).
- Menyalin daftar string resource, layout XML, atau aset lain dari APK.
- Menggunakan nama referensi sebagai keyword iklan/ASO untuk mengecoh pengguna (pelanggaran trademark & Play Impersonation Policy).
- Menerbitkan Rock Edit dengan tampilan yang sengaja dibuat membingungkan sebagai produk referensi (trade dress).

### Kewajiban Rock Edit

1. Semua aset (ikon launcher, ikon in-app, ilustrasi, warna, tipografi) dibuat orisinal.
2. Semua teks UI, help, dan privacy policy ditulis dari nol.
3. Catat pohon lisensi setiap library yang dipakai di layar "Licenses".
4. EULA/ToS Rock Edit ditulis sendiri.
5. Pertahankan bukti proses desain orisinal (clean-room log) — dokumentasi ini bagian darinya.

---

## 14. Roadmap Pengembangan Rock Edit

### Fase 0 — Fondasi (2–3 minggu)
- Setup proyek (Kotlin, minSdk 26, Compose + sora-editor atau WebView-highlight).
- Desain sistem tema (Light/Dark/Black + auto), ikon orisinal, wireframe semua layar.
- Kerangka: main activity, editor screen, nav drawer, folder drawer, tab manager, settings (DataStore), Room DB.

### Fase 1 — MVP "Editor Inti" (4–6 minggu) → rilis beta tertutup
- Editor: multi-tab, undo/redo unlimited, line numbers, auto-indent, indentasi tambah/kurang, goto line, word wrap, read-only, full screen.
- Highlight: 40 bahasa terpopuler dulu (Java, Kotlin, Python, JS, TS, C/C++, C#, PHP, HTML, CSS, JSON, XML, YAML, Markdown, SQL, Shell, Go, Rust, Swift, Lua, dll.) + file association + dialog pilih bahasa dengan filter.
- Search/replace lengkap (case, regex + backreference, wrap around, riwayat, \n).
- Encoding: auto-detect + manual + default setting + BOM; line break LF/CR/CRLF auto-detect.
- File: open/save/save-as via picker bawaan + SAF; SD card; filter & sort; rename/delete; create file/folder; path view; fast scroller.
- Seleksi: selector, double-click word, long-press selection mode, toolbar seleksi lengkap, floating toolbar.
- Insert color (HSV picker) & insert timestamp; statistik kata/karakter; share file/teks; share-in.
- Settings fase-1 (semua kecuali terkait cloud); session resume; auto-save.
- Uji performa file besar (≥10.000 baris).

### Fase 2 — Konektivitas & Organisasi (4–6 minggu)
> **STATUS IMPLEMENTASI (v0.1.0–v0.8.0, repo SecretArrow/RockEdit — CI/E2E hijau, semua rilis via GitHub Actions):**
> - ✅ Fase 0/1 tuntas (v0.1.0, 2026-10-03): editor inti (undo/redo 100 langkah, gutter+nomor baris, word wrap, read-only), SAF open/save/save-as + persist permission, auto-detect encoding (juniversalchardet) + peringatan binary, line break LF/CR/CRLF auto-detect + preferensi simpan, Find/Replace (case, wrap-around, replace all), goto line, statistik, recent files (40), share/share-in, tema terang/gelap/sistem, settings, ID+EN, ikon adaptif. Pipeline CI/CD penuh: CI (lint+unit+build), E2E (emulator API 30, auto-retry), auto-release (tag → APK signed + SHA256SUMS) — semua build via GitHub Actions, tanpa build lokal.
> - ✅ v0.2.0 (2026-10-03, bagian organisasi Fase 2): operasi baris (duplikat/hapus/naik/turun — LF/CR/CRLF-safe, terundo), bookmark per-baris per-file + dialog lompat, pemulihan kursor+scroll per file (session resume tahap 1), tema Hitam AMOLED, mode layar penuh. Toolchain: AGP 8.9.1 / Gradle 8.11.1 / Kotlin 2.2.20 / compileSdk 36; dependensi & Actions ter-bump penuh (10 PR Dependabot ditutup/merge).
> - ✅ v0.3.0 (2026-10-04, bagian format Fase 1/3): highlight sintaks 20 bahasa (tokenizer murni), buka-ulang/simpan-dengan encoding (15 charset), ukuran font, auto-save, insert tanggal/waktu.
> - ✅ v0.4.0 (2026-10-04, organisasi): **multi-tab penuh** (maks 10, indikator dirty, tutup/tutup lainnya, restore set tab antar-luncuran dengan lazy load, undo/bookmark/encoding per-tab), **folder browser SAF** (breadcrumb, sortir folder-first, filter hidden, folder terakhir diingat), save-all saat keluar.
> - ✅ v0.5.0 (2026-10-04, format & polish): highlight **48 bahasa** + nama populer tanpa ekstensi (Makefile/Dockerfile/Gemfile), **cetak** (PDF A4 via layar cetak Android), **backup/restore JSON** via SAF (semua pengaturan + recents + sessions + bookmarks + tabs), layar **Licenses** (atribusi OSS).
> - ✅ v0.6.0 (2026-10-04, konektivitas): **Storage Manager FTP/FTPS/SFTP/WebDAV** — kredensial terenkripsi Android Keystore (AES-GCM), RemoteContentProvider (file remote = tab editor biasa, simpan = unggah balik), parser FTP/PROPFIND murni; izin INTERNET dinyatakan (khusus koneksi milik pengguna).
> - ✅ v0.7.0 (2026-10-04, preview & eksekusi): **pratinjau HTML + Markdown** (renderer murni + WebView, tema ikut app), **jalankan kode daring Piston** (28 bahasa, opt-in eksplisit, default MATI), layar **Bantuan** offline (8 FAQ).
> - ✅ v0.8.0 (2026-10-04, git hosting): **GitHub & GitLab via PAT** — repo dimuat owner/repo/branch, simpan = commit sungguhan (sha-aware, .gitkeep utk folder, commit body GitLab), API murni ter-test.
> - ⏳ Tersisa (butuh input/pengujian eksternal): cloud OAuth Drive/Dropbox/OneDrive (butuh registrasi klien OAuth), USB OTG & mode root (butuh perangkat fisik), AsciiDoc preview, lokalisasi 30+ bahasa, Publikasi Play Store (Fase 4 — butuh akun developer).

- ✅ Storage Manager + FTP/FTPS/SFTP (Commons Net + sshj) — tuntas v0.6.0; WebDAV pakai klien HttpURLConnection sendiri (tanpa Sardine).
- ⏳ Google Drive, Dropbox, OneDrive (OAuth) — tertunda: perlu client ID/secret dari konsol developer.
- ✅ GitHub & GitLab (PAT; GitLab self-hosted via field host) — tuntas v0.8.0.
- ✅ Recent opened (40); bookmark per file; clear history (via penghapusan recents). ⏳ recent added & bookmark folder: belum.
- ✅ Folder browser SAF (bukan drawer — pola activity lebih sederhana & stabil); ✅ backup/restore JSON. ⏳ Android/data (A11/12 grant), mode root, USB drive: tertunda (perangkat).
- ✅ Print (PDF A4 monospace). ⏳ margin 10mm + highlight warna: belum.
- ✅ Preview HTML (WebView langsung, tanpa server lokal) + Markdown (renderer murni). ⏳ AsciiDoc: belum.
- ✅ Kompilasi/eksekusi online (Piston, 28 bahasa, opt-in default off + dialog persetujuan).

### Fase 4 — Peluncuran (2–3 minggu)
- Lokalisasi 8 bahasa inti dulu (EN, ID, ES, PT-BR, FR, DE, RU, JA, ZH-CN) lalu 30+ bertahap via komunitas.
- Privacy policy & Data Safety (tanpa iklan, tanpa berbagi data); halaman Licenses; help center (struktur 6 kategori seperti referensi).
- Listing Play Store orisinal; beta terbuka → produksi.
- *Tanpa Pro/IAP — monetisasi terkunci ke full free software (lihat bagian 10).*

### Fase 5 — Pasca-rilis (berkelanjutan)
- Tambah bahasa sintaks bertahap menuju 170+; perbaikan performa highlight (target lompatan ala v1.12.0 referensi).
- Adaptasi tiap versi Android baru (pelajari pola changelog referensi di Lampiran A).
- Program translator komunitas; kanal feedback; rilis rutin tiap 4–8 minggu.

---

## 15. Risiko & Metrik Keberhasilan

### Risiko utama & mitigasi

| Risiko | Dampak | Mitigasi |
|---|---|---|
| Performa highlight di file besar tidak setara referensi | Ulasan buruk ("lag") | Pilih engine terbukti (sora-editor/highlight.js), benchmark sejak fase 1, virtualisasi rendering |
| Fragmen OAuth Drive/Dropbox/OneDrive rumit | Koneksi gagal di beberapa device | Mulai dengan FTP/SFTP (paling stabil), cloud bertahap; test matrix device |
| Kebijakan Play soal storage berubah | Fitur akses file rusak | Ikuti SAF penuh (jalur referensi terbukti selamat dari Android 4.4→16); pantau rilis Android tiap tahun |
| Crash file sangat besar (arsitektur whole-in-memory) | 1-star reviews | Peringatan ukuran file + pesan batas di FAQ + optimasi memori |
| Kemiripan terlalu dekat dengan referensi (trade dress) | Tuntutan / penolakan Play | Review desain oleh orang kedua; aset & teks orisinal; nama "Rock Edit" berdiri sendiri |
| Kompiler online pihak ketiga berbayar/terbatas | Biaya / fitur mati | Self-host judge API, atau jadikan fitur opsional, atau batasi kuota |

### Metrik keberhasilan (6 bulan pertama)

- Crash-free rate ≥ 99,5%.
- Waktu buka file 1 MB < 400 ms di perangkat low-end.
- Rating Play ≥ 4,5; retensi D7 ≥ 35%.
- 0 laporan privasi; 100% listing Data Safety akurat.
- Paritas fitur: checklist dokumen ini terpenuhi ≥ 95% sebelum keluar beta.

---

## Lampiran A — Timeline Evolusi Produk Referensi (sinyal roadmap)

Kronologi ringkas versi (untuk memahami urutan prioritas fitur yang terbukti di pasar):

- **0.1.0 (Sep 2014)**: rilis awal — editor cepat, deteksi encoding, highlight, multi-bahasa.
- **0.6.0–0.7.x (Okt–Des 2014)**: mode root, keyboard fisik + shortcut, fast scrollbar, word wrap, font eksternal, auto-save, material design Lollipop, read-only, logcat highlight.
- **0.8.0–0.9.x (Mar–Okt 2015)**: tema black AMOLED, BOM, auto-indent, SD card Lollipop write, reopen files, remember modifications saat app dibunuh sistem, full screen, interstitial menggantikan banner.
- **1.0.0 (Feb 2016)**: **lompatan besar — FTP/FTPS/SFTP + Google Drive/OneDrive/Dropbox + konfigurasi line break**.
- **1.1.x–1.3.x (Mar 2016–Jun 2017)**: regex search, tab UX, share text, keyword shortcut, Swift/PowerShell syntax, bookmark folder, DeX, key file SFTP, wide screen.
- **1.4.x–1.5.x (2018–2019)**: print, statistik kata, file association, indent multi-line, folder creation, SAF, API 28, symlink FTP, 36 visual styles, native ads.
- **1.6.x (2020)**: **folder drawer (produk productivity) + session recovery default + WebDAV + JS eval/console + timestamp**.
- **1.7.x–1.8.x (2020–2021)**: **GitHub/GitLab integration**, word wrap toggle, USB drive, splash, dark preview, Android/data akses, Dropbox short token.
- **1.9.x–1.10.x (2022–2023)**: **kompiler online 30+ bahasa**, floating selection toolbar, themed icon A13, numpy/scipy, bottom toolbar paste/kursor, translate menu.
- **1.11.x (2024–2025)**: **backup/restore**, tema auto dark/light, mini toolbar customization, print margin+color, OneDrive fix, 10-detik session dialog, OpenJDK 21/Python 3.13.
- **1.12.x (Okt 2025–Sep 2026)**: **migrasi ke highlight.js (performa highlight +100–400%)**, +20 visual styles, AsciiDoc preview, filter dialog syntax, ERB/Clean/Flix/LLVM IR/WebAssembly, session tab index, target Android 16.

Pelajaran: urutan aman untuk Rock Edit = editor inti → cloud/FTP → folder drawer & session → Git hosting → kompiler online → backup → modernisasi engine.

## Lampiran B — FAQ Teknis Penting (dari referensi, wajib dijawab di help Rock Edit)

1. **Batas ukuran file?** Tidak ada pagination; seluruh file dimuat ke memori; batas tergantung RAM perangkat; file melebihi batas akan crash. Praktis: teks polos ±5 MB / 50.000+ baris lancar; file kode dengan highlight ±10.000 baris lancar. Pastikan tidak salah buka file media/video.
2. **File tidak muncul di daftar?** Matikan file type filter (corong) — mungkin ekstensinya tidak dianggap teks.
3. **Error permission saat menyimpan?** Umumnya file dibuka dari app pihak ketiga yang hanya memberi izin baca ("open with" = baca; "edit with" = tulis). Buka lewat file explorer bawaan aplikasi lalu simpan lagi.
4. **Bisa simpan ke SD card?** Android 4.4 read-only bagi app pihak ketiga; Android 5.0+ perlu grant write lewat picker (pilih root folder SD card).
5. **Karakter tampil kacau?** Deteksi encoding keliru → ganti encoding manual sesuai bahasa (mis. ISO-8859-7 untuk Yunani).
6. **Initial path FTP?** Tulis di field server, mis. `192.168.4.47/data`.
7. **Apakah ada versi berbayar?** **Tidak.** Rock Edit adalah 100% perangkat lunak bebas dan gratis: satu aplikasi, semua fitur lengkap, tanpa iklan, tanpa pembelian dalam-app, tanpa langganan.
8. **Ada code assistant/compiler lokal?** Tidak ada di editor referensi (ada di produk lain pengembangnya). Rock Edit: paritas v1; evaluasi pembeda di v2.
9. **Akses folder Android/data di A13+?** Lewat file manager sistem (lihat 4.9).

## Lampiran C — Sumber Riset

1. Halaman produk referensi — rhmsoft.com/qedit.html (fitur, daftar bahasa, encoding, bahasa terjemahan, 8 screenshot, video demo, tanggal rilis).
2. Pusat bantuan referensi — rhmsoft.com/qedit/help.html + 24 sub-halaman: intro, changelog, nls (multilanguage), drawer, folder, toolbar, open, save, storage, recent, bookmark, selection, undo, search, color, data (Android 11), syntax, encoding, keyboard, execute, backup, settings, pro (remove ads), licenses, faq.
3. Privacy policy — rhmsoft.com/privacy.html (terakhir diubah 18 Januari 2024).
4. Listing Google Play (package com.rhmsoft.edit & com.rhmsoft.edit.pro) — tidak dapat diakses penuh saat riset; data pasar bersifat estimasi dan perlu verifikasi ulang manual.
5. Situs developer — rhmsoft.com (portofolio produk, konteks).
