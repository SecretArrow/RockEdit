#!/usr/bin/env python3
"""1:1 Python mirror of core/CharsetLab.kt (v0.16.0) to verify the Kotlin test
expectations before CI (same technique as scripts/v014_*_verify.py).

Covers the three pure data paths of CharsetLab: BOM detection (with the
UTF-32LE-before-UTF-16LE overlap rule), the hex() renderer, and a subset of
the encode/decode logic through Python codecs - errors="strict" try/except
counts unmappable sequences exactly like the Kotlin REPORT loop (one count
per bad sequence, so a surrogate pair counts once and "euro+emoji" is 2).

Java/Python divergence mirrored where it exists: Java's "UTF-16" alias is
big-endian WITH a BOM while Python's "utf-16" is little-endian WITH a BOM,
so the Java alias is modeled as b"\\xfe\\xff" + text.encode("utf-16-be").
Decode expectations use errors="replace", which follows the same Unicode
maximal-subpart rule as Java's REPLACE action (one U+FFFD per bad sequence,
matching CharsetLab's per-sequence REPORT count)."""
import sys

UTF8_BOM = b"\xef\xbb\xbf"
UTF32LE_BOM = b"\xff\xfe\x00\x00"
UTF32BE_BOM = b"\x00\x00\xfe\xff"
UTF16LE_BOM = b"\xff\xfe"
UTF16BE_BOM = b"\xfe\xff"

PREVIEW_CHARS = 200
HEX_BYTES = 32


def bom_name(raw):
    """Mirror of CharsetLab.bomName: UTF-8 first, then UTF-32LE BEFORE
    UTF-16LE (FF FE 00 00 overlaps both), UTF-32BE, then the 16-bit BOMs."""
    if raw.startswith(UTF8_BOM):
        return "UTF-8"
    if raw.startswith(UTF32LE_BOM):
        return "UTF-32LE"
    if raw.startswith(UTF32BE_BOM):
        return "UTF-32BE"
    if raw.startswith(UTF16LE_BOM):
        return "UTF-16LE"
    if raw.startswith(UTF16BE_BOM):
        return "UTF-16BE"
    return None


def hex_pairs(raw, max_bytes=HEX_BYTES):
    """Mirror of CharsetLab.hex: lowercase space-separated pairs, truncated,
    "" for empty input or a non-positive max_bytes."""
    if max_bytes <= 0 or not raw:
        return ""
    return " ".join("%02x" % b for b in raw[:max_bytes])


def unmappable_count(text, encoding):
    """Mirror of the CharsetLab REPORT encode loop: encode with errors
    "strict", catch UnicodeEncodeError and count the unmappable characters.
    CPython's charmap encoders group CONSECUTIVE unmappable characters into
    one error span (Java reports each sequence separately, a surrogate pair
    as UNMAPPABLE[2]), so counting the span's chars (end - start) aligns
    both implementations on "one count per unmappable character": euro+emoji
    on ISO-8859-1 yields 2, exactly like the Kotlin expectation."""
    count = 0
    pos = 0
    while pos < len(text):
        try:
            text[pos:].encode(encoding, errors="strict")
            break
        except UnicodeEncodeError as error:
            count += error.end - error.start
            pos += error.end
    return count


def utf16_alias(text):
    """Java "UTF-16" alias: BE units plus a big-endian BOM, no BOM when
    text is empty (matches the JVM encoder)."""
    if not text:
        return b""
    return b"\xfe\xff" + text.encode("utf-16-be")


def replaced(raw, encoding):
    """Mirror of the REPLACE pass (String(bytes, charset)): U+FFFD per
    maximal bad sequence."""
    return raw.decode(encoding, errors="replace")


def to_utf16_units(text):
    """Expands a Python string into UTF-16 code units (Kotlin Char list) so
    preview-cut math matches Java's UTF-16 indexing."""
    units = []
    for ch in text:
        code = ord(ch)
        if code > 0xFFFF:
            value = code - 0x10000
            units.append(chr(0xD800 + (value >> 10)))
            units.append(chr(0xDC00 + (value & 0x3FF)))
        else:
            units.append(ch)
    return "".join(units)


def main():
    failures = 0
    total = 0

    def check(name, actual, expected):
        nonlocal failures, total
        total += 1
        if actual != expected:
            failures += 1
            print("FAIL %-34s expected %r, got %r" % (name, expected, actual))
        else:
            print("pass %-34s %r" % (name, actual))

    # ------------------------------------------------------------ BOM (13)
    check("bom-empty", bom_name(b""), None)
    check("bom-utf8", bom_name(b"\xef\xbb\xbfhi"), "UTF-8")
    check("bom-utf16le", bom_name(b"\xff\xfehi\x00"), "UTF-16LE")
    check("bom-utf16be", bom_name(b"\xfe\xff\x00hi"), "UTF-16BE")
    check("bom-utf32le-full", bom_name(b"\xff\xfe\x00\x00h\x00\x00\x00"), "UTF-32LE")
    check("bom-utf32le-beats-utf16le", bom_name(b"\xff\xfe\x00\x00"), "UTF-32LE")
    check("bom-utf32be", bom_name(b"\x00\x00\xfe\xff\x00\x00\x00h"), "UTF-32BE")
    check("bom-short-ff-fe", bom_name(b"\xff\xfe"), "UTF-16LE")
    check("bom-ff-fe-00", bom_name(b"\xff\xfe\x00"), "UTF-16LE")
    check("bom-plain-a", bom_name(b"A"), None)
    check("bom-incomplete-utf8", bom_name(b"\xef\xbb"), None)
    check("bom-lone-ff", bom_name(b"\xff"), None)
    check("bom-fe-ff-00-00", bom_name(b"\xfe\xff\x00\x00"), "UTF-16BE")

    # ------------------------------------------------------------ hex (7)
    check("hex-empty", hex_pairs(b""), "")
    check("hex-ff-0a", hex_pairs(b"\xff\x0a"), "ff 0a")
    check("hex-exact-32", hex_pairs(bytes(range(32))),
          " ".join("%02x" % i for i in range(32)))
    check("hex-truncates-40", hex_pairs(bytes(range(40))),
          " ".join("%02x" % i for i in range(32)))
    check("hex-custom-max", hex_pairs(b"\x01\x02\x03", 2), "01 02")
    check("hex-zero-max", hex_pairs(b"\x01", 0), "")
    check("hex-negative-max", hex_pairs(b"\x01", -3), "")

    # ------------------------------------------------- UTF-8 byte counts (6)
    check("utf8-len-a", len("a".encode("utf-8")), 1)
    check("utf8-len-e-acute", len("\u00e9".encode("utf-8")), 2)
    check("utf8-len-euro", len("\u20ac".encode("utf-8")), 3)
    check("utf8-len-emoji", len("\U0001f600".encode("utf-8")), 4)
    check("utf8-len-hello", len("hello".encode("utf-8")), 5)
    check("utf8-emoji-hex", hex_pairs("\U0001f600".encode("utf-8")), "f0 9f 98 80")

    # ------------------------------------ ISO-8859-1 / US-ASCII counts (9)
    check("latin1-e-acute-unmappable", unmappable_count("\u00e9", "iso-8859-1"), 0)
    check("latin1-e-acute-bytes", "\u00e9".encode("iso-8859-1"), b"\xe9")
    check("latin1-euro-unmappable", unmappable_count("\u20ac", "iso-8859-1"), 1)
    check("latin1-emoji-unmappable", unmappable_count("\U0001f600", "iso-8859-1"), 1)
    check("latin1-euro-emoji-count", unmappable_count("\u20ac\U0001f600", "iso-8859-1"), 2)
    check("latin1-euro-emoji-bytes",
          len("\u20ac\U0001f600".encode("iso-8859-1", errors="ignore")), 0)
    check("latin1-mixed-bytes", len("a\u20ac".encode("iso-8859-1", errors="ignore")), 1)
    check("ascii-e-acute-unmappable", unmappable_count("\u00e9", "us-ascii"), 1)
    check("ascii-hello-unmappable", unmappable_count("hello", "us-ascii"), 0)

    # ------------------------------------------------------ UTF-16 (3)
    check("utf16-alias-bytes", utf16_alias("h\u00e9llo"),
          b"\xfe\xff\x00h\x00\xe9\x00l\x00l\x00o")
    check("utf16-alias-empty", utf16_alias(""), b"")
    check("utf16le-a-bytes", "a".encode("utf-16-le"), b"a\x00")

    # ------------------------------------------------ windows-1252 (1)
    check("cp1252-cafe-bytes", "caf\u00e9".encode("cp1252"), b"caf\xe9")

    # --------------------------------------------- decode replace subset (8)
    check("dec-truncated-2byte", replaced(b"\xc3", "utf-8"), "\ufffd")
    check("dec-truncated-3byte", replaced(b"\xe2\x82", "utf-8"), "\ufffd")
    check("dec-three-bad-sequences", replaced(b"\xc3\xc3\xc3", "utf-8").count("\ufffd"), 3)
    check("dec-invalid-byte", replaced(b"\xff", "utf-8"), "\ufffd")
    check("dec-valid-utf8", replaced(b"h\xc3\xa9llo", "utf-8"), "h\u00e9llo")
    check("dec-utf16le-odd", replaced(b"\xff\xfe\x00", "utf-16-le"), "\ufeff\ufffd")
    check("dec-clean-means-count-minus-one",
          "h\u00e9llo".encode("utf-8").decode("utf-8", errors="strict"), "h\u00e9llo")
    check("dec-empty", replaced(b"", "utf-8"), "")

    # ---------------------------------------------- preview truncation (3)
    check("preview-ascii-cut", ("a" * 300)[:PREVIEW_CHARS], "a" * 200)
    emoji_units = to_utf16_units("\U0001f600" * 150)
    check("preview-emoji-300-units", len(emoji_units), 300)
    check("preview-emoji-clean-cut", len(emoji_units[:PREVIEW_CHARS]), 200)
    split_units = to_utf16_units("a" + "\U0001f600" * 150)
    preview = split_units[:PREVIEW_CHARS]
    check("preview-split-len", len(preview), 200)
    check("preview-split-first", preview[0], "a")
    check("preview-split-high-surrogate", 0xD800 <= ord(preview[-1]) <= 0xDBFF, True)

    if failures:
        print("FAILED: %d of %d vectors diverge from the Kotlin expectations"
              % (failures, total))
        sys.exit(1)
    print("ALL PASS (%d vectors)" % total)


if __name__ == "__main__":
    main()
