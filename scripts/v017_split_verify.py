#!/usr/bin/env python3
"""Parity mirror of app/src/main/java/com/secretarrow/rockedit/core/
SplitSessionState.kt (v0.17.0 split session codec, Task 11-b).

Mirrors SplitSessionCodec.encode/decode/sanitize 1:1 with Python json:
ensure_ascii=False + compact separators reproduces org.json's compact
toString (escapes ", backslash and control characters; keeps non-ASCII raw),
so round trips and hand-computed expected JSON strings are byte-comparable.

Documented divergences (do not affect the vectors below):
- org.json's lenient parser also accepts single-quoted / unquoted input;
  Python json.loads does not. All corrupt vectors are garbage for BOTH
  parsers, so the all-empty outcome agrees.
- KEY ORDER: the Android runtime (AOSP org.json) serializes JSONObject in
  insertion order (uri, name, charset, text, dirty) and this mirror's dicts
  match it; the Maven org.json:json 20240303 used by the JVM unit tests
  emits HashMap hash order. The contract is deliberately ORDER-INSENSITIVE
  (decode reads by key name); a dedicated vector pins decode on reordered
  input. Verified empirically with a local org.json probe (56 checks).
- Android org.json coerces JSON null to the string "null" in optString and
  coerces "true"/"false" strings in optBoolean; the mirror models both.
- Charset support mirrors java.nio.charset.Charset.isSupported: the Java
  legality rule (letters/digits/-/_/./: only) is applied BEFORE codecs.lookup
  because Python's lookup alone is more lenient (it accepts "utf 8", Java
  throws IllegalCharsetNameException for it). All COMMON_CHARSETS names
  agree on both runtimes.

Constants are read directly from the Kotlin source (parity guard: any
drift between the Kotlin codec and this mirror fails hard).

Usage: python3 scripts/v017_split_verify.py   -> prints ALL PASS, non-zero
exit on any failure.
"""
import codecs
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
KT = os.path.join(
    ROOT,
    "app/src/main/java/com/secretarrow/rockedit/core/SplitSessionState.kt",
)


def kt_const_str(name):
    src = open(KT, encoding="utf-8").read()
    m = re.search(r'const val %s = "(.*)"' % name, src)
    if not m:
        raise SystemExit("Kotlin constant %s not found" % name)
    raw = m.group(1)
    out, i = [], 0
    while i < len(raw):
        if raw[i] == "\\" and i + 1 < len(raw):
            esc = raw[i + 1]
            out.append({"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\"}[esc])
            i += 2
        else:
            out.append(raw[i])
            i += 1
    return "".join(out)


def kt_const_int(name):
    src = open(KT, encoding="utf-8").read()
    m = re.search(r"const val %s = ([0-9_]+)" % name, src, re.M)
    if not m:
        raise SystemExit("Kotlin constant %s not found" % name)
    return int(m.group(1).replace("_", ""))


MAX_SESSION_CHARS = kt_const_int("MAX_SESSION_CHARS")
MAX_TEXT_CHARS = kt_const_int("MAX_TEXT_CHARS")
TRUNCATION_MARKER = kt_const_str("TRUNCATION_MARKER")
UNTITLED = kt_const_str("UNTITLED")
DEFAULT = "UTF-8"

# Pane state tuple: (uri, name, charset, text, dirty)


NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]*$")


def is_supported(name):
    # Models java.nio.charset.Charset.isSupported: an empty/blank name or one
    # with characters outside the legal set (letters, digits, - _ . :) raises
    # IllegalCharsetNameException in Java -> unsupported. Python's codecs
    # lookup is more lenient (it accepts e.g. "utf 8"), so the legality check
    # must come first.
    if not name or not NAME_RE.match(name):
        return False
    try:
        codecs.lookup(name)
        return True
    except LookupError:
        return False


def sanitize(state):
    uri, name, cs, text, dirty = state
    if len(text) > MAX_TEXT_CHARS:
        keep = max(0, MAX_TEXT_CHARS - len(TRUNCATION_MARKER))
        text = text[:keep] + TRUNCATION_MARKER
    return (
        uri if uri is not None and uri.strip() else None,
        name if name.strip() else UNTITLED,
        cs if is_supported(cs) else DEFAULT,
        text,
        dirty,
    )


def encode(a, b):
    if len(a[3]) + len(b[3]) > MAX_SESSION_CHARS:
        return None
    arr = [
        {"uri": a[0] or "", "name": a[1], "charset": a[2], "text": a[3], "dirty": a[4]},
        {"uri": b[0] or "", "name": b[1], "charset": b[2], "text": b[3], "dirty": b[4]},
    ]
    return json.dumps(arr, ensure_ascii=False, separators=(",", ":"))


def scalar_to_str(v):
    # Mirrors Android org.json JSON.toString coercions behind optString.
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return json.dumps(v)
    return str(v)


def opt_str(o, key, fallback=None):
    if key not in o:
        return fallback
    return scalar_to_str(o[key])


def opt_bool(o, key, fallback=False):
    if key not in o:
        return fallback
    v = o[key]
    if isinstance(v, bool):
        return v
    if isinstance(v, str):
        if v == "true":
            return True
        if v == "false":
            return False
    return fallback


def pane_from_obj(o):
    return (
        opt_str(o, "uri", ""),
        opt_str(o, "name", ""),
        opt_str(o, "charset", DEFAULT),
        opt_str(o, "text", ""),
        opt_bool(o, "dirty", False),
    )


def empty_pane():
    return (None, "", DEFAULT, "", False)


def empty_session():
    return (empty_pane(), empty_pane())


def decode(json_str):
    try:
        arr = json.loads(json_str)
    except (json.JSONDecodeError, ValueError):
        return empty_session()
    if not isinstance(arr, list) or len(arr) != 2:
        return empty_session()
    if not isinstance(arr[0], dict) or not isinstance(arr[1], dict):
        return empty_session()
    a = sanitize(pane_from_obj(arr[0]))
    b = sanitize(pane_from_obj(arr[1]))
    if len(a[3]) + len(b[3]) > MAX_SESSION_CHARS:
        # Defense against constant drift (unreachable with shipped constants).
        return empty_session()
    return (a, b)


# ------------------------------------------------------------ vectors

failures = []
total = 0


def check(name, cond, detail=""):
    global total
    total += 1
    if cond:
        print("PASS %s" % name)
    else:
        failures.append(name)
        print("FAIL %s %s" % (name, detail))


def run_vectors():
    # --- constant parity guard (hand-verified against the spec) ---
    check("const_max_session", MAX_SESSION_CHARS == 2_000_000, MAX_SESSION_CHARS)
    check("const_max_text", MAX_TEXT_CHARS == 1_000_000, MAX_TEXT_CHARS)
    check("const_marker", TRUNCATION_MARKER == "\n…[truncated]", repr(TRUNCATION_MARKER))
    check("const_marker_len", len(TRUNCATION_MARKER) == 13, len(TRUNCATION_MARKER))
    check("const_untitled", UNTITLED == "untitled", UNTITLED)

    # --- encode: exact hand-computed JSON strings (field order, compactness) ---
    out = encode((None, "n", "UTF-8", "x", True), (None, "", "UTF-8", "", False))
    exact = (
        '[{"uri":"","name":"n","charset":"UTF-8","text":"x","dirty":true},'
        '{"uri":"","name":"","charset":"UTF-8","text":"","dirty":false}]'
    )
    check("encode_exact_small", out == exact, repr(out))

    out = encode((None, "n", "UTF-8", 'a"b\\c\nd', False), (None, "", "UTF-8", "", False))
    exact = (
        '[{"uri":"","name":"n","charset":"UTF-8","text":"a\\"b\\\\c\\nd",'
        '"dirty":false},{"uri":"","name":"","charset":"UTF-8","text":"",'
        '"dirty":false}]'
    )
    check("encode_exact_escaping", out == exact, repr(out))

    out = encode((None, "u", "UTF-8", "héllo中文", False), (None, "", "UTF-8", "", False))
    exact = (
        '[{"uri":"","name":"u","charset":"UTF-8","text":"héllo中文","dirty":false},'
        '{"uri":"","name":"","charset":"UTF-8","text":"","dirty":false}]'
    )
    check("encode_exact_unicode_raw", out == exact, repr(out))

    out = encode((None, "c", "UTF-8", "a\r\nb", False), (None, "", "UTF-8", "", False))
    check("encode_escapes_crlf", 'a\\r\\nb' in out, repr(out))

    # --- encode caps (hand-computed lengths) ---
    check(
        "encode_null_over_cap",
        encode((None, "a", "UTF-8", "x" * 1_200_000, False),
               (None, "b", "UTF-8", "y" * 900_000, False)) is None,
    )
    check(
        "encode_ok_exact_cap",
        encode((None, "a", "UTF-8", "x" * 1_000_000, False),
               (None, "b", "UTF-8", "y" * 1_000_000, False)) is not None,
    )
    check(
        "encode_ok_under_cap",
        encode((None, "a", "UTF-8", "x" * 999_999, False),
               (None, "b", "UTF-8", "y" * 1_000_000, False)) is not None,
    )

    # --- encode/decode round trips ---
    a = ("content://docs/a", "a.txt", "UTF-8", 'héllo\n"q" \\ b\t中文\nz', True)
    b = (None, "second", "Shift_JIS", "x\r\ny", False)
    ra, rb = decode(encode(a, b))
    check("roundtrip_full_a", ra == a, repr(ra))
    check("roundtrip_full_b", rb == b, repr(rb))

    ra, _ = decode(encode((None, "inmem", "UTF-8", "s", False), empty_pane()))
    check("roundtrip_null_uri", ra[0] is None and ra[1] == "inmem", repr(ra))

    json_blank = encode(("", "n", "UTF-8", "", False), empty_pane())
    ra, _ = decode(json_blank)
    check(
        "blank_uri_restores_null",
        ra[0] is None and '"uri":""' in json_blank,
        repr(ra),
    )

    ra, _ = decode(encode((None, "n", "UTF-8", "a\r\nb\r\rc\n", True), empty_pane()))
    check("roundtrip_crlf", ra[3] == "a\r\nb\r\rc\n", repr(ra[3]))

    ra, _ = decode(
        encode((None, "n", "UTF-8", "code \U0001F600 fun \U0001F916", False), empty_pane()),
    )
    check("roundtrip_emoji", ra[3] == "code \U0001F600 fun \U0001F916", repr(ra[3]))

    ra, rb = decode(encode((None, "a", "UTF-8", "ta", True), (None, "b", "UTF-8", "tb", False)))
    check("roundtrip_dirty_flags", ra[4] is True and rb[4] is False, "%s %s" % (ra[4], rb[4]))

    # --- decode: structural corruption -> all-empty session ---
    for name, bad in [
        ("decode_garbage", "not json {{{"),
        ("decode_blank", "   "),
        ("decode_object_root", '{"uri":"content://x"}'),
        ("decode_length_one", '[{"name":"x"}]'),
        ("decode_length_three", "[{},{},{}]"),
        ("decode_non_object_element", '["nope", {}]'),
        ("decode_non_object_element2", '[{}, "nope"]'),
        ("decode_number_root", "42"),
        ("decode_true_root", "true"),
    ]:
        ra, rb = decode(bad)
        check(name, ra == empty_pane() and rb == empty_pane(), repr((ra, rb)))

    # --- decode: field defaults + sanitization (hand-computed) ---
    ra, _ = decode(
        '[{"dirty":true,"charset":"UTF-8","name":"ro","text":"rt","uri":"content://r"}, {}]',
    )
    check(
        "decode_order_insensitive",
        ra == ("content://r", "ro", "UTF-8", "rt", True),
        repr(ra),
    )

    ra, rb = decode("[{}, {}]")
    check(
        "decode_missing_fields",
        ra == (None, "untitled", "UTF-8", "", False) and rb == ra,
        repr((ra, rb)),
    )

    ra, _ = decode('[{"charset":"NOT-A-CHARSET","text":"kept"}, {}]')
    check(
        "decode_bad_charset_utf8_keep_text",
        ra == (None, "untitled", "UTF-8", "kept", False),
        repr(ra),
    )

    ra, _ = decode('[{"charset":""}, {}]')
    check("decode_blank_charset_utf8", ra[2] == "UTF-8", repr(ra))

    ra, _ = decode('[{"charset":"bad name"}, {}]')
    check("decode_illegal_charset_utf8", ra[2] == "UTF-8", repr(ra))

    ra, _ = decode('[{"charset":"Shift_JIS"}, {}]')
    check("decode_valid_charset_kept", ra[2] == "Shift_JIS", repr(ra))

    ra, _ = decode('[{"name": 5}, {}]')
    check("decode_numeric_name_coerced", ra[1] == "5", repr(ra))

    ra, _ = decode('[{"uri": 7}, {}]')
    check("decode_numeric_uri_coerced", ra[0] == "7", repr(ra))

    ra, _ = decode('[{"text": 12}, {}]')
    check("decode_numeric_text_coerced", ra[3] == "12", repr(ra))

    ra, _ = decode('[{"dirty": true}, {}]')
    check("decode_dirty_true", ra[4] is True, repr(ra))

    ra, _ = decode('[{"dirty": "true"}, {}]')
    check("decode_dirty_string_coerced", ra[4] is True, repr(ra))

    ra, _ = decode('[{"dirty": false}, {}]')
    check("decode_dirty_false_default", ra[4] is False, repr(ra))

    # --- decode: per-pane truncation (last-resort fail-safe) ---
    ra, _ = decode('[{"text":"%s"}, {}]' % ("x" * 1_200_000))
    check(
        "decode_truncates_oversize",
        len(ra[3]) == 1_000_000
        and ra[3].endswith(TRUNCATION_MARKER)
        and ra[3].startswith("xxx"),
        len(ra[3]),
    )
    ra, _ = decode('[{"text":"%s"}, {}]' % ("y" * 1_000_000))
    check("decode_keeps_exact_cap", ra[3] == "y" * 1_000_000, len(ra[3]))

    # --- sanitize: direct hand-computed vectors ---
    s = sanitize((None, "", "UTF-8", "", False))
    check("sanitize_blank_name", s[1] == "untitled", repr(s))
    s = sanitize((None, "   ", "UTF-8", "", False))
    check("sanitize_whitespace_name", s[1] == "untitled", repr(s))
    s = sanitize((None, "readme.md", "UTF-8", "", False))
    check("sanitize_name_verbatim", s[1] == "readme.md", repr(s))
    s = sanitize(("content://docs/abc", "n", "UTF-8", "", False))
    check("sanitize_uri_kept", s[0] == "content://docs/abc", repr(s))
    s = sanitize(("   ", "n", "UTF-8", "", False))
    check("sanitize_blank_uri_null", s[0] is None, repr(s))
    s = sanitize(("", "n", "UTF-8", "", False))
    check("sanitize_empty_uri_null", s[0] is None, repr(s))
    for bad in ["NOT-A-CHARSET", "", "bad name", "utf 8"]:
        s = sanitize((None, "n", bad, "", False))
        check(
            "sanitize_charset_%s" % (bad.strip().replace(" ", "_") or "blank"),
            s[2] == "UTF-8",
            repr(s),
        )
    s = sanitize((None, "n", "windows-1251", "", False))
    check("sanitize_charset_kept", s[2] == "windows-1251", repr(s))
    s = sanitize((None, "n", "UTF-8", "z" * (MAX_TEXT_CHARS + 5), True))
    check(
        "sanitize_truncates_exact_cap",
        len(s[3]) == MAX_TEXT_CHARS
        and s[3].endswith(TRUNCATION_MARKER)
        and s[3].startswith("z" * 10)
        and s[4] is True,
        len(s[3]),
    )
    s = sanitize((None, "n", "UTF-8", "z" * MAX_TEXT_CHARS, False))
    check(
        "sanitize_cap_untouched",
        s[3] == "z" * MAX_TEXT_CHARS and not s[3].endswith(TRUNCATION_MARKER),
        len(s[3]),
    )
    s = sanitize((None, "n", "UTF-8", "ok", True))
    check("sanitize_dirty_passthrough", s[4] is True, repr(s))

    # --- mirror self-test: drifting constants must be caught (meta) ---
    check("caps_consistent", 2 * MAX_TEXT_CHARS == MAX_SESSION_CHARS, None)


def main():
    print(
        "Mirror constants: MAX_SESSION_CHARS=%d MAX_TEXT_CHARS=%d "
        "MARKER=%r UNTITLED=%r" % (MAX_SESSION_CHARS, MAX_TEXT_CHARS, TRUNCATION_MARKER, UNTITLED)
    )
    run_vectors()
    if failures:
        print("FAILED: %d of %d vectors" % (len(failures), total))
        sys.exit(1)
    print("ALL PASS (%d vectors)" % total)


if __name__ == "__main__":
    main()
