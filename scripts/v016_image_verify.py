#!/usr/bin/env python3
"""1:1 Python mirror of core/ImageExportPlanner.kt (v0.16.0) to verify the
Kotlin planner behavior before CI (same technique as scripts/v014_pdf_verify.py).

Offsets use UTF-16 code units like Kotlin String indexes, so astral-plane
characters (emoji) count as two units; text is therefore expanded into a
surrogate-aware unit list before any offset math happens.

The script also parity-checks the planner constants and both palettes
directly against the Kotlin source, so silent drift between this mirror and
core/ImageExportPlanner.kt fails loudly here."""
import os
import re
import sys

MIN_WRAP_COLUMNS, MAX_WRAP_COLUMNS = 20, 200
MIN_FONT_SP, MAX_FONT_SP = 8, 40

KEYWORD, STRING, COMMENT, NUMBER = "KEYWORD", "STRING", "COMMENT", "NUMBER"
PLAIN, TITLE = "PLAIN", "TITLE"

ROLE_FOR_TOKEN = {KEYWORD: KEYWORD, STRING: STRING, COMMENT: COMMENT, NUMBER: NUMBER}

LIGHT = {
    "background": 0xFFFAF7F2,
    "foreground": 0xFF2E3440,
    "lineNumber": 0xFF9C948A,
    "keyword": 0xFF6A3AB2,
    "string": 0xFF2E7D32,
    "comment": 0xFF8A8A8A,
    "number": 0xFFB25000,
    "title": 0xFF6E655A,
}
DARK = {
    "background": 0xFF1E1E2E,
    "foreground": 0xFFE6E6F0,
    "lineNumber": 0xFF6C7086,
    "keyword": 0xFFCBA6F7,
    "string": 0xFFA6E3A1,
    "comment": 0xFF7F849C,
    "number": 0xFFFAB387,
    "title": 0xFFB8B8CC,
}

KT_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..",
    "app/src/main/java/com/secretarrow/rockedit/core/ImageExportPlanner.kt",
)

# Mirror of the Kotlin keyword set in SyntaxRegistry.kt.
KOTLIN_KEYWORDS = {
    "package", "import", "class", "interface", "fun", "object", "val", "var",
    "if", "else", "when", "while", "do", "for", "return", "break", "continue",
    "in", "is", "as", "true", "false", "null", "this", "super", "private",
    "public", "protected", "internal", "final", "open", "abstract", "sealed",
    "data", "enum", "companion", "init", "override", "suspend", "lateinit",
    "by", "try", "catch", "finally", "throw", "typealias", "operator",
    "inline", "reified", "const", "expect", "actual", "where", "out", "vararg",
}


def to_units(text):
    """Expands a Python string into UTF-16 code units (Kotlin Char list)."""
    units = []
    for ch in text:
        cp = ord(ch)
        if cp > 0xFFFF:
            v = cp - 0x10000
            units.append(chr(0xD800 + (v >> 10)))
            units.append(chr(0xDC00 + (v & 0x3FF)))
        else:
            units.append(ch)
    return units


def u(units):
    return "".join(units)


def _surrogate(c):
    return 0xD800 <= ord(c) <= 0xDFFF


def _digit(c):
    return not _surrogate(c) and "0" <= c <= "9"


def _letter(c):
    if _surrogate(c):
        return False
    return "a" <= c <= "z" or "A" <= c <= "Z" or c == "_" or c == "$"


def _alnum(c):
    return _letter(c) or _digit(c)


def tokenize(units):
    """Mirror of SyntaxTokenizer.tokenize for the registered Kotlin language."""
    tokens = []
    n = len(units)
    i = 0
    while i < n:
        if units[i] == "/" and i + 1 < n and units[i + 1] == "/":
            end = i
            while end < n and units[end] != "\n":
                end += 1
            tokens.append((COMMENT, i, end))
            i = end
            continue
        if units[i] == "/" and i + 1 < n and units[i + 1] == "*":
            close = -1
            j = i + 2
            while j + 1 < n:
                if units[j] == "*" and units[j + 1] == "/":
                    close = j
                    break
                j += 1
            if close < 0:
                tokens.append((COMMENT, i, n))
                i = n
            else:
                tokens.append((COMMENT, i, close + 2))
                i = close + 2
            continue
        c = units[i]
        if c in "\"'":
            j = i + 1
            while j < n:
                ch = units[j]
                if ch == "\\" and j + 1 < n:
                    j += 2
                    continue
                if ch == c:
                    j += 1
                    break
                if ch == "\n":
                    break
                j += 1
            tokens.append((STRING, i, j))
            i = j
            continue
        if _digit(c):
            j = i
            while j < n and (_alnum(units[j]) or units[j] in "._"):
                j += 1
            tokens.append((NUMBER, i, j))
            i = j
            continue
        if _letter(c):
            j = i
            while j < n and (_alnum(units[j]) or units[j] in "_$"):
                j += 1
            if u(units[i:j]) in KOTLIN_KEYWORDS:
                tokens.append((KEYWORD, i, j))
            i = j
            continue
        i += 1
    return tokens


def preprocess(text):
    """Byte-identical to ImageExportPlanner.preprocess / PdfExportPlanner.preprocess."""
    return text.replace("\r\n", "\n").replace("\r", "\n").replace("\t", "    ")


def clamp_options(opts):
    """Fail-safe clamp of every numeric knob (mirror of plan() step 1)."""
    return {
        "darkTheme": bool(opts.get("darkTheme", False)),
        "lineNumbers": bool(opts.get("lineNumbers", True)),
        "wrapColumns": max(MIN_WRAP_COLUMNS, min(MAX_WRAP_COLUMNS, opts.get("wrapColumns", 80))),
        "maxLines": max(1, opts.get("maxLines", 5000)),
        "fontSizeSp": max(MIN_FONT_SP, min(MAX_FONT_SP, opts.get("fontSizeSp", 14))),
        "paddingSp": max(0, opts.get("paddingSp", 16)),
        "title": opts.get("title", ""),
    }


def merge_adjacent(segments):
    out = []
    for text, role in segments:
        if out and out[-1][1] == role:
            out[-1] = (out[-1][0] + text, role)
        else:
            out.append((text, role))
    return out


def split_nl(units):
    """split('\\n') that keeps trailing empty strings, like Kotlin."""
    lines, cur = [], []
    for unit in units:
        if unit == "\n":
            lines.append(cur)
            cur = []
        else:
            cur.append(unit)
    lines.append(cur)
    return lines


def plan(text, tokens, opts):
    """Mirror of ImageExportPlanner.plan: ("OK", plan) or ("FAIL", code, message, actual)."""
    eff = clamp_options(opts)
    units = to_units(text)
    if not text or text.isspace():
        return ("FAIL", "EMPTY", "text is blank; there is nothing to render into an image", -1)
    for index, (_, start, end) in enumerate(tokens):
        if start < 0 or end > len(units) or start > end:
            return ("FAIL", "TOKEN_MISMATCH",
                    "token[%d] range [%d, %d) does not fit text length %d; tokens must come "
                    "from tokenize(preprocess(text)) with the same text passed to plan()"
                    % (index, start, end, len(units)), -1)
    raw_lines = split_nl(units)
    if len(raw_lines) > eff["maxLines"]:
        return ("FAIL", "TOO_LARGE",
                "document has %d source lines but image export is capped at %d; split the "
                "file or raise the cap consciously" % (len(raw_lines), eff["maxLines"]),
                len(raw_lines))
    ordered = sorted(tokens, key=lambda t: (t[1], t[2]))
    lines = []
    if eff["title"].strip():
        lines.append((None, [(eff["title"], TITLE)]))
    wrap = eff["wrapColumns"]
    cursor = 0
    offset = 0  # absolute start of the current source line in `units`
    for index, raw in enumerate(raw_lines):
        number = index + 1 if eff["lineNumbers"] else None
        line_end = offset + len(raw)
        chunk_start = offset
        while True:
            chunk_end = line_end if line_end - chunk_start <= wrap else chunk_start + wrap
            while cursor < len(ordered) and ordered[cursor][2] <= chunk_start:
                cursor += 1
            segments = []
            pen = chunk_start
            i = cursor
            while i < len(ordered) and ordered[i][1] < chunk_end:
                tok_type, tok_start, tok_end = ordered[i]
                i += 1
                start = max(tok_start, chunk_start)
                end = min(tok_end, chunk_end)
                if end <= start:
                    continue
                if start > pen:
                    segments.append((u(units[pen:start]), PLAIN))
                    pen = start
                if end > pen:
                    segments.append((u(units[pen:end]), ROLE_FOR_TOKEN[tok_type]))
                    pen = end
            if pen < chunk_end:
                segments.append((u(units[pen:chunk_end]), PLAIN))
            if not segments:
                segments.append(("", PLAIN))
            lines.append((number, merge_adjacent(segments)))
            chunk_start = chunk_end
            if chunk_start >= line_end:
                break
        offset = line_end + 1
    return ("OK", {"lines": lines, "palette": DARK if eff["darkTheme"] else LIGHT,
                   "options": eff, "sourceLineCount": len(raw_lines)})


def linetext(line):
    return "".join(t for t, _ in line[1])


def replay_invariants(result, text, opts):
    """Independent replay of the documented rules (mirror of the Kotlin test
    helper), in UTF-16 units so astral-plane characters behave like Kotlin."""
    eff = clamp_options(opts)
    wrap = eff["wrapColumns"]
    units = to_units(text)
    expected = []
    if eff["title"].strip():
        expected.append((None, eff["title"]))
    for line_no, raw in enumerate(split_nl(units), 1):
        number = line_no if eff["lineNumbers"] else None
        start = 0
        while True:
            end = min(start + wrap, len(raw))
            expected.append((number, u(raw[start:end])))
            start = end
            if start >= len(raw):
                break
    lines = result["lines"]
    problems = []
    if len(lines) != len(expected):
        problems.append("row count %d != %d" % (len(lines), len(expected)))
    for i, (number, chunk) in enumerate(expected):
        if i >= len(lines):
            break
        if lines[i][0] != number:
            problems.append("row %d number %r != %r" % (i, lines[i][0], number))
        if linetext(lines[i]) != chunk:
            problems.append("row %d concat %r != %r" % (i, linetext(lines[i]), chunk))
        if not lines[i][1]:
            problems.append("row %d has no segments" % i)
        roles = [r for _, r in lines[i][1]]
        for j in range(1, len(roles)):
            if roles[j - 1] == roles[j]:
                problems.append("row %d adjacent roles equal at %d" % (i, j))
    return problems


def kotlin_parity_failures():
    """Compares constants and palettes against core/ImageExportPlanner.kt."""
    src = open(KT_PATH, encoding="utf-8").read()
    problems = []
    for name, expected in [("MIN_WRAP_COLUMNS", MIN_WRAP_COLUMNS),
                           ("MAX_WRAP_COLUMNS", MAX_WRAP_COLUMNS),
                           ("MIN_FONT_SP", MIN_FONT_SP),
                           ("MAX_FONT_SP", MAX_FONT_SP)]:
        m = re.search(r"const val %s = (\d+)" % name, src)
        if not m or int(m.group(1)) != expected:
            problems.append("constant %s drifted from the Python mirror" % name)
    for palette_name, expected in [("LIGHT", LIGHT), ("DARK", DARK)]:
        m = re.search(r"val %s: ImagePalette =(.*?)\n        \)" % palette_name, src, re.S)
        if not m:
            problems.append("palette %s not found in Kotlin source" % palette_name)
            continue
        found = dict(re.findall(r"(\w+) = (0x[0-9A-Fa-f]+)L", m.group(1)))
        for key, value in expected.items():
            if key not in found or int(found[key], 16) != value:
                problems.append("palette %s.%s drifted" % (palette_name, key))
    return problems


FAILURES = []
CHECKS = 0


def check(name, actual, expected):
    global CHECKS
    CHECKS += 1
    if actual != expected:
        FAILURES.append("%s:\n  expected: %r\n  actual:   %r" % (name, expected, actual))


def check_true(name, cond, detail=""):
    global CHECKS
    CHECKS += 1
    if not cond:
        FAILURES.append("%s %s" % (name, detail))


def expect_ok(name, text, tokens, opts):
    status, payload = plan(text, tokens, opts)
    check_true(name + " ok", status == "OK", "got %r" % (status,))
    return payload


def expect_fail(name, text, tokens, opts, code):
    status, got_code, message, actual = plan(text, tokens, opts)
    check(name + " status", status, "FAIL")
    check(name + " code", got_code, code)
    return message, actual


def main():
    # ------------------------------------------------- Kotlin source parity
    for problem in kotlin_parity_failures():
        FAILURES.append("parity: " + problem)

    # ----------------------------------------------------------- preprocess
    check("preprocess_crlf", preprocess("a\r\nb\r\nc"), "a\nb\nc")
    check("preprocess_lone_cr", preprocess("a\rb\rc"), "a\nb\nc")
    check("preprocess_tab", preprocess("\tca\tt"), "    ca    t")
    check("preprocess_mixed", preprocess("val a = 1\r\n\t// mixed\rc\rd\r\n"),
          "val a = 1\n    // mixed\nc\nd\n")
    check("preprocess_clean", preprocess("fun main() {\n    return\n}\n"),
          "fun main() {\n    return\n}\n")

    # ---------------------------------------------------------------- empty
    msg, actual = expect_fail("empty_text", "", [], {}, "EMPTY")
    check_true("empty_text message", "blank" in msg, repr(msg))
    check("empty_text actual", actual, -1)
    expect_fail("blank_spaces", "   ", [], {}, "EMPTY")
    expect_fail("blank_mixed", " \n\t ", [], {}, "EMPTY")

    # --------------------------------------------------------- token guards
    msg, _ = expect_fail("token_start_negative", "abc", [(STRING, -1, 1)], {}, "TOKEN_MISMATCH")
    check_true("token_start_negative msg", "token[0]" in msg and "[-1, 1)" in msg, repr(msg))
    msg, _ = expect_fail("token_end_beyond", "abc", [(KEYWORD, 0, 4)], {}, "TOKEN_MISMATCH")
    check_true("token_end_beyond msg", "3" in msg, repr(msg))
    expect_fail("token_inverted", "abc", [(STRING, 2, 1)], {}, "TOKEN_MISMATCH")
    msg, _ = expect_fail("token_index_reported", "val x",
                         [(KEYWORD, 0, 3), (NUMBER, 4, 9)], {}, "TOKEN_MISMATCH")
    check_true("token_index_reported msg", "token[1]" in msg, repr(msg))
    expect_fail("token_huge_end", "abc", [(COMMENT, 1, 2 ** 31 - 1)], {}, "TOKEN_MISMATCH")

    # -------------------------------------------------------------- line cap
    msg, actual = expect_fail("too_large", "a\nb\nc\nd", [], {"maxLines": 3}, "TOO_LARGE")
    check("too_large actual", actual, 4)
    check_true("too_large msg", "4" in msg and "3" in msg, repr(msg))
    at_cap = expect_ok("at_max_lines", "a\nb\nc\nd", [], {"maxLines": 4})
    check("at_max_lines count", at_cap["sourceLineCount"], 4)
    expect_fail("max_lines_clamp_zero", "a\nb", [], {"maxLines": 0}, "TOO_LARGE")
    expect_fail("max_lines_clamp_negative", "a\nb", [], {"maxLines": -5}, "TOO_LARGE")

    # --------------------------------------------------------- option clamps
    clamped = expect_ok("wrap_clamp_low", "x" * 45, [], {"wrapColumns": 5})
    check("wrap_clamp_low rows", [len(linetext(l)) for l in clamped["lines"]], [20, 20, 5])
    check("wrap_clamp_low echo", clamped["options"]["wrapColumns"], 20)
    clamped = expect_ok("wrap_clamp_high", "x" * 450, [], {"wrapColumns": 999})
    check("wrap_clamp_high rows", [len(linetext(l)) for l in clamped["lines"]], [200, 200, 50])
    check("wrap_clamp_high echo", clamped["options"]["wrapColumns"], 200)
    kept = expect_ok("wrap_in_range", "x" * 45, [], {"wrapColumns": 30})
    check("wrap_in_range rows", [len(linetext(l)) for l in kept["lines"]], [30, 15])
    check("wrap_in_range echo", kept["options"]["wrapColumns"], 30)
    check("font_clamp_low",
          expect_ok("font_clamp_low", "ok", [], {"fontSizeSp": 1})["options"]["fontSizeSp"], 8)
    check("font_clamp_high",
          expect_ok("font_clamp_high", "ok", [], {"fontSizeSp": 99})["options"]["fontSizeSp"], 40)
    check("padding_clamp",
          expect_ok("padding_clamp", "ok", [], {"paddingSp": -7})["options"]["paddingSp"], 0)
    echo = expect_ok("echo_clamped", "ok", [],
                     {"wrapColumns": 5, "fontSizeSp": 1, "paddingSp": -3, "maxLines": 0})
    check("echo_clamped",
          (echo["options"]["wrapColumns"], echo["options"]["fontSizeSp"],
           echo["options"]["paddingSp"], echo["options"]["maxLines"]), (20, 8, 0, 1))
    check_true("echo_palette_non_null", isinstance(echo["palette"], dict))

    # ---------------------------------------------------- role mapping (manual)
    line = expect_ok("role_keyword", "abcdef", [(KEYWORD, 0, 3)], {})["lines"][0]
    check("role_keyword", line[1], [("abc", KEYWORD), ("def", PLAIN)])
    line = expect_ok("role_string", "ab\"cd\"ef", [(STRING, 2, 6)], {})["lines"][0]
    check("role_string", line[1], [("ab", PLAIN), ("\"cd\"", STRING), ("ef", PLAIN)])
    line = expect_ok("role_comment", "// hi", [(COMMENT, 0, 5)], {})["lines"][0]
    check("role_comment", line[1], [("// hi", COMMENT)])
    line = expect_ok("role_number", "a42", [(NUMBER, 1, 3)], {})["lines"][0]
    check("role_number", line[1], [("a", PLAIN), ("42", NUMBER)])

    # --------------------------------------------------------------- merging
    merged = expect_ok("adjacent_merge", "abcdef",
                       [(KEYWORD, 0, 3), (KEYWORD, 3, 6)], {})["lines"][0]
    check("adjacent_merge", merged[1], [("abcdef", KEYWORD)])
    overlap = expect_ok("overlap_earlier_wins", "abcdefghij",
                        [(KEYWORD, 0, 5), (STRING, 3, 8)], {})["lines"][0]
    check("overlap_earlier_wins", overlap[1],
          [("abcde", KEYWORD), ("fgh", STRING), ("ij", PLAIN)])

    # ------------------------------------------------------------------ wrap
    wrapped = expect_ok("wrap_chunks", "x" * 45, [], {"wrapColumns": 20})
    check("wrap_chunks lens", [len(linetext(l)) for l in wrapped["lines"]], [20, 20, 5])
    exact = expect_ok("wrap_exact_boundary", "y" * 20, [], {"wrapColumns": 20})
    check("wrap_exact_boundary rows", len(exact["lines"]), 1)
    check("wrap_exact_boundary text", linetext(exact["lines"][0]), "y" * 20)
    check("wrap_exact_boundary number", exact["lines"][0][0], 1)
    digits = expect_ok("wrap_repeat_numbers", "012345678901234567890123456789", [],
                       {"wrapColumns": 20})
    check("wrap_repeat_numbers texts", [linetext(l) for l in digits["lines"]],
          ["01234567890123456789", "0123456789"])
    check("wrap_repeat_numbers numbers", [l[0] for l in digits["lines"]], [1, 1])
    crossing = expect_ok("token_crossing_wrap", "abcdefghijklmnopqrstuvwxyz0123",
                         [(KEYWORD, 3, 25)], {"wrapColumns": 20})
    check("token_crossing_wrap row0", crossing["lines"][0][1],
          [("abc", PLAIN), ("defghijklmnopqrst", KEYWORD)])
    check("token_crossing_wrap row1", crossing["lines"][1][1],
          [("uvwxy", KEYWORD), ("z0123", PLAIN)])
    check("token_crossing_wrap numbers", [l[0] for l in crossing["lines"]], [1, 1])

    # ----------------------------------------------------------------- title
    titled = expect_ok("title_first", "abcdef", [], {"title": "main.kt"})
    check("title_first row0", titled["lines"][0], (None, [("main.kt", TITLE)]))
    check("title_first row1 number", titled["lines"][1][0], 1)
    check("title_first source count", titled["sourceLineCount"], 1)
    blank = expect_ok("blank_title_suppressed", "abcdef", [], {"title": "  "})
    check("blank_title_suppressed rows", len(blank["lines"]), 1)
    check_true("blank_title_suppressed no TITLE",
               all(r != TITLE for _, r in blank["lines"][0][1]))
    notitle = expect_ok("title_absent_default", "abcdef", [], {})
    check("title_absent_default rows", len(notitle["lines"]), 1)
    nonum = expect_ok("title_line_numbers_false", "abcdef", [],
                      {"lineNumbers": False, "title": "notes"})
    check("title_line_numbers_false numbers", [l[0] for l in nonum["lines"]], [None, None])

    # ---------------------------------------------------------- line numbers
    nonum = expect_ok("line_numbers_false", "a\nbb\nccc", [], {"lineNumbers": False})
    check("line_numbers_false numbers", [l[0] for l in nonum["lines"]], [None, None, None])
    check("line_numbers_false texts", [linetext(l) for l in nonum["lines"]], ["a", "bb", "ccc"])
    one_based = expect_ok("numbers_one_based", "a\nb\nc", [], {})
    check("numbers_one_based", [l[0] for l in one_based["lines"]], [1, 2, 3])

    # --------------------------------------------------------------- palette
    check("palette_light", expect_ok("palette_light", "ok", [], {})["palette"], LIGHT)
    check("palette_dark", expect_ok("palette_dark", "ok", [], {"darkTheme": True})["palette"], DARK)

    # ------------------------------------------------------ newline semantics
    trailing = expect_ok("trailing_newline", "a\n", [], {})
    check("trailing_newline count", trailing["sourceLineCount"], 2)
    check("trailing_newline texts", [linetext(l) for l in trailing["lines"]], ["a", ""])
    check("trailing_newline numbers", [l[0] for l in trailing["lines"]], [1, 2])
    check("trailing_newline last segs", trailing["lines"][1][1], [("", PLAIN)])
    middle = expect_ok("empty_middle_line", "a\n\nb", [], {})
    check("empty_middle_line numbers", [l[0] for l in middle["lines"]], [1, 2, 3])
    check("empty_middle_line segs", middle["lines"][1][1], [("", PLAIN)])

    # --------------------------------------------------- tokenizer-driven flow
    tok = expect_ok("tokenizer_line", "val n = 12 // ok",
                    tokenize(to_units("val n = 12 // ok")), {})
    check("tokenizer_line segs", tok["lines"][0][1],
          [("val", KEYWORD), (" n = ", PLAIN), ("12", NUMBER), (" ", PLAIN), ("// ok", COMMENT)])
    str_text = "val s = \"0123456789ABCDEF\""
    strwrap = expect_ok("tokenizer_string_wrap", str_text,
                        tokenize(to_units(str_text)), {"wrapColumns": 20})
    check("tokenizer_string_wrap row0", strwrap["lines"][0][1],
          [("val", KEYWORD), (" s = ", PLAIN), ("\"0123456789A", STRING)])
    check("tokenizer_string_wrap row1", strwrap["lines"][1][1], [("BCDEF\"", STRING)])
    check("tokenizer_string_wrap numbers", [l[0] for l in strwrap["lines"]], [1, 1])
    crlf = preprocess("val a = 1\r\n// done")
    crlf_plan = expect_ok("crlf_flow", crlf, tokenize(to_units(crlf)), {})
    check("crlf_flow row0", crlf_plan["lines"][0][1],
          [("val", KEYWORD), (" a = ", PLAIN), ("1", NUMBER)])
    check("crlf_flow row1", crlf_plan["lines"][1][1], [("// done", COMMENT)])
    check("crlf_flow numbers", [l[0] for l in crlf_plan["lines"]], [1, 2])
    emoji = expect_ok("emoji_no_shift", "x \U0001F680 val",
                      tokenize(to_units("x \U0001F680 val")), {})
    # The plan output is joined from UTF-16 units, so the emoji appears in its
    # surrogate-pair form (two Python chars), exactly like a Kotlin substring.
    check("emoji_no_shift segs", emoji["lines"][0][1],
          [("x \ud83d\ude80 ", PLAIN), ("val", KEYWORD)])

    # ------------------------------------------------------------ invariants
    rep_text = "val header = 1\n\"0123456789ABCDEF\" val\n\n42 // tail\n"
    rep_opts = {"wrapColumns": 20, "title": "snippet"}
    rep = expect_ok("invariants_representative", rep_text, tokenize(to_units(rep_text)), rep_opts)
    check_true("invariants_representative", not replay_invariants(rep, rep_text, rep_opts),
               str(replay_invariants(rep, rep_text, rep_opts)))
    nonum_text = "aaaaaaa\nb\n"
    nonum_opts = {"lineNumbers": False, "wrapColumns": 20}
    nonum_plan = expect_ok("invariants_no_numbers", nonum_text,
                           tokenize(to_units(nonum_text)), nonum_opts)
    check_true("invariants_no_numbers", not replay_invariants(nonum_plan, nonum_text, nonum_opts),
               str(replay_invariants(nonum_plan, nonum_text, nonum_opts)))
    emoji_text = u(to_units("b" * 19 + "\U0001F680" + "c" * 3))
    emoji_wrap = expect_ok("invariants_emoji_wrap", emoji_text, [], {"wrapColumns": 20})
    check_true("invariants_emoji_wrap",
               not replay_invariants(emoji_wrap, emoji_text, {"wrapColumns": 20}),
               str(replay_invariants(emoji_wrap, emoji_text, {"wrapColumns": 20})))
    # The 20-unit cut lands inside the emoji pair: row 0 ends with the high
    # surrogate, row 1 starts with the low surrogate (mirrors Kotlin exactly).
    check("emoji_wrap row0 text", linetext(emoji_wrap["lines"][0]), "b" * 19 + "\ud83d")
    check("emoji_wrap row1 text", linetext(emoji_wrap["lines"][1]), "\ude80ccc")

    # ------------------------------------------------------------- verdict
    if FAILURES:
        for failure in FAILURES:
            print("FAIL " + failure)
        print("%d FAILURE(S)" % len(FAILURES))
        sys.exit(1)
    print("ALL PASS (%d vectors)" % CHECKS)


if __name__ == "__main__":
    main()
