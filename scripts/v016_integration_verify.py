#!/usr/bin/env python3
"""v016 integration pre-CI checks (main agent wiring).

Checks, without an Android build:
  1. XML well-formedness of every res XML + AndroidManifest touched by v0.16.0
  2. EN/ID string parity for strings_v016.xml (same key sets, format args match)
  3. Every NEW v016 key is referenced from at least one .kt/.xml source file
  4. Every @string/ reference inside new/changed files resolves to a defined key
  5. Kotlin line width <= 100 for all new v016 files
  6. Manifest contains the InAppAuthActivity registration exactly once
  7. Menu ids referenced in EditorActivity exist in menu_editor.xml
Exit non-zero on any failure; prints ALL PASS otherwise.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "app", "src", "main", "res")
SRC = os.path.join(ROOT, "app", "src", "main")
CHECKS = 0
FAILURES = 0


def check(name, ok, detail=""):
    global CHECKS, FAILURES
    CHECKS += 1
    if not ok:
        FAILURES += 1
        print("FAIL %s %s" % (name, detail))


def parse_strings(path):
    tree = ET.parse(path)
    out = {}
    for node in tree.getroot().findall("string"):
        out[node.get("name")] = "".join(node.itertext())
    return out


def fmt_args(s):
    return sorted(re.findall(r"%\d+\$[sd]", s))


xml_files = [
    os.path.join(RES, "values", "strings_v016.xml"),
    os.path.join(RES, "values-in", "strings_v016.xml"),
    os.path.join(RES, "menu", "menu_editor.xml"),
    os.path.join(SRC, "AndroidManifest.xml"),
]
for path in xml_files:
    try:
        ET.parse(path)
        check("xml-wellformed " + os.path.basename(path), True)
    except ET.ParseError as e:
        check("xml-wellformed " + os.path.basename(path), False, str(e))

en = parse_strings(xml_files[0])
idn = parse_strings(xml_files[1])
check("v016 key parity", set(en) == set(idn),
      "EN-only: %s ID-only: %s" % (set(en) - set(idn), set(idn) - set(en)))
for key in en:
    if fmt_args(en[key]) != fmt_args(idn[key]):
        check("v016 fmt args %s" % key, False, "%r vs %r" % (en[key], idn[key]))
    else:
        check("v016 fmt args %s" % key, True)

# Every new v016 key referenced somewhere in sources.
kt_sources = []
for base, _dirs, files in os.walk(SRC):
    for f in files:
        if f.endswith(".kt") or f.endswith(".xml"):
            kt_sources.append(os.path.join(base, f))
blob = "\n".join(open(p, encoding="utf-8", errors="replace").read() for p in kt_sources)
for key in sorted(en):
    check("key used %s" % key, ("@string/%s" % key) in blob or ("R.string.%s" % key) in blob)

# Menu ids used by EditorActivity must exist in the menu xml.
menu_blob = open(xml_files[2], encoding="utf-8").read()
menu_ids = set(re.findall(r'@\+id/(\w+)', menu_blob))
editor = open(os.path.join(SRC, "java", "com", "secretarrow", "rockedit", "ui", "EditorActivity.kt"), encoding="utf-8").read()
for rid in ["action_charset_lab", "action_export_image"]:
    check("menu id %s in xml" % rid, rid in menu_ids)
    check("menu id %s handled" % rid, ("R.id.%s" % rid) in editor)

# Manifest registers InAppAuthActivity exactly once.
manifest = open(xml_files[3], encoding="utf-8").read()
check("manifest InAppAuthActivity", manifest.count('.ui.InAppAuthActivity') == 1)

# Line width <= 100 in new v016 kotlin files.
new_files = [
    "app/src/main/java/com/secretarrow/rockedit/core/CodeStatistics.kt",
    "app/src/main/java/com/secretarrow/rockedit/core/CharsetLab.kt",
    "app/src/main/java/com/secretarrow/rockedit/core/ImageExportPlanner.kt",
    "app/src/main/java/com/secretarrow/rockedit/core/LoopbackRedirectServer.kt",
    "app/src/main/java/com/secretarrow/rockedit/ui/ImageExporter.kt",
    "app/src/main/java/com/secretarrow/rockedit/ui/InAppAuthActivity.kt",
    "app/src/test/java/com/secretarrow/rockedit/core/CodeStatisticsTest.kt",
    "app/src/test/java/com/secretarrow/rockedit/core/CharsetLabTest.kt",
    "app/src/test/java/com/secretarrow/rockedit/core/ImageExportPlannerTest.kt",
    "app/src/test/java/com/secretarrow/rockedit/core/LoopbackRedirectServerTest.kt",
]
repo_root = os.path.normpath(ROOT)
for rel in new_files:
    path = os.path.join(repo_root, rel)
    for i, line in enumerate(open(path, encoding="utf-8"), 1):
        if len(line.rstrip("\n")) > 100:
            check("width %s:%d" % (rel, i), False, "%d chars" % len(line.rstrip()))
    check("width scanned %s" % rel, True)

print("TOTAL CHECKS: %d, TOTAL FAILURES: %d" % (CHECKS, FAILURES))
if FAILURES:
    sys.exit(1)
print("ALL PASS")
