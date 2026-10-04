#!/usr/bin/env python3
"""1:1 Python mirror of core/TodoScanner.kt (v0.13.0) to verify the Kotlin
test expectations before CI (same technique as scripts/*_verify.py)."""
import sys

MAX_MESSAGE = 200
MAX_TAG = 20
ELLIPSIS = "…"
DEFAULT_MARKERS = {"TODO", "FIXME", "HACK", "XXX", "BUG", "NOTE"}


def is_word(c):
    return c.isalnum() or c == "_"


def is_tag_char(c):
    return c.isalnum() or c in "-_"


def chars_equal(a, b, cs):
    return a == b if cs else a.casefold() == b.casefold()


def region_matches(text, pos, marker, cs):
    seg = text[pos:pos + len(marker)]
    if len(seg) != len(marker):
        return False
    return seg == marker if cs else seg.casefold() == marker.casefold()


def scan_line(text, line_start, line_end, line_no, ordered, cs, max_items, items):
    pos = line_start
    while pos < line_end:
        advance = 1
        for marker in ordered:
            mlen = len(marker)
            if pos + mlen > line_end:
                continue
            if not chars_equal(text[pos], marker[0], cs):
                continue
            if not region_matches(text, pos, marker, cs):
                continue
            if pos > line_start and is_word(text[pos - 1]):
                continue
            after = pos + mlen
            if after < line_end and is_word(text[after]):
                continue
            tag = None
            msg_start = after
            if after < line_end and text[after] == "(":
                close = text.find(")", after + 1, line_end)
                if close > after + 1:
                    cand = text[after + 1:close]
                    if len(cand) <= MAX_TAG and all(is_tag_char(c) for c in cand):
                        tag = cand
                        msg_start = close + 1
            raw = text[msg_start:line_end].strip()
            message = raw[:MAX_MESSAGE] + ELLIPSIS if len(raw) > MAX_MESSAGE else raw
            items.append({
                "line": line_no, "col": pos - line_start + 1, "marker": marker,
                "tag": tag, "message": message, "offset": pos,
            })
            advance = mlen
            if len(items) >= max_items:
                return True
            break
        pos += advance
    return False


def scan(text, markers=None, cs=False, max_items=1000, cap=1_000_000):
    if len(text) > cap:
        return ("INPUT_TOO_LARGE", len(text), cap)
    ms = markers if markers is not None else DEFAULT_MARKERS
    ms = {m.strip().upper() for m in ms}
    if not ms:
        return ("NO_MARKERS",)
    ordered = sorted(ms, key=lambda m: (-len(m), m))
    items = []
    n = len(text)
    line_start, line_no, truncated = 0, 1, False
    while line_start < n:
        j = line_start
        while j < n and text[j] not in "\n\r":
            j += 1
        line_end = j
        nxt = j
        if nxt < n:
            nxt += 2 if text[nxt] == "\r" and nxt + 1 < n and text[nxt + 1] == "\n" else 1
        if scan_line(text, line_start, line_end, line_no, ordered, cs, max_items, items):
            truncated = True
            break
        line_no += 1
        line_start = nxt
    return ("SUCCESS", items, truncated)


FAIL = []


def check(name, cond):
    if not cond:
        FAIL.append(name)
    print(("PASS " if cond else "FAIL ") + name)


# --- test vectors (mirror TodoScannerTest.kt) ---
r = scan("TODO one\nFIXME two\nHACK three\nXXX four\nBUG five\nNOTE six")
check("findsEveryDefaultMarker", r[0] == "SUCCESS" and len(r[1]) == 6 and
      {i["marker"] for i in r[1]} == {"TODO", "FIXME", "HACK", "XXX", "BUG", "NOTE"} and not r[2])
i = scan("todo fix this")[1][0]
check("lowercaseMarkerMatchesWhenCaseInsensitive",
      i["marker"] == "TODO" and i["line"] == 1 and i["col"] == 1)
i = scan("FixMe now please")[1][0]
check("mixedCaseMarkerMatchesWhenCaseInsensitive", i["marker"] == "FIXME")
check("caseSensitiveSkipsLowercase", scan("todo fix this", cs=True)[1] == [])
items = scan("todo and TODO", cs=True)[1]
check("caseSensitiveFindsUppercaseOnly", len(items) == 1 and items[0]["marker"] == "TODO"
      and items[0]["col"] == 10)
items = scan("OPT do it\nTODO skip", markers={"OPT"})[1]
check("customMarkerSetReplacesDefaults", len(items) == 1 and items[0]["marker"] == "OPT")
check("wordBoundaryRejectsSuffixWord", scan("TODOS many things")[1] == [])
check("wordBoundaryRejectsPrefixWord", scan("xTODO keep")[1] == [])
check("wordBoundaryRejectsUnderscoreNeighbor",
      scan("TODO_x trailing")[1] == [] and scan("lead_TODO trailing")[1] == [])
i = scan("TODO: ship it")[1][0]
check("boundaryAllowsPunctuationSuffix", i["marker"] == "TODO" and i["message"] == ": ship it")
i = scan("see https://x/TODO/1")[1][0]
check("markerInsideUrlMatchesByDesign", i["marker"] == "TODO")
check("markerNeverSpansLineBreak", scan("TO\nDO")[1] == [])
i = scan("TODO(p1) write tests")[1][0]
check("tagIsCapturedAndMessageFollows", i["tag"] == "p1" and i["message"] == "write tests")
i = scan("FIXME(p2-fix_3) go")[1][0]
check("tagAllowsDashDigitsUnderscore", i["tag"] == "p2-fix_3")
i = scan("TODO(p q) check")[1][0]
check("malformedTagStaysInMessage", i["tag"] is None and i["message"] == "(p q) check")
i = scan("TODO() nothing")[1][0]
check("emptyTagIsMalformed", i["tag"] is None and i["message"] == "() nothing")
i = scan("TODO(p1 open")[1][0]
check("unclosedTagIsMalformed", i["tag"] is None and i["message"] == "(p1 open")
t20, t21 = "a" * 20, "a" * 21
check("tagLengthBoundary",
      scan(f"TODO({t20}) ok")[1][0]["tag"] == t20 and
      scan(f"TODO({t21}) no")[1][0]["tag"] is None and
      scan(f"TODO({t21}) no")[1][0]["message"] == f"({t21}) no")
i = scan("TODO    spaced out   ")[1][0]
check("messageIsTrimmed", i["message"] == "spaced out")
i = scan("TODO(p1)    indented")[1][0]
check("messageAfterTagIsTrimmed", i["message"] == "indented")
i = scan("TODO " + "x" * 250)[1][0]
check("longMessageIsCutWithEllipsis", len(i["message"]) == 201 and
      i["message"].endswith(ELLIPSIS) and i["message"][:-1] == "x" * 200)
check("emptyMessageStaysEmpty",
      scan("TODO")[1][0]["message"] == "" and scan("TODO(p1)")[1][0]["message"] == "")
r = scan("")
check("emptyTextSucceedsWithNoItems", r[0] == "SUCCESS" and r[1] == [] and not r[2])
r = scan("just some plain text\nnothing to see")
check("textWithoutMarkersSucceedsWithNoItems", r[1] == [] and not r[2])
items = scan("TODO first\nplain middle\nTODO last")[1]
check("markerAtEndOfFileWithoutNewline", len(items) == 2 and items[0]["line"] == 1 and
      items[1]["line"] == 3 and items[1]["col"] == 1)
check("trailingNewlineProducesNoExtraLine",
      len(scan("TODO x\n")[1]) == 1 and len(scan("TODO x\n\n")[1]) == 1)
items = scan("TODO one\r\nFIXME two\r\nplain\r\nBUG three")[1]
check("crlfColumnsLinesAndOffsets", len(items) == 3 and
      (items[0]["line"], items[0]["col"], items[0]["offset"]) == (1, 1, 0) and
      (items[1]["line"], items[1]["col"], items[1]["offset"]) == (2, 1, 10) and
      (items[2]["line"], items[2]["col"]) == (4, 1))
items = scan("TODO a\rFIXME b")[1]
check("loneCrSeparatesLines", len(items) == 2 and items[0]["line"] == 1 and items[1]["line"] == 2)
items = scan("TODO and FIXME here")[1]
check("consecutiveMarkersOnOneLine", len(items) == 2 and items[0]["marker"] == "TODO" and
      items[0]["col"] == 1 and items[1]["marker"] == "FIXME" and items[1]["col"] == 10)
i = scan("\tTODO x")[1][0]
check("columnCountsCharactersIncludingTabs", i["col"] == 2)
i = scan("code TODO here")[1][0]
check("columnForMidLineMarker", i["col"] == 6)
text = "line one\n// TODO hello"
i = scan(text)[1][0]
check("offsetPointsAtMarkerStart", i["offset"] == text.index("TODO") and
      text[i["offset"]:i["offset"] + len(i["marker"])] == "TODO")
r = scan("TODO a\nFIXME b\nHACK c\nXXX d\nBUG e", max_items=3)
check("truncatesAtMaxItems", r[0] == "SUCCESS" and len(r[1]) == 3 and r[2] and
      [i["marker"] for i in r[1]] == ["TODO", "FIXME", "HACK"])
r = scan("TODO a\nFIXME b\nHACK c", max_items=3)
check("reachingLimitExactlyStillReportsTruncated", len(r[1]) == 3 and r[2])
r = scan("a" * (1_000_000 + 1))
check("inputAboveLimitFails", r[0] == "INPUT_TOO_LARGE")
r1 = scan("0123456789ABC", cap=10)
r2 = scan("0123456789", cap=10)
check("inputAboveSmallCapFailsAndExactCapPasses",
      r1[0] == "INPUT_TOO_LARGE" and r2[0] == "SUCCESS" and r2[1] == [] and not r2[2])
r = scan("todo x", markers={" todo "})
check("optionsNormalizeAndTrimMarkers", len(r[1]) == 1 and r[1][0]["marker"] == "TODO")

print()
print("TOTAL FAILURES:", len(FAIL))
sys.exit(1 if FAIL else 0)
