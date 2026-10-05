#!/usr/bin/env python3
"""v0.18.0 pre-CI verification: system-bar insets + editor file operations.

Checks (mirrors the repo's v016/v017 integration verify style):
 1. New/edited Kotlin files pass bracket balance (check_kt_balance.py logic).
 2. All 14 activities install SystemBars after setContentView.
 3. SystemBars handles the defensive cases (ime, cutout, base padding, CONSUMED).
 4. menu_editor.xml is well-formed XML and declares the 3 new action ids.
 5. Every new menu id has a matching onOptionsItemSelected branch.
 6. strings_v018.xml EN/ID parity: same keys, same format args, same count.
 7. New strings keys are used somewhere (menu XML or Kotlin).
 8. versionName bumped to 0.18.0; CHANGELOG entry present; README mentions
    the new features; docs/rockedit.md has section 4.17.
 9. Zen restore stays on the edge-to-edge baseline (no setDecorFitsSystemWindows(true)).
10. E2E insets regression test exists and asserts both paddings.
"""
import io
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app" / "src" / "main"
FAILS: list[str] = []


def check(cond: bool, msg: str) -> None:
    if cond:
        print(f"  OK  {msg}")
    else:
        FAILS.append(msg)
        print(f"FAIL  {msg}")


def read(path: Path) -> str:
    return io.open(path, encoding="utf-8").read()


# --- 1. bracket balance for the touched Kotlin files -------------------------
sys.path.insert(0, str(ROOT / "scripts"))
import check_kt_balance  # noqa: E402

kt_files = [
    APP / "java/com/secretarrow/rockedit/ui/SystemBars.kt",
    APP / "java/com/secretarrow/rockedit/ui/EditorActivity.kt",
    APP / "java/com/secretarrow/rockedit/MainActivity.kt",
    APP / "java/com/secretarrow/rockedit/ui/InAppAuthActivity.kt",
    ROOT / "app/src/androidTest/java/com/secretarrow/rockedit/InsetsE2eTest.kt",
]
for path in kt_files:
    try:
        deltas = check_kt_balance.balance(read(path))
        ok, detail = deltas == (0, 0, 0), f"brace/paren/bracket deltas={deltas}"
    except Exception as exc:  # pragma: no cover - guards script drift
        ok, detail = False, f"checker error: {exc}"
    check(ok, f"bracket balance: {path.name} ({detail})")

# --- 2. all activities install SystemBars ------------------------------------
activities = [
    ("MainActivity.kt", ""),
    ("EditorActivity.kt", "ui"),
    ("SettingsActivity.kt", "ui"),
    ("FolderBrowserActivity.kt", "ui"),
    ("StorageManagerActivity.kt", "ui"),
    ("RemoteBrowserActivity.kt", "ui"),
    ("HelpActivity.kt", "ui"),
    ("PreviewActivity.kt", "ui"),
    ("LicensesActivity.kt", "ui"),
    ("GrepActivity.kt", "ui"),
    ("DiffActivity.kt", "ui"),
    ("HexViewerActivity.kt", "ui"),
    ("SplitEditorActivity.kt", "ui"),
    ("InAppAuthActivity.kt", "ui"),
]
for name, sub in activities:
    body = read(APP / "java/com/secretarrow/rockedit" / sub / name)
    has_install = "SystemBars.install(" in body
    order_ok = has_install and body.index("SystemBars.install(") > body.index("setContentView(")
    check(has_install, f"{name}: SystemBars.install present")
    check(order_ok, f"{name}: install happens after setContentView")
manifest = read(APP / "AndroidManifest.xml")
declared = re.findall(r'android:name="\.?([\w.]*Activity)"', manifest)
check(len(declared) >= 14, f"manifest declares >=14 activities ({len(declared)})")

# --- 3. SystemBars defensive coverage ----------------------------------------
sbars = read(APP / "java/com/secretarrow/rockedit/ui/SystemBars.kt")
for token, why in [
    ("enableEdgeToEdge()", "edge-to-edge entry point"),
    ("WindowInsetsCompat.Type.systemBars()", "status + navigation bars"),
    ("WindowInsetsCompat.Type.displayCutout()", "display cutout"),
    ("WindowInsetsCompat.Type.ime()", "keyboard inset"),
    ("CONSUMED", "insets consumed at root"),
    ("isAppearanceLightStatusBars", "status icon appearance"),
    ("isAppearanceLightNavigationBars", "nav icon appearance"),
]:
    check(token in sbars, f"SystemBars covers {why}")
check(
    "paddingLeft" in sbars and "baseLeft" in sbars,
    "SystemBars captures baseline padding (idempotent re-dispatch)",
)

# --- 4/5. menu ids and handler wiring ----------------------------------------
menu = ET.parse(APP / "res/menu/menu_editor.xml").getroot()
menu_ids = {it.get("{http://schemas.android.com/apk/res/android}id") for it in menu.iter("item")}
for action in ["action_open_file", "action_open_recent", "action_save_all"]:
    check(f"@+id/{action}" in menu_ids, f"menu_editor declares {action}")
editor = read(APP / "java/com/secretarrow/rockedit/ui/EditorActivity.kt")
for action, branch in [
    ("action_open_file", "R.id.action_open_file -> openFileLauncher.launch"),
    ("action_open_recent", "R.id.action_open_recent -> showOpenRecentDialog()"),
    ("action_save_all", "R.id.action_save_all -> saveAllDirtyTabs()"),
]:
    check(branch in editor, f"EditorActivity handles {action}")
check("OpenDocument()" in editor and "openFileLauncher" in editor, "open file SAF launcher wired")
check("dirtyFileTabs()" in editor, "save all iterates dirty file tabs")
check("getOrElse { emptyList() }" in editor, "recents read failure -> empty list")
check("getOrNull(which)" in editor, "dialog index guarded with getOrNull")

# --- 6/7. strings parity and usage -------------------------------------------
en = ET.parse(APP / "res/values/strings_v018.xml").getroot()
idn = ET.parse(APP / "res/values-in/strings_v018.xml").getroot()
en_map = {e.get("name"): (e.text or "") for e in en.iter("string")}
id_map = {e.get("name"): (e.text or "") for e in idn.iter("string")}
check(set(en_map) == set(id_map), f"EN/ID key parity ({sorted(en_map)} vs {sorted(id_map)})")
for key in en_map:
    args_en = sorted(re.findall(r"%\d\$s|%s|%d", en_map[key]))
    args_id = sorted(re.findall(r"%\d\$s|%s|%d", id_map[key]))
    check(args_en == args_id, f"format args parity for {key}")
res_texts = "\n".join(
    read(p) for p in list((APP / "res").rglob("*.xml")) + list((APP / "java").rglob("*.kt"))
)
for key in en_map:
    check(f"@string/{key}" in res_texts or f"R.string.{key}" in res_texts, f"{key} is referenced")

# --- 8. version + docs --------------------------------------------------------
gradle = read(ROOT / "app/build.gradle.kts")
check('"0.18.0"' in gradle, "versionName 0.18.0")
changelog = read(ROOT / "CHANGELOG.md")
check("## [0.18.0] - 2026-10-05" in changelog, "CHANGELOG has 0.18.0 entry")
readme = read(ROOT / "README.md")
check("v0.18.0" in readme and "Open Recent" in readme, "README documents v0.18.0")
docs = read(ROOT / "docs/rockedit.md")
check("### 4.17" in docs and "SystemBars" in docs, "docs/rockedit.md has 4.17")

# --- 9. zen restore stays edge-to-edge ---------------------------------------
check("setDecorFitsSystemWindows(window, true)" not in editor, "zen restore no longer re-enables decor fitting")
check(editor.count("setDecorFitsSystemWindows") >= 3, "editor still manages fullscreen/zen paths")

# --- 10. E2E regression test --------------------------------------------------
e2e = read(ROOT / "app/src/androidTest/java/com/secretarrow/rockedit/InsetsE2eTest.kt")
check("paddingTop" in e2e and "paddingBottom" in e2e, "E2E asserts both paddings")
check("MainActivity" in e2e and "EditorActivity" in e2e, "E2E covers main + editor")

print()
if FAILS:
    print(f"v018_verify: {len(FAILS)} FAILURE(S)")
    for f in FAILS:
        print(f" - {f}")
    sys.exit(1)
print("v018_verify: ALL PASS")
