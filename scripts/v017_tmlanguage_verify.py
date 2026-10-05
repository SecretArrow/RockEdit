#!/usr/bin/env python3
"""v017 TmLanguageParser pre-CI verification (Task 11-a).

Pure-Python mirror of app/src/main/java/com/secretarrow/rockedit/core/
TmLanguageParser.kt: JSON grammar validation, pattern walk with include
resolution + budget, keyword/comment/string extraction heuristics.
Vectors are HAND-COMPUTED. Exit 0 + "ALL PASS" when every vector matches.
"""
import json
import sys

MAX_JSON_CHARS = 2_000_000
MAX_PATTERNS_VISITED = 5_000
MAX_KEYWORDS = 5_000
MAX_LINE_COMMENTS = 4
MAX_BLOCK_COMMENTS = 4
MAX_KEYWORD_DEPTH = 4
MAX_LINE_TOKEN_CHARS = 3
MAX_BLOCK_TOKEN_CHARS = 6
CUSTOM_ID_PREFIX = "custom_"

META = set("^$.*+?|()[]{}")


def normalize_extension(raw):
    s = raw.strip().lower()
    # Kotlin trimStart('.', '*'): strip ANY leading run of '.'/'*' chars.
    return s.lstrip(".*")


def split_alternatives(regex):
    parts, current = [], []
    depth, in_class, i = 0, False, 0
    n = len(regex)
    while i < n:
        c = regex[i]
        if c == "\\":
            current.append(c)
            if i + 1 < n:
                current.append(regex[i + 1])
                i += 2
            else:
                i += 1
            continue
        if in_class:
            if c == "]":
                in_class = False
            current.append(c)
        elif c == "[":
            in_class = True
            current.append(c)
        elif c == "(":
            depth += 1
            current.append(c)
        elif c == ")":
            if depth > 0:
                depth -= 1
            current.append(c)
        elif c == "|" and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(c)
        i += 1
    parts.append("".join(current))
    return parts


def matching_paren(regex, open_idx):
    depth, in_class, i = 0, False, open_idx
    n = len(regex)
    while i < n:
        c = regex[i]
        if c == "\\":
            i += 2
            continue
        if in_class:
            if c == "]":
                in_class = False
            i += 1
            continue
        if c == "[":
            in_class = True
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return None


def introducer_end(regex, open_idx):
    if open_idx + 1 >= len(regex):
        return open_idx + 1
    if regex[open_idx + 1] != "?":
        return open_idx + 1
    if open_idx + 2 < len(regex) and regex[open_idx + 2] == ":":
        return open_idx + 3
    if open_idx + 2 < len(regex) and regex[open_idx + 2] in ("<", "'"):
        closer = ">" if regex[open_idx + 2] == "<" else "'"
        j = open_idx + 3
        while j < len(regex) and regex[j] != closer:
            j += 1
        return j + 1 if j < len(regex) else len(regex)
    return open_idx + 2


def is_wrapper(segment):
    i, n = 0, len(segment)
    while i < n:
        c = segment[i]
        if c == "\\":
            if i + 1 >= n:
                return False
            i += 2
            continue
        if c not in "^$. *+?\t":
            return False
        i += 1
    return True


def core_group_body(regex):
    i, in_class, n = 0, False, len(regex)
    while i < n:
        c = regex[i]
        if c == "\\":
            i += 2
            continue
        if in_class:
            if c == "]":
                in_class = False
            i += 1
            continue
        if c == "[":
            in_class = True
        elif c == "(":
            close = matching_paren(regex, i)
            if close is not None:
                intro_end = introducer_end(regex, i)
                if intro_end < close and is_wrapper(regex[:i]) and is_wrapper(regex[close + 1:]):
                    return regex[intro_end:close]
        i += 1
    return None


def literal_keyword_word(alternative):
    if len(alternative) < 2:
        return None
    word = []
    i, n = 0, len(alternative)
    while i < n:
        c = alternative[i]
        if c == "\\":
            if i + 1 >= n:
                return None
            if not alternative[i + 1].isalnum():
                return None
            i += 2
        elif c in "^$":
            i += 1
        elif c.isalnum() or c == "_":
            word.append(c)
            i += 1
        else:
            return None
    result = "".join(word)
    return result if len(result) >= 2 else None


def class_close(regex, open_idx):
    i = open_idx + 1
    n = len(regex)
    while i < n:
        c = regex[i]
        if c == "\\":
            i += 2
        elif c == "]":
            return i
        else:
            i += 1
    return None


def skip_padding(regex):
    i, n = 0, len(regex)
    while i < n:
        c = regex[i]
        if c in "^$.*+?":
            i += 1
        elif c == "{":
            close = regex.find("}", i)
            i = close + 1 if close >= 0 else n
        elif c == "[":
            close = class_close(regex, i)
            if close is None:
                return i
            i = close + 1
        elif c == "\\" and i + 1 < n and regex[i + 1] in "sSbBnrt ":
            i += 2
        else:
            return i
    return i


def literal_prefix(regex):
    literal = []
    i = skip_padding(regex)
    n = len(regex)
    while i < n:
        c = regex[i]
        if c == "\\":
            if i + 1 >= n:
                break
            nxt = regex[i + 1]
            if nxt.isalnum():
                break
            literal.append(nxt)
            i += 2
            continue
        if c in "^$.*+?|()[]{}":
            break
        if c.isspace():
            break
        literal.append(c)
        i += 1
    return "".join(literal)


def is_punct_token(token, max_chars):
    return (
        len(token) > 0
        and len(token) <= max_chars
        and all((not ch.isalnum()) and (not ch.isspace()) for ch in token)
    )


class WalkState:
    def __init__(self, root_patterns, repository):
        self.root_patterns = root_patterns
        self.repository = repository
        self.visited = 0
        self.expanded_includes = set()
        self.keywords = []
        self.line_comments = []
        self.block_comments = []
        self.string_quotes = []
        self.saw_string_quoted = False


def collect_keyword_words(regex, depth, out):
    if depth > MAX_KEYWORD_DEPTH:
        return
    parts = split_alternatives(regex)
    if len(parts) > 1:
        for p in parts:
            collect_keyword_words(p, depth + 1, out)
        return
    only = parts[0]
    inner = core_group_body(only)
    if inner is not None:
        collect_keyword_words(inner, depth + 1, out)
        return
    word = literal_keyword_word(only)
    if word is not None and len(out) < MAX_KEYWORDS and word not in out:
        out.append(word)


def extract(obj, state):
    scope = obj.get("name")
    if not isinstance(scope, str):
        return
    match = obj.get("match")
    begin = obj.get("begin")
    end = obj.get("end")
    lower = scope.lower()
    if lower.startswith("keyword.") and isinstance(match, str):
        if len(state.keywords) < MAX_KEYWORDS:
            collect_keyword_words(match, 0, state.keywords)
    elif lower.startswith("comment.line") and isinstance(match, str):
        if len(state.line_comments) < MAX_LINE_COMMENTS:
            token = literal_prefix(match)
            if is_punct_token(token, MAX_LINE_TOKEN_CHARS) and token not in state.line_comments:
                state.line_comments.append(token)
    elif lower.startswith("comment.block") and isinstance(begin, str) and isinstance(end, str):
        if len(state.block_comments) < MAX_BLOCK_COMMENTS:
            open_token = literal_prefix(begin)
            close_token = literal_prefix(end)
            if is_punct_token(open_token, MAX_BLOCK_TOKEN_CHARS) and is_punct_token(close_token, MAX_BLOCK_TOKEN_CHARS):
                pair = (open_token, close_token)
                if pair not in state.block_comments:
                    state.block_comments.append(pair)
    elif lower.startswith("string.quoted"):
        state.saw_string_quoted = True
        if isinstance(begin, str):
            lit = literal_prefix(begin)
            if lit and lit[0] in "\"'" and lit[0] not in state.string_quotes:
                state.string_quotes.append(lit[0])


def push_all(stack, arr, _path):
    for i in range(len(arr) - 1, -1, -1):
        obj = arr[i]
        if isinstance(obj, dict):
            stack.append(obj)


def follow_include(obj, state, stack):
    include = obj.get("include")
    if not isinstance(include, str):
        return False
    if include.startswith("#"):
        key = include[1:]
        rule = state.repository.get(key)
        if not isinstance(rule, dict):
            return False
        if key in state.expanded_includes:
            return True
        state.expanded_includes.add(key)
        stack.append(rule)
        return True
    if include in ("$base", "$self"):
        if include in state.expanded_includes:
            return True
        state.expanded_includes.add(include)
        push_all(stack, state.root_patterns, "x")
        return True
    return False


def walk(state):
    stack = []
    push_all(stack, state.root_patterns, "patterns")
    while stack:
        obj = stack.pop()
        state.visited += 1
        if state.visited > MAX_PATTERNS_VISITED:
            return "budget"
        extract(obj, state)
        if not follow_include(obj, state, stack):
            nested = obj.get("patterns")
            if isinstance(nested, list):
                push_all(stack, nested, "x")


def parse(json_text, requested_id, extensions):
    if len(json_text) > MAX_JSON_CHARS:
        return ("TOO_LARGE",)
    try:
        root = json.loads(json_text)
    except ValueError:
        return ("NOT_JSON",)
    if not isinstance(root, dict):
        return ("NOT_GRAMMAR",)
    scope_name = root.get("scopeName")
    if not isinstance(scope_name, str) or not scope_name:
        return ("NOT_GRAMMAR",)
    root_patterns = root.get("patterns")
    if not isinstance(root_patterns, list):
        return ("NOT_GRAMMAR",)
    repository = root.get("repository")
    if not isinstance(repository, dict):
        repository = {}
    state = WalkState(root_patterns, repository)
    overflow = walk(state)
    if overflow is not None:
        return ("PATTERN_BUDGET_EXCEEDED",)
    grammar_name = root.get("name")
    if not isinstance(grammar_name, str) or not grammar_name:
        grammar_name = scope_name
    norm_exts = []
    for e in extensions:
        ne = normalize_extension(e)
        if ne and ne not in norm_exts:
            norm_exts.append(ne)
    if state.string_quotes:
        delims = list(state.string_quotes)
    elif state.saw_string_quoted:
        delims = ['"']
    else:
        delims = ['"', "'"]
    lang = dict(
        id=CUSTOM_ID_PREFIX + (requested_id.strip() or "grammar"),
        displayName=grammar_name,
        keywords=list(state.keywords),
        lineComments=list(state.line_comments),
        blockComments=list(state.block_comments),
        stringDelims=delims,
        extensions=norm_exts,
    )
    return ("OK", lang)


CHECKS = 0
FAILURES = 0


def check(name, actual, expected):
    global CHECKS, FAILURES
    CHECKS += 1
    if actual != expected:
        FAILURES += 1
        print("FAIL %s: expected %r, got %r" % (name, expected, actual))


def check_set(name, actual, expected):
    check(name, sorted(actual), sorted(expected))


# ---- hand-computed vectors ----

# extension normalization
check("ext.py", normalize_extension(".PY"), "py")
check("ext.star", normalize_extension("*.py"), "py")
check("ext.plain", normalize_extension("Rmd"), "rmd")
check("ext.dotstar", normalize_extension(".*ml"), "ml")

# literal prefix / comment tokens (hand derived)
check("lp.slash", literal_prefix("//.*$"), "//")
check("lp.hash", literal_prefix("#"), "#")
check("lp.dash", literal_prefix("--.*$"), "--")
check("lp.anchor", literal_prefix("^;"), ";")
check("lp.blockbegin", literal_prefix("/*"), "/")  # unescaped '*' is a metachar stop
check("lp.blockend", literal_prefix("\\*/"), "*/")  # escaped '*' + literal '/'
check("lp.classskip", literal_prefix("[(#)]%"), "%")
check("lp.quoteskip", literal_prefix("\\s*//"), "//")
check("lp.empty", literal_prefix(""), "")

# punct tokens
check("punct.line", is_punct_token("//", 3), True)
check("punct.long", is_punct_token("//--", 3), False)
check("punct.word", is_punct_token("ab", 3), False)
check("punct.block", is_punct_token("/*", 6), True)
check("punct.empty", is_punct_token("", 3), False)

# keyword word extraction (hand derived)
check("kw.plain", literal_keyword_word("if"), "if")
check("kw.short", literal_keyword_word("a"), None)
check("kw.anchor", literal_keyword_word("^end$"), "end")
check("kw.classreject", literal_keyword_word("\\d"), None)
check("kw.escapedletter", literal_keyword_word("\\w"), None)
check("kw.empty", literal_keyword_word(""), None)

# split alternatives (hand derived)
check("split.simple", split_alternatives("if|else|while"), ["if", "else", "while"])
# '(' raises depth, so the '|' inside the group does NOT split (Kotlin parity).
check("split.group", split_alternatives("\\b(if|else)\\b"), ["\\b(if|else)\\b"])
check("split.escaped", split_alternatives("a\\|b|c"), ["a\\|b", "c"])
check("split.nested", split_alternatives("(x|y)|z"), ["(x|y)", "z"])

# core group body (hand derived)
check("cgb.plain", core_group_body("\\b(if|else)\\b"), "if|else")
check("cgb.noncapture", core_group_body("^(?:function|end)$"), "function|end")
check("cgb.none", core_group_body("abc"), None)
# '(a)(b)': the first group's SUFFIX '(b)' is not wrapper plumbing -> None.
check("cgb.capture", core_group_body("(a)(b)"), None)

# full grammar: mini language (hand-computed expectations)
mini = json.dumps({
    "name": "MiniLang",
    "scopeName": "source.mini",
    "patterns": [
        {"include": "#control"},
        {"match": r"//.*$", "name": "comment.line.double-slash.mini"},
        {"begin": r"/\*", "end": r"\*/", "name": "comment.block.mini"},
        {"begin": '"', "end": '"', "name": "string.quoted.double.mini"},
        {"match": r"\b(readonly|const)\b", "name": "keyword.other.mini"},
    ],
    "repository": {
        "control": {
            "patterns": [
                {"match": r"\b(if|else|while)\b", "name": "keyword.control.mini"},
            ]
        },
    },
})
status, lang = parse(mini, "mini", [".mLang", "txt"])
check("mini.status", status, "OK")
check_set("mini.keywords", lang["keywords"], ["if", "else", "while", "readonly", "const"])
check("mini.lineComments", lang["lineComments"], ["//"])
check("mini.blockComments", lang["blockComments"], [("/*", "*/")])
check("mini.delims", lang["stringDelims"], ['"'])
check("mini.id", lang["id"], "custom_mini")
check("mini.name", lang["displayName"], "MiniLang")
check("mini.exts", lang["extensions"], ["mlang", "txt"])

# defaults: no string.quoted -> both quotes
g2 = json.dumps({"scopeName": "source.x", "patterns": [{"match": r"#.*$", "name": "comment.line.number-sign.x"}]})
status, lang = parse(g2, "x", ["x"])
check("g2.delims", lang["stringDelims"], ['"', "'"])
check("g2.lineComments", lang["lineComments"], ["#"])

# string.quoted present but begin not extractable -> default '"'
g3 = json.dumps({"scopeName": "source.x", "patterns": [{"begin": r"\\\"", "end": '"', "name": "string.quoted.other.x"}]})
status, lang = parse(g3, "x", [])
check("g3.delims", lang["stringDelims"], ['"'])

# single quotes win when present
g4 = json.dumps({"scopeName": "source.x", "patterns": [{"begin": "'", "end": "'", "name": "string.quoted.single.x"}]})
status, lang = parse(g4, "x", [])
check("g4.delims", lang["stringDelims"], ["'"])

# validity failures
check("bad.notjson", parse("this is not json", "x", [])[0], "NOT_JSON")
check("bad.array", parse("[1,2]", "x", [])[0], "NOT_GRAMMAR")
check("bad.noscope", parse(json.dumps({"patterns": []}), "x", [])[0], "NOT_GRAMMAR")
check("bad.nopatterns", parse(json.dumps({"scopeName": "source.x"}), "x", [])[0], "NOT_GRAMMAR")
check("bad.toobig", parse("x" * (MAX_JSON_CHARS + 1), "x", [])[0], "TOO_LARGE")

# include resolution: #repository resolved; external include ignored as a
# reference but the rule's OWN patterns are still walked (documented).
g5 = json.dumps({
    "scopeName": "source.inc",
    "patterns": [
        {"include": "#kw"},
        {"include": "source.external", "patterns": [{"match": r"\b(notThis)\b", "name": "keyword.x"}]},
    ],
    "repository": {
        "kw": {"patterns": [{"match": r"\b(yes)\b", "name": "keyword.control.x"}]},
    },
})
status, lang = parse(g5, "inc", [])
check("inc.keywords", sorted(lang["keywords"]), ["notThis", "yes"])

# a->b->a cycle TERMINATES via expanded-includes (no budget blow-up)
g6 = json.dumps({
    "scopeName": "source.cycle",
    "patterns": [{"include": "#a"}],
    "repository": {
        "a": {"patterns": [{"include": "#b"}]},
        "b": {"patterns": [{"include": "#a"}]},
    },
})
check("cycle", parse(g6, "c", [])[0], "OK")

# a LONG chain of DISTINCT includes exhausts the pattern budget
N = 5010
repo = {"r%d" % i: {"patterns": [{"include": "#r%d" % (i + 1)}]} for i in range(N)}
g7 = json.dumps({
    "scopeName": "source.chain",
    "patterns": [{"include": "#r0"}],
    "repository": repo,
})
check("chain.budget", parse(g7, "ch", [])[0], "PATTERN_BUDGET_EXCEEDED")

# grammar with empty pattern list is a valid (bare) language
g8 = json.dumps({"scopeName": "source.empty", "patterns": []})
status, lang = parse(g8, "e", [])
check("empty.ok", status, "OK")
check("empty.keywords", lang["keywords"], [])

print("TOTAL CHECKS: %d, TOTAL FAILURES: %d" % (CHECKS, FAILURES))
if FAILURES:
    sys.exit(1)
print("ALL PASS")
