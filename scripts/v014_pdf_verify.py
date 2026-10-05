#!/usr/bin/env python3
"""1:1 Python mirror of core/PdfExportPlanner.kt (v0.14.0) to verify the
Kotlin test expectations before CI (same technique as scripts/v013_*_verify.py).

Offsets use UTF-16 code units like Kotlin String indexes, so astral-plane
characters (emoji) count as two units; text is therefore expanded into a
surrogate-aware unit list before any offset math happens."""
import sys

DEFAULT_CHARS_PER_LINE = 88
LINES_PER_PAGE = 44
MAX_EXPORT_LINES = 100_000

KEYWORD, STRING, COMMENT, NUMBER = "KEYWORD", "STRING", "COMMENT", "NUMBER"

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


def _find_seq(units, seq, start, end):
    m, n = len(seq), len(units)
    i = start
    while i + m <= end:
        if units[i:i + m] == list(seq):
            return i
        i += 1
    return -1


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
            close = _find_seq(units, "*/", i + 2, n)
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
    return text.replace("\r\n", "\n").replace("\r", "\n").replace("\t", "    ")


def append_line_chunks(out, units, line_start, line_end, first_number,
                       tokens, token_cursor, colored, chars_per_line):
    cursor = token_cursor
    number = first_number
    chunk_start = line_start
    while True:  # do-while mirror
        if line_end - chunk_start <= chars_per_line:
            chunk_end = line_end
        else:
            chunk_end = chunk_start + chars_per_line
        while cursor < len(tokens) and tokens[cursor][2] <= chunk_start:
            cursor += 1
        segments = []
        if colored:
            pen = chunk_start
            i = cursor
            while i < len(tokens) and tokens[i][1] < chunk_end:
                ttype, ts, te = tokens[i]
                i += 1
                s = max(ts, chunk_start)
                e = min(te, chunk_end)
                if e <= s:
                    continue
                if s > pen:
                    segments.append((None, u(units[pen:s])))
                    pen = s
                if e > pen:
                    segments.append((ttype, u(units[pen:e])))
                    pen = e
            if pen < chunk_end:
                segments.append((None, u(units[pen:chunk_end])))
        else:
            segments.append((None, u(units[chunk_start:chunk_end])))
        if not segments:
            segments.append((None, ""))
        out.append((number, segments))
        number = None
        chunk_start = chunk_end
        if chunk_start >= line_end:
            break
    return cursor


def plan(units, tokens, line_numbers=True, colored=True,
         chars_per_line=DEFAULT_CHARS_PER_LINE):
    """Mirror of PdfExportPlanner.plan: ("SUCCESS", pages, count) |
    ("TOO_LARGE", actual) | ("TOKEN_MISMATCH", index, start, end, length)."""
    if chars_per_line <= 0:
        raise ValueError("charsPerLine must be positive, was: %d" % chars_per_line)
    for idx, tok in enumerate(tokens):
        if tok[1] < 0 or tok[2] > len(units) or tok[1] > tok[2]:
            return ("TOKEN_MISMATCH", idx, tok[1], tok[2], len(units))
    raw = u(units).split("\n")
    if len(raw) > MAX_EXPORT_LINES:
        return ("TOO_LARGE", len(raw))
    ordered = sorted(tokens, key=lambda t: (t[1], t[2]))
    flat = []
    cursor = 0
    line_start = 0
    for index, raw_line in enumerate(raw):
        line_end = line_start + len(raw_line)
        first_number = index + 1 if line_numbers else None
        cursor = append_line_chunks(flat, units, line_start, line_end,
                                    first_number, ordered, cursor, colored,
                                    chars_per_line)
        line_start = line_end + 1
    pages = [flat[i:i + LINES_PER_PAGE] for i in range(0, len(flat), LINES_PER_PAGE)]
    return ("SUCCESS", pages, len(raw))


def plan_text(text, **kw):
    return plan(to_units(text), tokenize(to_units(text)), **kw)


def plan_tok(text, **kw):
    units = to_units(text)
    return plan(units, tokenize(units), **kw)


def flat_lines(result):
    return [line for page in result[1] for line in page]


def line_text(line):
    return "".join(seg[1] for seg in line[1])


def line_roles(line):
    return [seg[0] for seg in line[1]]


def assert_invariants(result, text, line_numbers=True, chars_per_line=DEFAULT_CHARS_PER_LINE):
    # text must already be in UTF-16 unit space (u(to_units(...))) when it
    # contains astral-plane characters, so the replay matches Kotlin indexing.
    expected = []
    line_no = 0
    for raw in text.split("\n"):
        line_no += 1
        if not raw:
            expected.append((line_no if line_numbers else None, ""))
            continue
        start, first = 0, True
        while True:
            end = min(start + chars_per_line, len(raw))
            expected.append((line_no if line_numbers and first else None, raw[start:end]))
            first = False
            start = end
            if start >= len(raw):
                break
    flat = flat_lines(result)
    if len(flat) != len(expected):
        return False
    for i, (number, chunk) in enumerate(expected):
        if flat[i][0] != number or line_text(flat[i]) != chunk or not flat[i][1]:
            return False
    return result[2] == len(text.split("\n"))


FAIL = []


def check(name, cond):
    if not cond:
        FAIL.append(name)
    print(("PASS " if cond else "FAIL ") + name)


def seg(role, text):
    return (role, text)


# --- test vectors (mirror PdfExportPlannerTest.kt) ---
check("preprocessNormalizesCrLfAndLoneCr", preprocess("a\r\nb\rc\nd") == "a\nb\nc\nd")
check("preprocessExpandsTabsToFourSpaces", preprocess("\tca\tt") == "    ca    t")
check("preprocessKeepsCleanTextIdentical",
      preprocess("fun main() {\n    return\n}\n") == "fun main() {\n    return\n}\n")
check("preprocessKeepsEmptyTextEmpty", preprocess("") == "")

r = plan(to_units(""), [])
line = flat_lines(r)[0]
check("emptyTextYieldsSinglePageSingleEmptyLine",
      r[0] == "SUCCESS" and len(r[1]) == 1 and r[2] == 1
      and len(flat_lines(r)) == 1 and line[0] == 1 and line[1] == [(None, "")])

r = plan(to_units("hello world"), [])
check("shortPlainLineIsSinglePlainSegment",
      flat_lines(r)[0][1] == [(None, "hello world")] and flat_lines(r)[0][0] == 1)

r = plan(to_units("a\nb\nc"), [])
check("multilineLinesAreNumberedOneBased", [l[0] for l in flat_lines(r)] == [1, 2, 3])

r = plan(to_units("a\n"), [])
check("trailingNewlineCountsEmptyLastLine",
      r[2] == 2 and [l[0] for l in flat_lines(r)] == [1, 2]
      and [line_text(l) for l in flat_lines(r)] == ["a", ""])

r = plan(to_units("a\rb"), [])
check("planDoesNotPreprocessByContract",
      r[2] == 1 and line_text(flat_lines(r)[0]) == "a\rb")

r = plan_tok("val x")
check("keywordSegmentGetsKeywordRole",
      flat_lines(r)[0][1] == [seg(KEYWORD, "val"), seg(None, " x")])

r = plan_tok("val s = \"hi\"")
check("stringSegmentGetsStringRole",
      flat_lines(r)[0][1] == [seg(KEYWORD, "val"), seg(None, " s = "), seg(STRING, "\"hi\"")])

r = plan_tok("// header")
check("lineCommentSegmentGetsCommentRole", flat_lines(r)[0][1] == [seg(COMMENT, "// header")])

r = plan_tok("/* box */ val")
check("blockCommentSegmentGetsCommentRole",
      flat_lines(r)[0][1] == [seg(COMMENT, "/* box */"), seg(None, " "), seg(KEYWORD, "val")])

r = plan_tok("x = 42")
check("numberSegmentGetsNumberRole",
      flat_lines(r)[0][1] == [seg(None, "x = "), seg(NUMBER, "42")])

r = plan_tok("val n = 12 // ok")
check("multipleTokensInOneLineAreOrdered",
      flat_lines(r)[0][1] == [seg(KEYWORD, "val"), seg(None, " n = "), seg(NUMBER, "12"),
                              seg(None, " "), seg(COMMENT, "// ok")])

r = plan_tok("val a\n// done")
check("rolesStayInTheirOwnLines",
      line_roles(flat_lines(r)[0]) == [KEYWORD, None] and line_roles(flat_lines(r)[1]) == [COMMENT])

r = plan_tok("1ab")
check("numberWordIsOneTokenWithoutPlainGap", flat_lines(r)[0][1] == [seg(NUMBER, "1ab")])

r = plan_text("x" * 200, chars_per_line=88)
lines = flat_lines(r)
check("longPlainLineWrapsAtCharsPerLine",
      len(lines) == 3 and [l[0] for l in lines] == [1, None, None]
      and [len(line_text(l)) for l in lines] == [88, 88, 24]
      and all(l[1] == [(None, line_text(l))] for l in lines))

r = plan_text("0123456789", chars_per_line=4)
lines = flat_lines(r)
check("wrappedContinuationLinesHaveNullNumber",
      [l[0] for l in lines] == [1, None, None]
      and [line_text(l) for l in lines] == ["0123", "4567", "89"])

r = plan_tok("val s = \"0123456789ABCDEF\"", chars_per_line=12)
lines = flat_lines(r)
check("tokenCrossingWrapIsSplitAcrossChunks",
      len(lines) == 3
      and lines[0][1] == [seg(KEYWORD, "val"), seg(None, " s = "), seg(STRING, "\"012")]
      and lines[1][1] == [seg(STRING, "3456789ABCDE")]
      and lines[2][1] == [seg(STRING, "F\"")]
      and [l[0] for l in lines] == [1, None, None])

r = plan(to_units("abcdefghijklmnop"), [(KEYWORD, 3, 15)], chars_per_line=10)
lines = flat_lines(r)
check("manualTokenCrossingWrapSplitsWithPlainTail",
      len(lines) == 2
      and lines[0][1] == [seg(None, "abc"), seg(KEYWORD, "defghij")]
      and lines[1][1] == [seg(KEYWORD, "klmno"), seg(None, "p")])

r = plan_text("abcdef", chars_per_line=3)
lines = flat_lines(r)
check("wrapRespectsCustomCharsPerLine",
      [line_text(l) for l in lines] == ["abc", "def"] and [l[0] for l in lines] == [1, None])

r = plan_text("a\nbb\nccc", line_numbers=False)
check("lineNumbersFalseNullsEveryNumber",
      [l[0] for l in flat_lines(r)] == [None, None, None]
      and [line_text(l) for l in flat_lines(r)] == ["a", "bb", "ccc"])

r = plan_text("aaaaaaa\nb\n", line_numbers=False, chars_per_line=5)
check("lineNumbersFalseKeepsPaginationInvariants",
      assert_invariants(r, "aaaaaaa\nb\n", line_numbers=False, chars_per_line=5))

r = plan_tok("val n", line_numbers=False)
check("lineNumbersFalseStillColors",
      flat_lines(r)[0][0] is None
      and flat_lines(r)[0][1] == [seg(KEYWORD, "val"), seg(None, " n")])

r = plan_tok("val n = 12 // ok", colored=False)
check("coloredFalseStripsAllRoles", flat_lines(r)[0][1] == [(None, "val n = 12 // ok")])

r = plan_text("x" * 200, colored=False, chars_per_line=88)
lines = flat_lines(r)
check("coloredFalseStillWrapsLongLines",
      len(lines) == 3 and all(l[1] == [(None, line_text(l))] for l in lines))

r = plan_text("\n".join("l%d" % i for i in range(1, 45)))
check("exactlyLinesPerPageIsOnePage", r[0] == "SUCCESS" and len(r[1]) == 1 and len(r[1][0]) == 44)

r = plan_text("\n".join("l%d" % i for i in range(1, 46)))
check("oneLineOverFillsSecondPage", len(r[1]) == 2 and len(r[1][0]) == 44 and len(r[1][1]) == 1)

r = plan_text("\n".join("l%d" % i for i in range(1, 90)))
check("pageChunkingFollowsLinesPerPage",
      len(r[1]) == 3 and [len(p) for p in r[1]] == [44, 44, 1])

r = plan_text("\n".join("l%d" % i for i in range(1, 91)))
check("lastPartialPageHoldsRemainder",
      [len(p) for p in r[1]] == [44, 44, 2] and r[2] == 90)

r = plan(to_units("\n" * MAX_EXPORT_LINES), [])
check("overLineLimitFailsWithActualAndLimit",
      r[0] == "TOO_LARGE" and r[1] == MAX_EXPORT_LINES + 1)

r = plan(to_units("\n" * (MAX_EXPORT_LINES - 1)), [])
check("atLineLimitSucceeds",
      r[0] == "SUCCESS" and r[2] == MAX_EXPORT_LINES
      and len(r[1]) == (MAX_EXPORT_LINES + LINES_PER_PAGE - 1) // LINES_PER_PAGE
      and len(r[1][-1]) == 32)

r = plan(to_units("abc"), [(KEYWORD, 0, 4)])
check("tokenEndBeyondTextFails", r[0] == "TOKEN_MISMATCH" and r[1] == 0 and r[2] == 0 and r[3] == 4)

r = plan(to_units("abc"), [(STRING, -1, 1)])
r2 = plan(to_units("abc"), [(STRING, 2, 1)])
check("negativeStartAndInvertedRangeFail", r[0] == "TOKEN_MISMATCH" and r2[0] == "TOKEN_MISMATCH")

r = plan(to_units("val x"), [(KEYWORD, 0, 3), (NUMBER, 4, 9)])
check("tokenIndexIsReportedInMismatch", r[0] == "TOKEN_MISMATCH" and r[1] == 1)

r = plan(to_units("abc"), [(COMMENT, 1, 2**31 - 1)])
check("hugeEndNeverCrashesPlanner", r[0] == "TOKEN_MISMATCH")

try:
    plan(to_units("abc"), [], chars_per_line=0)
    rejected0 = False
except ValueError:
    rejected0 = True
try:
    plan(to_units("abc"), [], chars_per_line=-1)
    rejected1 = False
except ValueError:
    rejected1 = True
check("zeroAndNegativeCharsPerLineAreRejected", rejected0 and rejected1)

r = plan_tok("x \U0001F680 val")
plain = u(to_units("x \U0001F680 "))
check("emojiDoesNotShiftTokenSegments",
      flat_lines(r)[0][1] == [seg(None, plain), seg(KEYWORD, "val")])

r = plan_text("a\U0001F680b", chars_per_line=3)
lines = flat_lines(r)
check("emojiAcrossWrapBoundaryKeepsConcatenation",
      [l[0] for l in lines] == [1, None]
      and [line_text(l) for l in lines] == [u(to_units("a\U0001F680")), "b"]
      and assert_invariants(r, u(to_units("a\U0001F680b")), chars_per_line=3))

r = plan_tok(preprocess("val a = 1\r\n// done"))
lines = flat_lines(r)
check("crlfOffsetsMatchAfterPreprocessAndTokenize",
      r[2] == 2
      and lines[0][1] == [seg(KEYWORD, "val"), seg(None, " a = "), seg(NUMBER, "1")]
      and lines[1][1] == [seg(COMMENT, "// done")]
      and [l[0] for l in lines] == [1, 2])

text = "val header = 1\n\"0123456789ABCDEF\" val\n\n42 // tail\n"
r = plan_tok(text, chars_per_line=12)
check("invariantsHoldOnRepresentativeDocument",
      r[0] == "SUCCESS" and assert_invariants(r, text, chars_per_line=12))

r = plan_text("a\n\nb")
lines = flat_lines(r)
check("emptyLinesKeepGridRowWithEmptyPlainSegment",
      [l[0] for l in lines] == [1, 2, 3] and lines[1][1] == [(None, "")])

check("defaultOptionsUsePrintLayoutGeometry",
      DEFAULT_CHARS_PER_LINE == 88 and LINES_PER_PAGE == 44 and MAX_EXPORT_LINES == 100_000)

print()
print("TOTAL FAILURES:", len(FAIL))
sys.exit(1 if FAIL else 0)
