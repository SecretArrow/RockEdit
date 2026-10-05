#!/usr/bin/env python3
"""v016 CodeStatistics pre-CI verification (Task 10-a).

Pure-Python mirror of app/src/main/java/com/secretarrow/rockedit/core/
CodeStatistics.kt: string/comment-aware classification, line endings,
indentation profile, TODO counting. Vectors below are HAND-COMPUTED
(do not copy values from the Kotlin tests) so a shared bug is unlikely.

Exit code 0 + "ALL PASS" when every vector matches; non-zero otherwise.
"""
import sys

COMMON_WIDTHS = (1, 2, 3, 4, 6, 8)

PROFILES = {
    "default": (["//"], [("/*", "*/")], []),
    "python": (["#"], [], ['"""', "'''"]),
    "hash": (["#"], [], []),
    "lua": (["--"], [("--[[", "]]")], []),
    "html": ([], [("<!--", "-->")], []),
    "sql": (["--"], [("/*", "*/")], []),
    "haskell": (["--"], [("{-", "-}")], []),
    "matlab": (["%"], [], []),
    "ini": ([";", "#"], [], []),
    "latex": (["%"], [], []),
    "css": ([], [("/*", "*/")], []),
}

EXT_MAP = {
    "py": "python", "pyi": "python", "pyw": "python",
    "sh": "hash", "bash": "hash", "zsh": "hash", "rb": "hash",
    "yml": "hash", "yaml": "hash", "toml": "hash", "r": "hash",
    "mk": "hash", "nix": "hash",
    "lua": "lua",
    "html": "html", "htm": "html", "xml": "html", "svg": "html",
    "vue": "html", "xhtml": "html",
    "sql": "sql",
    "hs": "haskell", "lhs": "haskell",
    "m": "matlab",
    "ini": "ini", "properties": "ini", "cfg": "ini", "conf": "ini",
    "tex": "latex", "latex": "latex",
    "css": "css", "scss": "css", "less": "css",
}


def profile_for(name):
    if not name or not name.strip():
        return "default"
    dot = name.rfind(".")
    if dot < 0 or dot == len(name) - 1:
        return "default"
    return EXT_MAP.get(name[dot + 1:].lower(), "default")


def is_ws(c):
    return c.isspace()


def count_non_ws(s, frm, to):
    return sum(1 for c in s[frm:to] if not is_ws(c))


def count_line_endings(text):
    crlf = lf = cr = 0
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "\r":
            if i + 1 < n and text[i + 1] == "\n":
                crlf += 1
                i += 1
            else:
                cr += 1
        elif c == "\n":
            lf += 1
        i += 1
    return crlf, lf, cr


def split_logical_lines(text):
    """Mirror of the Kotlin split: empty -> [], trailing terminator does not
    add a final empty line."""
    if not text:
        return []
    lines, cur = [], []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "\r":
            if i + 1 < n and text[i + 1] == "\n":
                i += 1
            lines.append("".join(cur))
            cur = []
        elif c == "\n":
            lines.append("".join(cur))
            cur = []
        else:
            cur.append(c)
        i += 1
    if (text.endswith("\n") or text.endswith("\r")) and not cur:
        return lines
    lines.append("".join(cur))
    return lines


def textstats_line_count(text):
    if not text:
        return 0
    lines = 1
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c in ("\n", "\r"):
            if c == "\r" and i + 1 < n and text[i + 1] == "\n":
                i += 1
            if i + 1 < n:
                lines += 1
        i += 1
    return lines


def textstats_word_count(text):
    return len(text.split())


def count_word_token(text, token):
    count, i, n = 0, 0, len(text)
    while i <= n - len(token):
        if text[i:i + len(token)] == token:
            before = " " if i == 0 else text[i - 1]
            after = " " if i + len(token) == n else text[i + len(token)]
            before_word = before.isalnum() or before == "_"
            after_word = after.isalnum() or after == "_"
            if not before_word and not after_word:
                count += 1
                i += len(token)
                continue
        i += 1
    return count


def analyze(text, profile_name="default"):
    line_comments, block_comments, triples = PROFILES[profile_name]
    line_comments = sorted([x for x in line_comments if x], key=len, reverse=True)
    block_comments = sorted([b for b in block_comments if b[0] and b[1]],
                            key=lambda b: len(b[0]), reverse=True)
    triples = sorted([t for t in triples if t], key=len, reverse=True)

    crlf, lf, cr = count_line_endings(text)
    lines = split_logical_lines(text)
    char_count = len(text)
    word_count = textstats_word_count(text)
    if not lines:
        return dict(charCount=charCount_v(text), wordCount=word_count, lineCount=0,
                    blank=0, commentOnly=0, code=0, longest=0, avg=0.0,
                    crlf=crlf, lf=lf, cr=cr, tab=0, space=0, common=-1,
                    mixed=False, trailing=0, todo=0, fixme=0, hack=0, xxx=0)

    blank = comment_only = trailing = tab_ind = space_ind = 0
    longest, total_len = 0, 0
    widths = {}
    comment_text = []
    in_block = None
    in_triple = None
    in_quote = None

    for line in lines:
        n = len(line)
        i = 0
        comment_non_ws = 0
        while i < n:
            if in_block is not None:
                end = line.find(in_block, i)
                if end < 0:
                    comment_non_ws += count_non_ws(line, i, n)
                    comment_text.append(line[i:n] + "\n")
                    i = n
                else:
                    stop = end + len(in_block)
                    comment_non_ws += count_non_ws(line, i, stop)
                    comment_text.append(line[i:stop] + "\n")
                    i = stop
                    in_block = None
                continue
            if in_triple is not None:
                end = line.find(in_triple, i)
                if end < 0:
                    i = n
                else:
                    i = end + len(in_triple)
                    in_triple = None
                continue
            if in_quote is not None:
                c = line[i]
                if c == "\\":
                    i += 2
                    continue
                i += 1
                if c == in_quote:
                    in_quote = None
                continue
            matched = False
            for opener, closer in block_comments:
                if line.startswith(opener, i):
                    comment_non_ws += len(opener)
                    in_block = closer
                    i += len(opener)
                    matched = True
                    break
            if matched:
                continue
            for starter in line_comments:
                if line.startswith(starter, i):
                    comment_non_ws += count_non_ws(line, i, n)
                    comment_text.append(line[i:n] + "\n")
                    i = n
                    matched = True
                    break
            if matched:
                continue
            for delim in triples:
                if line.startswith(delim, i):
                    in_triple = delim
                    i += len(delim)
                    matched = True
                    break
            if matched:
                continue
            if line[i] in ('"', "'"):
                in_quote = line[i]
            i += 1
        in_quote = None
        total_non_ws = count_non_ws(line, 0, n)
        total_len += n
        if n > longest:
            longest = n
        if total_non_ws == 0:
            blank += 1
        elif comment_non_ws >= total_non_ws:
            comment_only += 1
        else:
            if line[0] == "\t":
                tab_ind += 1
            elif line[0] == " ":
                space_ind += 1
                w = len(line) - len(line.lstrip(" "))
                widths[w] = widths.get(w, 0) + 1
            if line[-1] in (" ", "\t"):
                trailing += 1

    joined = "".join(comment_text)
    code = max(0, len(lines) - blank - comment_only)
    best_w, best_c = -1, 0
    for w in COMMON_WIDTHS:
        c = widths.get(w, 0)
        if c > best_c:
            best_c, best_w = c, w
    return dict(
        charCount=charCount_v(text), wordCount=word_count, lineCount=len(lines),
        blank=blank, commentOnly=comment_only, code=code, longest=longest,
        avg=total_len / len(lines), crlf=crlf, lf=lf, cr=cr,
        tab=tab_ind, space=space_ind, common=best_w,
        mixed=(tab_ind > 0 and space_ind > 0), trailing=trailing,
        todo=count_word_token(joined, "TODO"),
        fixme=count_word_token(joined, "FIXME"),
        hack=count_word_token(joined, "HACK"),
        xxx=count_word_token(joined, "XXX"),
    )


def charCount_v(t):
    return len(t)


CHECKS = 0
FAILURES = 0


def check(name, actual, expected):
    global CHECKS, FAILURES
    CHECKS += 1
    if actual != expected:
        FAILURES += 1
        print("FAIL %s: expected %r, got %r" % (name, expected, actual))


# ---- hand-computed vectors (NOT copied from Kotlin tests) ----

s = ""
r = analyze(s)
check("empty.lineCount", r["lineCount"], 0)
check("empty.wordCount", r["wordCount"], 0)
check("empty.longest", r["longest"], 0)
check("empty.avg", r["avg"], 0.0)
check("empty.common", r["common"], -1)

r = analyze("hello brave world")
check("oneline.lines", r["lineCount"], 1)
check("oneline.words", r["wordCount"], 3)
check("oneline.longest", r["longest"], 17)
check("oneline.code", r["code"], 1)

r = analyze("a\r\nb\rc\nd")
check("mixed.endings", (r["crlf"], r["lf"], r["cr"]), (1, 1, 1))
check("mixed.lineCount", r["lineCount"], 4)
check("mixed.textstats", textstats_line_count("a\r\nb\rc\nd"), 4)

r = analyze("x\r\ny\r\n")
check("crlf.only", (r["crlf"], r["lf"], r["cr"]), (2, 0, 0))
check("crlf.lineCount", r["lineCount"], 2)

# 3 lines: code / blank / comment-only
src = "int a;\n\n// note\n"
r = analyze(src)
check("cls.lines", r["lineCount"], 3)
check("cls.blank", r["blank"], 1)
check("cls.commentOnly", r["commentOnly"], 1)
check("cls.code", r["code"], 1)

# string hides comment starter
r = analyze('url = "http://x/y"  \n')
check("str.commentOnly", r["commentOnly"], 0)
check("str.code", r["code"], 1)
check("str.trailing", r["trailing"], 1)

# hash profile: '#' comment, but not inside quotes
r = analyze('s = "a # b"\n# real\n', "python")
check("py.code", r["code"], 1)
check("py.commentOnly", r["commentOnly"], 1)

# python triple string hides '#' and spans lines
r = analyze('x = """\n# not comment\n"""\n', "python")
check("py3.code", r["code"], 3)
check("py3.commentOnly", r["commentOnly"], 0)

# unclosed quote confined to line 1; line 2 comment detected
r = analyze("don't\n# real\n", "python")
check("apos.code", r["code"], 1)
check("apos.commentOnly", r["commentOnly"], 1)

# block comment one line / with code
r = analyze("/* note */\n")
check("blk1.commentOnly", r["commentOnly"], 1)
r = analyze("/* note */ int x;\n")
check("blk2.code", r["code"], 1)
check("blk2.commentOnly", r["commentOnly"], 0)

# unclosed block consumes the rest
r = analyze("/* open\nint hidden;\n// also\n")
check("ublock.commentOnly", r["commentOnly"], 3)
check("ublock.code", r["code"], 0)

# TODO counting: boundaries
r = analyze("// TODO: a\n// TODOS b\n// _TODO c\nmsg = \"TODO d\"\n")
check("todo.count", r["todo"], 1)
r = analyze("// FIXME f\n// HACK h\n// XXX x\n")
check("todos.total", r["fixme"] + r["hack"] + r["xxx"], 3)
check("todos.todoZero", r["todo"], 0)

# indentation
r = analyze("if a:\n    x = 1\n    y = 2\nz = 3\n", "python")
check("ind.space", r["space"], 2)
check("ind.common", r["common"], 4)
check("ind.mixed", r["mixed"], False)
r = analyze("a\n\tb\n    c\n")
check("ind2.tab", r["tab"], 1)
check("ind2.mixed", r["mixed"], True)
r = analyze(" a\n  b\n   c\n")
check("ind3.tie", r["common"], 1)
r = analyze("     a\n     b\n")
check("ind4.unusual", r["common"], -1)
r = analyze("code\n\t\n\treal\n")
check("ind5.blankTab", r["tab"], 1)
check("ind5.blank", r["blank"], 1)

# trailing whitespace
r = analyze("a   \n\t\nb\t\nc\n")
check("tr.count", r["trailing"], 2)

# longest / average (hand computed: 2,4,3 -> longest 4, avg 3.0)
r = analyze("ab\nabcd\nabc\n")
check("len.longest", r["longest"], 4)
check("len.avg", round(r["avg"], 6), 3.0)

# profile mapping
check("prof.py", profile_for("m.py"), "python")
check("prof.sh", profile_for("run.sh"), "hash")
check("prof.lua", profile_for("a.lua"), "lua")
check("prof.html", profile_for("i.html"), "html")
check("prof.sql", profile_for("q.sql"), "sql")
check("prof.css", profile_for("s.css"), "css")
check("prof.null", profile_for(None), "default")
check("prof.unknown", profile_for("x.zzz"), "default")
check("prof.trailingDot", profile_for("a."), "default")

# lua block vs line (block opener starts with line starter)
r = analyze("--[[ note ]]\n", "lua")
check("lua.block", r["commentOnly"], 1)
r = analyze("-- note\n", "lua")
check("lua.line", r["commentOnly"], 1)

# ini semicolon + hash
r = analyze("; c1\n# c2\n", "ini")
check("ini.both", r["commentOnly"], 2)

# empty-ish texts
r = analyze("\n")
check("nl.lineCount", r["lineCount"], 1)
check("nl.blank", r["blank"], 1)
r = analyze(" ")
check("sp.blank", r["blank"], 1)
check("sp.lines", r["lineCount"], 1)

# consistency with TextStats semantics on tricky endings
for v in ["a\r\nb", "a\rb", "a\n\n", "a\r\n", "\n", "\r\n\r\nx", "tail"]:
    check("consist %r" % v, analyze(v)["lineCount"], textstats_line_count(v))

print("TOTAL CHECKS: %d, TOTAL FAILURES: %d" % (CHECKS, FAILURES))
if FAILURES:
    sys.exit(1)
print("ALL PASS")
