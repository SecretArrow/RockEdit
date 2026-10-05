#!/usr/bin/env python3
"""1:1 Python mirror of core/CodeFolding.kt (v0.14.0) to verify the Kotlin
test expectations before CI (same technique as scripts/v013_todo_verify.py).
Replicates: CRLF normalization, brace scanning (string/comment/multiline
state, ordered events), indentation scanning (stack pass), placeholder
helpers, the restore archive (per-key LIFO queue), and every fold/unfold
failure branch. Then runs the same vectors as CodeFoldingTest.kt."""
import sys

MAX_TEXT_CHARS = 1_000_000
MAX_ACTIVE_FOLDS = 256
MAX_SCAN_DEPTH = 512
PLACEHOLDER_PREFIX = "⟦⋯ "
PLACEHOLDER_SUFFIX = " ⟧"
INDENT_STYLE_LANGUAGE_IDS = {"python", "vyper", "ruby", "lua", "elixir", "julia", "latex"}
INT_MAX = 2**31 - 1
INT_MIN = -(2**31)


class FoldRange:
    def __init__(self, start, end):
        self.start = start
        self.end = end

    def as_tuple(self):
        return (self.start, self.end)


def brace_config(lang):
    key = lang.strip().lower()
    if key == "kotlin":
        return {"line": ["//"], "block": [("/*", "*/")], "str": ['"', "'"],
                "ml": [('"""', '"""')]}
    if key == "go":
        return {"line": ["//"], "block": [("/*", "*/")], "str": ['"', "'"],
                "ml": [("`", "`")]}
    # generic C-family default (covers unknown ids)
    return {"line": ["//"], "block": [("/*", "*/")], "str": ['"', "'"], "ml": []}


def placeholder_hidden_count(line):
    n = len(line)
    i = 0
    while i < n and line[i] in " \t":
        i += 1
    if not line.startswith(PLACEHOLDER_PREFIX, i):
        return None
    digits_start = i + len(PLACEHOLDER_PREFIX)
    j = digits_start
    while j < n and "0" <= line[j] <= "9":
        j += 1
    if j == digits_start:
        return None
    if j < n and line[j] == "#":
        j += 1
        ordinal_start = j
        while j < n and "0" <= line[j] <= "9":
            j += 1
        if j == ordinal_start:
            return None
    if not line.startswith(PLACEHOLDER_SUFFIX, j):
        return None
    if j + len(PLACEHOLDER_SUFFIX) != n:
        return None
    k = digits_start
    while k < n and "0" <= line[k] <= "9":
        k += 1
    value = int(line[digits_start:k])
    if value > INT_MAX or value < INT_MIN:
        return None
    return value


def is_placeholder_line(line):
    return placeholder_hidden_count(line) is not None


def indent_of(line):
    out = []
    for c in line:
        if c in " \t":
            out.append(c)
        else:
            break
    return "".join(out)


def sorted_ranges(found):
    return sorted(found, key=lambda r: (r.start, -r.end))


def indent_ranges(lines):
    indents = []
    for line in lines:
        j = 0
        while j < len(line) and line[j] in " \t":
            j += 1
        indents.append(-1 if j == len(line) else j)
    found = []
    open_start = []
    open_indent = []
    open_end = []
    for j, indent in enumerate(indents):
        if indent < 0:
            continue
        while open_indent and open_indent[-1] >= indent:
            open_indent.pop()
            start = open_start.pop()
            end = open_end.pop()
            if end > start:
                found.append(FoldRange(start, end))
        for k in range(len(open_end)):
            open_end[k] = j
        open_start.append(j)
        open_indent.append(indent)
        open_end.append(j)
    while open_indent:
        open_indent.pop()
        start = open_start.pop()
        end = open_end.pop()
        if end > start:
            found.append(FoldRange(start, end))
    return sorted_ranges(found)


def brace_ranges(lines, cfg):
    openers = []
    found = []
    block_closer = None
    multiline_closer = None
    for index, line in enumerate(lines):
        i = 0
        n = len(line)
        while i < n:
            if block_closer is not None:
                end = line.find(block_closer, i)
                if end < 0:
                    break
                i = end + len(block_closer)
                block_closer = None
                continue
            if multiline_closer is not None:
                end = line.find(multiline_closer, i)
                if end < 0:
                    break
                i = end + len(multiline_closer)
                multiline_closer = None
                continue
            c = line[i]
            consumed = False
            for lc in cfg["line"]:
                if line.startswith(lc, i):
                    consumed = True
                    break
            if consumed:
                break
            for opening, closing in cfg["block"]:
                if line.startswith(opening, i):
                    end = line.find(closing, i + len(opening))
                    if end < 0:
                        block_closer = closing
                        i = n
                    else:
                        i = end + len(closing)
                    consumed = True
                    break
            if consumed:
                continue
            for opening, closing in cfg["ml"]:
                if line.startswith(opening, i):
                    end = line.find(closing, i + len(opening))
                    if end < 0:
                        multiline_closer = closing
                        i = n
                    else:
                        i = end + len(closing)
                    consumed = True
                    break
            if consumed:
                continue
            if c in cfg["str"]:
                i += 1
                while i < n:
                    sc = line[i]
                    if sc == "\\":
                        i += 2
                        continue
                    i += 1
                    if sc == c:
                        break
                continue
            if c == "{":
                openers.append(index)
                if len(openers) > MAX_SCAN_DEPTH:
                    return sorted_ranges(found)
            elif c == "}":
                if openers:
                    start = openers.pop()
                    if index > start:
                        found.append(FoldRange(start, index))
            i += 1
    return sorted_ranges(found)


def outermost_ranges(all_ranges):
    out = []
    last_end = -1
    for r in all_ranges:
        if r.start > last_end:
            out.append(r)
            last_end = r.end
    return out


class CodeFolding:
    def __init__(self, language_id):
        self.language_id = language_id
        self.normalized = language_id.strip().lower()
        self.uses_indent_style = self.normalized in INDENT_STYLE_LANGUAGE_IDS
        self.cfg = brace_config(self.normalized)
        self.archive = {}  # unique placeholder line -> list of bodies (LIFO)
        self.next_fold_id = 1

    def active_fold_count(self):
        return sum(len(q) for q in self.archive.values())

    def all_ranges(self, lines):
        if self.uses_indent_style:
            return indent_ranges(lines)
        return brace_ranges(lines, self.cfg)

    def compute_ranges(self, text):
        if len(text) > MAX_TEXT_CHARS:
            return []
        lines = text.replace("\r\n", "\n").split("\n")
        return [r.as_tuple() for r in outermost_ranges(self.all_ranges(lines))]

    def deepest_starting_at(self, all_ranges, line):
        best = None
        for r in all_ranges:
            if r.start != line:
                continue
            if best is None or r.end < best.end:
                best = r
        return best

    def deepest_containing(self, all_ranges, line):
        best = None
        for r in all_ranges:
            if r.start >= line or line > r.end:
                continue
            if (
                best is None
                or r.start > best.start
                or (r.start == best.start and r.end < best.end)
            ):
                best = r
        return best

    def foreign_placeholder(self, lines):
        for line in lines:
            if is_placeholder_line(line) and line not in self.archive:
                return line
        return None

    def first_placeholder_in(self, lines, rng):
        for i in range(rng.start, rng.end + 1):
            if is_placeholder_line(lines[i]):
                return i
        return -1

    def apply_fold(self, lines, rng):
        opener = lines[rng.start]
        body = list(lines[rng.start + 1 : rng.end + 1])
        base = indent_of(opener) + PLACEHOLDER_PREFIX + str(len(body)) + PLACEHOLDER_SUFFIX
        placeholder = base
        if base in self.archive:
            while True:
                candidate = (
                    indent_of(opener)
                    + PLACEHOLDER_PREFIX
                    + str(len(body))
                    + "#"
                    + str(self.next_fold_id)
                    + PLACEHOLDER_SUFFIX
                )
                self.next_fold_id += 1
                if candidate not in self.archive:
                    placeholder = candidate
                    break
        self.archive.setdefault(placeholder, []).append(body)
        del lines[rng.start + 1 : rng.end + 1]
        lines.insert(rng.start + 1, placeholder)
        return len(body)

    def take_archive(self, placeholder):
        queue = self.archive.get(placeholder)
        if not queue:
            return None
        body = queue.pop()
        if not queue:
            del self.archive[placeholder]
        return body

    def prune_orphans(self, lines):
        if not self.archive:
            return
        present = {line for line in lines if is_placeholder_line(line)}
        for key in [k for k in self.archive if k not in present]:
            del self.archive[key]

    def failure(self, code, message):
        return ("FAILURE", code, message)

    def fold_all(self, text):
        if len(text) > MAX_TEXT_CHARS:
            return self.failure("INPUT_TOO_LARGE", "text is %d characters" % len(text))
        lines = text.replace("\r\n", "\n").split("\n")
        foreign = self.foreign_placeholder(lines)
        if foreign is not None:
            return self.failure("PLACEHOLDER_AMBIGUOUS", "placeholder-looking line: " + foreign)
        ranges = outermost_ranges(self.all_ranges(lines))
        if not ranges:
            return self.failure("NO_FOLD_RANGE", "no foldable region detected")
        for rng in ranges:
            offender = self.first_placeholder_in(lines, rng)
            if offender >= 0:
                return self.failure("REGION_CONTAINS_PLACEHOLDER", "line %d" % offender)
        total = self.active_fold_count() + len(ranges)
        if total > MAX_ACTIVE_FOLDS:
            return self.failure(
                "TOO_MANY_FOLDS",
                "%d active fold(s) would exceed the limit of %d (requested %d more)"
                % (total, MAX_ACTIVE_FOLDS, len(ranges)),
            )
        result = list(lines)
        hidden = 0
        for rng in sorted(ranges, key=lambda r: -r.start):
            hidden += self.apply_fold(result, rng)
        return ("DONE", "\n".join(result), hidden)

    def unfold_all(self, text):
        if len(text) > MAX_TEXT_CHARS:
            return self.failure("INPUT_TOO_LARGE", "text is %d characters" % len(text))
        lines = text.replace("\r\n", "\n").split("\n")
        restored = 0
        for i in reversed(range(len(lines))):
            current = lines[i]
            if not is_placeholder_line(current):
                continue
            body = self.take_archive(current)
            if body is None:
                continue
            del lines[i]
            lines[i:i] = body
            restored += len(body)
        self.prune_orphans(lines)
        return ("DONE", "\n".join(lines), restored)

    def fold_at_line(self, text, line):
        if len(text) > MAX_TEXT_CHARS:
            return self.failure("INPUT_TOO_LARGE", "text is %d characters" % len(text))
        lines = text.replace("\r\n", "\n").split("\n")
        foreign = self.foreign_placeholder(lines)
        if foreign is not None:
            return self.failure("PLACEHOLDER_AMBIGUOUS", "placeholder-looking line: " + foreign)
        if line < 0 or line >= len(lines):
            return self.failure("NO_FOLD_RANGE", "line %d outside the text" % line)
        all_ranges = self.all_ranges(lines)
        target = self.deepest_starting_at(all_ranges, line)
        if target is None:
            target = self.deepest_containing(all_ranges, line)
        if target is None:
            return self.failure("NO_FOLD_RANGE", "no region at line %d" % line)
        offender = self.first_placeholder_in(lines, target)
        if offender >= 0:
            return self.failure("REGION_CONTAINS_PLACEHOLDER", "line %d" % offender)
        total = self.active_fold_count() + 1
        if total > MAX_ACTIVE_FOLDS:
            return self.failure(
                "TOO_MANY_FOLDS",
                "%d active fold(s) would exceed the limit of %d" % (total, MAX_ACTIVE_FOLDS),
            )
        result = list(lines)
        hidden = self.apply_fold(result, target)
        return ("DONE", "\n".join(result), hidden)

    def unfold_at_line(self, text, line):
        if len(text) > MAX_TEXT_CHARS:
            return self.failure("INPUT_TOO_LARGE", "text is %d characters" % len(text))
        lines = text.replace("\r\n", "\n").split("\n")
        if line < 0 or line >= len(lines):
            return self.failure("NOT_A_PLACEHOLDER", "line %d outside the text" % line)
        current = lines[line]
        if not is_placeholder_line(current):
            return self.failure("NOT_A_PLACEHOLDER", "line %d is not a placeholder" % line)
        body = self.take_archive(current)
        if body is None:
            self.prune_orphans(lines)
            return self.failure("REGION_GONE", "no archived content for line %d" % line)
        del lines[line]
        lines[line:line] = body
        return ("DONE", "\n".join(lines), len(body))


KOTLIN_NESTED = (
    "class A {\n"
    "    fun a() {\n"
    "        x = 1\n"
    "    }\n"
    "    fun b() {\n"
    "        y = 2\n"
    "    }\n"
    "}"
)
PYTHON_TWO_DEFS = (
    "def a():\n    x = 1\n    y = 2\n\ndef b():\n    z = 3\n    w = 4\n    v = 5\n"
)

FAIL = []


def check(name, cond):
    if not cond:
        FAIL.append(name)
    print(("PASS " if cond else "FAIL ") + name)


# --- test vectors (mirror CodeFoldingTest.kt) ---
f = CodeFolding("kotlin")
r = f.fold_all(KOTLIN_NESTED)
check("braceFoldAllUsesOutermostRangeOnly",
      r == ("DONE", "class A {\n⟦⋯ 7 ⟧", 7) and f.active_fold_count() == 1)
f = CodeFolding("kotlin")
check("braceComputeRangesReturnsOutermostOnly",
      f.compute_ranges(KOTLIN_NESTED) == [(0, 7)])
f = CodeFolding("kotlin")
r = f.fold_at_line(KOTLIN_NESTED, 1)
check("braceFoldAtLineDeepestRegionStartingAtLine",
      r == ("DONE", "class A {\n    fun a() {\n    ⟦⋯ 2 ⟧\n    fun b() {\n        y = 2\n    }\n}", 2))
f = CodeFolding("kotlin")
r = f.fold_at_line(KOTLIN_NESTED, 5)
check("braceFoldAtLineDeepestRegionContainingLine",
      r[0] == "DONE" and r[2] == 2 and "    fun b() {\n    ⟦⋯ 2 ⟧\n}" in r[1])
f = CodeFolding("kotlin")
check("braceFoldAtLinePrefersRegionStartingAtLine",
      f.fold_at_line(KOTLIN_NESTED, 0)[2] == 7 and f.fold_at_line(KOTLIN_NESTED, 1)[2] == 2)
f = CodeFolding("kotlin")
r1 = f.fold_all(KOTLIN_NESTED)
r2 = f.unfold_all(r1[1])
check("braceFoldUnfoldRoundTripRestoresExactText",
      r2 == ("DONE", KOTLIN_NESTED, 7) and f.active_fold_count() == 0)
f = CodeFolding("kotlin")
text = 'class A {\n    val open = "{"\n    val close = \'}\'\n    val mixed = "a } b { c"\n}'
check("braceInsideStringIsIgnored", f.compute_ranges(text) == [(0, 4)])
f = CodeFolding("kotlin")
text = "class A {\n    // } { }}} no braces here\n    val x = 1\n}"
check("braceInsideLineCommentIsIgnored", f.compute_ranges(text) == [(0, 3)])
f = CodeFolding("kotlin")
text = "class A {\n    /* } { */\n    /* multi\n       }} {{\n */\n    val x = 1\n}"
check("braceInsideBlockCommentIsIgnored", f.compute_ranges(text) == [(0, 6)])
f = CodeFolding("kotlin")
text = 'class A {\n    val s = """\n    {\n    }\n    """\n}'
check("braceInsideMultilineStringIsIgnored", f.compute_ranges(text) == [(0, 5)])
f = CodeFolding("go")
text = "func a() {\n\ts := `\n    {\n    }\n`\n}"
check("braceInsideGoRawStringIsIgnored", f.compute_ranges(text) == [(0, 5)])
f = CodeFolding("kotlin")
text = "class A {\n    fun a() {\n        x\n"
check("unclosedBraceIsIgnoredSilently",
      f.all_ranges(text.split("\n")) == [] and f.fold_all(text)[1] == "NO_FOLD_RANGE")
f = CodeFolding("kotlin")
text = "    }\n}\nval x = 1\n"
check("unmatchedClosingBraceIsIgnoredSilently",
      f.all_ranges(text.split("\n")) == [] and f.active_fold_count() == 0)
f = CodeFolding("kotlin")
text = "fun x() { doIt() }"
check("pairOnSameLineYieldsNoRange",
      f.all_ranges(text.split("\n")) == [] and f.fold_all(text)[1] == "NO_FOLD_RANGE")
f = CodeFolding("kotlin")
text = "if (a) {\n    x = 1\n} else {\n    y = 2\n}"
check("closerAndOpenerOnSameLinePairSeparately",
      f.compute_ranges(text) == [(0, 2)] and
      f.fold_at_line(text, 3) == ("DONE", "if (a) {\n    x = 1\n} else {\n⟦⋯ 2 ⟧", 2))
f = CodeFolding("kotlin")
folded = f.fold_all(KOTLIN_NESTED)[1]
check("foldAllTwiceOnFoldedTextIsNoFoldRange", f.fold_all(folded)[1] == "NO_FOLD_RANGE")
f = CodeFolding("python")
check("pythonFoldAllTwoBlocks",
      f.fold_all(PYTHON_TWO_DEFS) == ("DONE", "def a():\n⟦⋯ 2 ⟧\n\ndef b():\n⟦⋯ 3 ⟧\n", 5))
f = CodeFolding("python")
check("pythonTrailingBlankLinesExcluded",
      f.fold_all("def a():\n    x = 1\n\n\n") == ("DONE", "def a():\n⟦⋯ 1 ⟧\n\n\n", 1))
f = CodeFolding("python")
check("pythonBlankLineInsideBlockIncluded",
      f.fold_all("def a():\n    x = 1\n\n    y = 2\n") == ("DONE", "def a():\n⟦⋯ 3 ⟧\n", 3))
f = CodeFolding("python")
text = "def a():\n\n\n"
check("pythonBlankOnlyBodyHasNoRange",
      f.all_ranges(text.split("\n")) == [] and f.fold_all(text)[1] == "NO_FOLD_RANGE")
f = CodeFolding("python")
text = "def a():\n    if x:\n        y = 1\n    z = 2\n"
check("pythonNestedBlockFoldsDeepest",
      f.compute_ranges(text) == [(0, 3)] and
      f.fold_at_line(text, 1) == ("DONE", "def a():\n    if x:\n    ⟦⋯ 1 ⟧\n    z = 2\n", 1) and
      f.fold_at_line(text, 0)[2] == 3)
f = CodeFolding("python")
inside = "def a():\n    x = 1\n    # note\n    y = 2"
closing = "def a():\n    x = 1\n# top level comment\nz = 0"
check("pythonCommentLineCountedByIndent",
      f.compute_ranges(inside) == [(0, 3)] and f.compute_ranges(closing) == [(0, 1)])
f = CodeFolding("kotlin")
r = f.fold_at_line(KOTLIN_NESTED, 1)
check("placeholderIndentMatchesOpenerIndent",
      "\n    ⟦⋯ 2 ⟧\n" in r[1] and placeholder_hidden_count("    ⟦⋯ 2 ⟧") == 2)
check("placeholderHelpersAccept",
      is_placeholder_line("    ⟦⋯ 12 ⟧") and placeholder_hidden_count("    ⟦⋯ 12 ⟧") == 12 and
      is_placeholder_line("⟦⋯ 1 ⟧") and is_placeholder_line("\t⟦⋯ 2 ⟧") and
      placeholder_hidden_count("⟦⋯ 0 ⟧") == 0 and placeholder_hidden_count("⟦⋯ 07 ⟧") == 7 and
      placeholder_hidden_count("⟦⋯ 2147483647 ⟧") == INT_MAX)
check("placeholderHelpersReject",
      placeholder_hidden_count("⟦⋯ ⟧") is None and
      placeholder_hidden_count("⟦⋯ x ⟧") is None and
      placeholder_hidden_count("⟦⋯ -1 ⟧") is None and
      placeholder_hidden_count("⟦⋯ 99999999999 ⟧") is None and
      placeholder_hidden_count("⟦⋯ 1 ⟧ trailing") is None and
      placeholder_hidden_count("x ⟦⋯ 1 ⟧") is None and
      placeholder_hidden_count("⟦⋯ 1") is None and
      placeholder_hidden_count("") is None and
      not is_placeholder_line("val x = 1"))
f = CodeFolding("kotlin")
text = "class A {\n⟦⋯ 3 ⟧\n}"
check("originalPlaceholderLookingTextIsAmbiguous",
      f.fold_all(text)[1] == "PLACEHOLDER_AMBIGUOUS" and
      f.fold_at_line(text, 0)[1] == "PLACEHOLDER_AMBIGUOUS" and
      "⟦⋯ 3 ⟧" in f.fold_at_line(text, 0)[2] and f.active_fold_count() == 0)
f = CodeFolding("kotlin")
text = "class A {\n    fun a() {\n        x = 1\n    }\n}"
folded = f.fold_at_line(text, 1)[1]
check("foldingRegionContainingOwnPlaceholderRejected",
      f.active_fold_count() == 1 and
      f.fold_at_line(folded, 0)[1] == "NO_FOLD_RANGE" and
      f.fold_all(folded)[1] == "REGION_CONTAINS_PLACEHOLDER" and
      f.active_fold_count() == 1)
f = CodeFolding("kotlin")
text = "class A {\n    x = 1\n}"
check("unfoldAtLineOnPlainLineIsNotAPlaceholder",
      f.unfold_at_line(text, 0)[1] == "NOT_A_PLACEHOLDER" and
      f.unfold_at_line(text, 1)[1] == "NOT_A_PLACEHOLDER" and
      f.unfold_at_line(text, -1)[1] == "NOT_A_PLACEHOLDER" and
      f.unfold_at_line(text, 99)[1] == "NOT_A_PLACEHOLDER")
f = CodeFolding("python")
text = "def a():\n    x = 1\n    y = 2"
folded = f.fold_all(text)[1]
check("unfoldAtLineRestoresExactBody",
      folded == "def a():\n⟦⋯ 2 ⟧" and f.unfold_at_line(folded, 1) == ("DONE", text, 2) and
      f.active_fold_count() == 0)
f = CodeFolding("python")
folded = f.fold_all("def a():\n    x = 1\n    y = 2")[1]
edited = folded.replace("⟦⋯ 2 ⟧", "⟦⋯ 9 ⟧")
r = f.unfold_at_line(edited, 1)
check("regionGoneWhenTextChanged", r[1] == "REGION_GONE" and f.active_fold_count() == 0)
f = CodeFolding("python")
folded = f.fold_all("def a():\n    x = 1\n    y = 2")[1]
fresh = CodeFolding("python")
check("regionGoneWhenStateLost",
      fresh.unfold_at_line(folded, 1)[1] == "REGION_GONE" and
      fresh.unfold_all(folded) == ("DONE", folded, 0))
f = CodeFolding("python")
folded = f.fold_all("def a():\n    x = 1\n    y = 2")[1]
check("unfoldAllSilentlyDropsMissingPlaceholders",
      f.unfold_all("def a():\ngone\n") == ("DONE", "def a():\ngone\n", 0) and
      f.active_fold_count() == 0)
f = CodeFolding("python")
r = f.fold_all("".join("def f%d():\n    pass\n" % i for i in range(257)))
check("tooManyFoldsOn257Ranges",
      r[1] == "TOO_MANY_FOLDS" and "257" in r[2] and f.active_fold_count() == 0)
f = CodeFolding("python")
text = "".join("def f%d():\n    pass\n" % i for i in range(256))
r = f.fold_all(text)
check("tooManyFoldsBoundary256Accepted",
      r[0] == "DONE" and r[2] == 256 and f.active_fold_count() == 256 and
      f.unfold_all(r[1]) == ("DONE", text, 256) and f.active_fold_count() == 0)
f = CodeFolding("python")
f.fold_all("".join("def f%d():\n    pass\n" % i for i in range(256)))
r = f.fold_all("class X {\n    y = 1\n}")
check("tooManyFoldsAcrossOperations",
      r[1] == "TOO_MANY_FOLDS" and "257" in r[2] and f.active_fold_count() == 256)
f = CodeFolding("python")
text = "x" * (MAX_TEXT_CHARS + 1)
check("inputTooLargeRejected",
      f.fold_all(text)[1] == "INPUT_TOO_LARGE" and "1000001" in f.fold_all(text)[2] and
      f.fold_at_line(text, 0)[1] == "INPUT_TOO_LARGE" and
      f.unfold_all(text)[1] == "INPUT_TOO_LARGE" and
      f.unfold_at_line(text, 0)[1] == "INPUT_TOO_LARGE" and
      f.compute_ranges(text) == [])
f = CodeFolding("python")
sb = "def a():"
while len(sb) + 6 <= MAX_TEXT_CHARS:
    sb += "\n    x"
sb += " " * (MAX_TEXT_CHARS - len(sb))
r = f.fold_all(sb)
check("inputAtExactLimitAccepted", len(sb) == MAX_TEXT_CHARS and r[0] == "DONE" and r[2] > 0)
f = CodeFolding("kotlin")
check("emptyTextHasNoRanges",
      f.compute_ranges("") == [] and f.fold_all("")[1] == "NO_FOLD_RANGE" and
      f.unfold_all("") == ("DONE", "", 0) and f.fold_at_line("", 0)[1] == "NO_FOLD_RANGE" and
      f.active_fold_count() == 0)
f = CodeFolding("python")
text = "x = 1"
check("singleLineTextHasNoRanges",
      f.all_ranges(text.split("\n")) == [] and f.fold_all(text)[1] == "NO_FOLD_RANGE" and
      f.unfold_all(text) == ("DONE", text, 0))
f = CodeFolding("kotlin")
check("foldAtLineOutOfRangeIsNoFoldRange",
      f.fold_at_line(KOTLIN_NESTED, -1)[1] == "NO_FOLD_RANGE" and
      f.fold_at_line(KOTLIN_NESTED, 8)[1] == "NO_FOLD_RANGE")
f = CodeFolding("python")
text = "x = 1\ny = 2\n"
check("foldAtLineWithoutRegionIsNoFoldRange",
      f.fold_at_line(text, 0)[1] == "NO_FOLD_RANGE" and
      f.fold_at_line(text, 1)[1] == "NO_FOLD_RANGE")
f = CodeFolding("python")
first = f.fold_at_line(PYTHON_TWO_DEFS, 0)
second = f.fold_at_line(first[1], 3)
active_after_folds = f.active_fold_count()
r = f.unfold_all(second[1])
check("unfoldAllRestoresTwoSeparateFolds",
      first[0] == "DONE" and active_after_folds == 2 and second[2] == 3 and
      r == ("DONE", PYTHON_TWO_DEFS, 5) and f.active_fold_count() == 0)
f = CodeFolding("kotlin")
text = "a = 1\nb = 2\n"
once = f.unfold_all(text)
check("unfoldAllIsIdempotentOnPlainText",
      once == ("DONE", text, 0) and f.unfold_all(once[1]) == ("DONE", text, 0) and
      f.active_fold_count() == 0)
f = CodeFolding("kotlin")
folded = f.fold_all(KOTLIN_NESTED)
check("hiddenLinesMatchesFoldedLineCount",
      len(KOTLIN_NESTED.split("\n")) - len(folded[1].split("\n")) + 1 == folded[2])
f = CodeFolding("kotlin")
text = "class A {\r\n    fun a() {\r\n        x\r\n    }\r\n}"
folded = f.fold_all(text)
check("crlfIsNormalizedToLf",
      folded == ("DONE", "class A {\n⟦⋯ 4 ⟧", 4) and "\r" not in folded[1] and
      f.unfold_all(folded[1]) == ("DONE", text.replace("\r\n", "\n"), 4))
f = CodeFolding("python")
first_fold = f.fold_at_line(PYTHON_TWO_DEFS, 0)
second_fold = f.fold_at_line(first_fold[1], 3)
restored_all = f.unfold_all(first_fold[1])
check("activeFoldCountTracksArchive",
      f.active_fold_count() == 0 and
      first_fold[0] == "DONE" and f.active_fold_count() == 1 and
      second_fold[0] == "DONE" and f.active_fold_count() == 2 and
      restored_all == ("DONE", PYTHON_TWO_DEFS, 2) and f.active_fold_count() == 0)
f = CodeFolding("python")
folded = f.fold_all("def a():\n    x = 1\n    y = 2\n")[1]
r = f.unfold_all("import os\n" + folded)
check("restoreWorksAfterEditsOutsideFold",
      r == ("DONE", "import os\ndef a():\n    x = 1\n    y = 2\n", 2))
f = CodeFolding("python")
text = "def a():\n    x = 1\n\ndef b():\n    y = 2\n"
folded = f.fold_all(text)
check("identicalSiblingPlaceholdersRoundTrip",
      folded == ("DONE", "def a():\n⟦⋯ 1#1 ⟧\n\ndef b():\n⟦⋯ 1 ⟧\n", 2) and f.active_fold_count() == 2 and
      f.unfold_all(folded[1]) == ("DONE", text, 2))
f = CodeFolding("someconfig")
text = "if x {\n    y = 1\n}\n"
check("unknownLanguageUsesBraceFallback",
      f.language_id == "someconfig" and f.compute_ranges(text) == [(0, 2)] and
      f.fold_all(text)[2] == 2)
f = CodeFolding("Python")
check("languageIdIsPreservedAndNormalizedInternally",
      f.language_id == "Python" and f.compute_ranges("def a():\n    x\n") == [(0, 1)])

print()
print("TOTAL FAILURES:", len(FAIL))
if not FAIL:
    print("ALL PASS")
sys.exit(1 if FAIL else 0)
