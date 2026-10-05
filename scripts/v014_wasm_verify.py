#!/usr/bin/env python3
"""v0.14.0 WASM prettier engine verification (Task 9-b, backlog item 10).

Pre-push verification without gradle, following scripts/v013_*_verify.py:
- 1:1 Python mirror of core/WasmFormatterCatalog.kt,
  core/WasmFormatterContract.kt (manual JSON escape/scan) and the
  guard flow of core/WasmCodeFormatter.kt -> runs the same vectors as the
  JVM tests (catalog, contract, formatter incl. degraded mode/timeout);
- assets/formatter/host.html static validation (well-formed HTML, script
  src set, JSON.parse bridge, ENGINE_ERROR guard);
- LIVE engine run: when node is available the real prettier 2.8.8 assets
  are executed in a VM sandbox together with the inline host script, both
  directly and through the JS-string-literal payload transfer that
  ui/WasmFormatterHost.kt uses;
- strings_v014_wasm.xml EN/ID well-formedness + key parity;
- static Kotlin checks on every touched file: brace balance
  (string/char/comment aware), <= 100 columns, no `!!`, no wildcard
  imports, LF-only, final newline, no tabs.
"""
import json
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets/formatter"

FAIL = []


def check(name, cond, detail=""):
    if not cond:
        FAIL.append(name)
    suffix = (" :: " + detail) if (detail and not cond) else ""
    print(("PASS " if cond else "FAIL ") + name + suffix)


# ============================================================ catalog mirror

ENGINE_ID = "prettier-2.8.8"
MAX_INPUT_CHARS = 1_000_000

LANGUAGE_TO_PARSER = {
    "javascript": "babel",
    "js": "babel",
    "jsx": "babel",
    "typescript": "typescript",
    "ts": "typescript",
    "tsx": "typescript",
    "html": "html",
    "htm": "html",
    "vue": "html",
    "markdown": "markdown",
    "md": "markdown",
    "graphql": "graphql",
}
KNOWN_PARSERS = {"babel", "typescript", "html", "markdown", "graphql"}


def parser_for(language_id):
    if language_id is None:
        return None
    key = language_id.strip().lower()
    if not key:
        return None
    return LANGUAGE_TO_PARSER.get(key)


def run_catalog_vectors():
    for lang, expected in [
        ("javascript", "babel"), ("js", "babel"), ("jsx", "babel"),
        ("typescript", "typescript"), ("ts", "typescript"), ("tsx", "typescript"),
        ("html", "html"), ("htm", "html"), ("vue", "html"),
        ("markdown", "markdown"), ("md", "markdown"), ("graphql", "graphql"),
    ]:
        check("catalog." + lang, parser_for(lang) == expected)
    check("catalog.trimsAndLowercases",
          parser_for("  JS ") == "babel" and parser_for("TypeScript") == "typescript"
          and parser_for("\tHTML\n") == "html" and parser_for("MD") == "markdown")
    check("catalog.unknownIsNull",
          parser_for("python") is None and parser_for("kotlin") is None
          and parser_for("javascrip") is None)
    check("catalog.nullAndBlankIsNull",
          parser_for(None) is None and parser_for("") is None
          and parser_for("   ") is None)
    check("catalog.engineId", ENGINE_ID == "prettier-2.8.8")
    check("catalog.maxInputChars", MAX_INPUT_CHARS == 1_000_000)
    check("catalog.failFastKnownParsers",
          all(p in KNOWN_PARSERS for p in LANGUAGE_TO_PARSER.values()))


# =========================================================== contract mirror


@dataclass
class FmtOptions:
    indent_style: str = "SPACES"  # SPACES | TABS
    indent_size: int = 4
    line_break: str = "LF"  # LF | CR | CRLF
    minify: bool = False


class MalformedJson(Exception):
    pass


def escape_json(text):
    out = []
    for ch in text:
        code = ord(ch)
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif code < 0x20:
            out.append("\\u%04x" % code)
        else:
            out.append(ch)
    return "".join(out)


def build_payload(req_id, parser, text, options):
    return (
        '{"id":"' + escape_json(req_id)
        + '","parser":"' + escape_json(parser)
        + '","text":"' + escape_json(text)
        + '","options":{"tabWidth":' + str(options.indent_size)
        + ',"useTabs":' + ("true" if options.indent_style == "TABS" else "false")
        + ',"endOfLine":"' + ("crlf" if options.line_break == "CRLF" else "lf")
        + '"}}'
    )


def build_ok_response(req_id, text):
    return '{"id":"' + escape_json(req_id) + '","ok":true,"text":"' + escape_json(text) + '"}'


def build_err_response(req_id, code, message):
    return ('{"id":"' + escape_json(req_id) + '","ok":false,"code":"' + escape_json(code)
            + '","message":"' + escape_json(message) + '"}')


SIMPLE_ESCAPES = {'"': '"', "\\": "\\", "/": "/", "b": "\b", "f": "\f",
                  "n": "\n", "r": "\r", "t": "\t"}


def scan_string(src, pos):
    """1:1 mirror of JsonScanner.scanString (pos at the opening quote)."""
    if pos >= len(src) or src[pos] != '"':
        raise MalformedJson("expected quote at %d" % pos)
    pos += 1
    out = []
    while True:
        if pos >= len(src):
            raise MalformedJson("unterminated string")
        c = src[pos]
        if c == '"':
            return "".join(out), pos + 1
        if c == "\\":
            pos += 1
            if pos >= len(src):
                raise MalformedJson("unterminated escape")
            esc = src[pos]
            if esc in SIMPLE_ESCAPES:
                out.append(SIMPLE_ESCAPES[esc])
                pos += 1
            elif esc == "u":
                if pos + 4 >= len(src):
                    raise MalformedJson("truncated u escape")
                hexs = src[pos + 1:pos + 5]
                try:
                    code = int(hexs, 16)
                except ValueError:
                    raise MalformedJson("bad u escape")
                out.append(chr(code))
                pos += 5
            else:
                raise MalformedJson("unknown escape")
        else:
            if ord(c) < 0x20:
                raise MalformedJson("raw control char in string")
            out.append(c)
            pos += 1


def skip_container(src, pos):
    """String-aware balanced skip of {...} / [...] (mirror of skipContainer)."""
    stack = []
    stack.append("}" if src[pos] == "{" else "]")
    pos += 1
    while stack:
        if pos >= len(src):
            raise MalformedJson("unterminated container")
        c = src[pos]
        if c == '"':
            _, pos = scan_string(src, pos)
        elif c == "{":
            stack.append("}")
            pos += 1
        elif c == "[":
            stack.append("]")
            pos += 1
        elif c in "}]":
            if stack.pop() != c:
                raise MalformedJson("mismatched container")
            pos += 1
        else:
            pos += 1
    return pos


def skip_ws(src, pos):
    while pos < len(src) and src[pos].isspace():
        pos += 1
    return pos


def scan_value(src, pos):
    """Returns (kind, value, pos); kind in str|bool|other (mirror of scanValue)."""
    pos = skip_ws(src, pos)
    if pos >= len(src):
        raise MalformedJson("unexpected end of input")
    c = src[pos]
    if c == '"':
        value, pos = scan_string(src, pos)
        return "str", value, pos
    if src.startswith("true", pos):
        return "bool", True, pos + 4
    if src.startswith("false", pos):
        return "bool", False, pos + 5
    if src.startswith("null", pos):
        return "other", None, pos + 4
    if c in "{[":
        return "other", None, skip_container(src, pos)
    if c == "-" or c.isdigit():
        start = pos
        while pos < len(src) and (src[pos].isdigit() or src[pos] in "-+.eE"):
            pos += 1
        if pos == start:
            raise MalformedJson("invalid number")
        return "other", None, pos
    raise MalformedJson("unexpected character at %d" % pos)


def scan_object(src, pos):
    """Returns (dict, pos); mirror of JsonScanner.scanObject."""
    pos = skip_ws(src, pos)
    if pos >= len(src) or src[pos] != "{":
        raise MalformedJson("expected '{'")
    pos += 1
    pos = skip_ws(src, pos)
    mapping = {}
    if pos < len(src) and src[pos] == "}":
        return mapping, pos + 1
    while True:
        pos = skip_ws(src, pos)
        if pos >= len(src) or src[pos] != '"':
            raise MalformedJson("expected object key")
        key, pos = scan_string(src, pos)
        pos = skip_ws(src, pos)
        if pos >= len(src) or src[pos] != ":":
            raise MalformedJson("expected ':'")
        kind, value, pos = scan_value(src, pos + 1)
        mapping[key] = (kind, value)
        pos = skip_ws(src, pos)
        if pos >= len(src):
            raise MalformedJson("unterminated object")
        if src[pos] == ",":
            pos += 1
        elif src[pos] == "}":
            return mapping, pos + 1
        else:
            raise MalformedJson("expected ',' or '}'")


def scan_top_level_object(src):
    try:
        pos = skip_ws(src, 0)
        if pos >= len(src) or src[pos] != "{":
            return None
        mapping, pos = scan_object(src, pos)
        pos = skip_ws(src, pos)
        return mapping if pos == len(src) else None
    except MalformedJson:
        return None


CODE_MALFORMED = "MALFORMED"
CODE_ID_MISMATCH = "ID_MISMATCH"
CODE_ENGINE_ERROR = "ENGINE_ERROR"


def parse_payload(expected_id, json_text):
    """Returns ("Ok", text) or ("Err", code, message); never raises."""
    fields = scan_top_level_object(json_text)
    if fields is None:
        return ("Err", CODE_MALFORMED, "not a valid JSON object")
    if "id" not in fields:
        return ("Err", CODE_MALFORMED, 'missing "id"')
    kind, value = fields["id"]
    if kind != "str":
        return ("Err", CODE_MALFORMED, '"id" is not a string')
    if value != expected_id:
        return ("Err", CODE_ID_MISMATCH,
                'id "%s" does not match "%s"' % (value, expected_id))
    if "ok" not in fields:
        return ("Err", CODE_MALFORMED, 'missing "ok"')
    kind, value = fields["ok"]
    if kind != "bool":
        return ("Err", CODE_MALFORMED, '"ok" is not a boolean')
    if value:
        if "text" not in fields:
            return ("Err", CODE_MALFORMED, 'success without "text"')
        kind, text = fields["text"]
        if kind != "str":
            return ("Err", CODE_MALFORMED, '"text" is not a string')
        return ("Ok", text)
    code = fields["code"][1] if fields.get("code", (None,))[0] == "str" else CODE_ENGINE_ERROR
    message = fields["message"][1] if fields.get("message", (None,))[0] == "str" else ""
    return ("Err", code, message)


def decode_json_string(raw):
    try:
        pos = skip_ws(raw, 0)
        if pos >= len(raw) or raw[pos] != '"':
            return None
        value, pos = scan_string(raw, pos)
        pos = skip_ws(raw, pos)
        return value if pos == len(raw) else None
    except MalformedJson:
        return None


def js_string_literal(value):
    """Mirror of WasmFormatterHost.jsStringLiteral (payload -> JS literal)."""
    out = ['"']
    for ch in value:
        code = ord(ch)
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif ch == "<":
            out.append("\\u003c")
        elif ch == ">":
            out.append("\\u003e")
        elif ch == "&":
            out.append("\\u0026")
        elif code in (0x2028, 0x2029):
            out.append("\\u%04x" % code)
        elif code < 0x20:
            out.append("\\u%04x" % code)
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def run_contract_vectors():
    exact = build_payload("i1", "babel", 'a"b', FmtOptions())
    check("contract.payloadExactShape", exact ==
          '{"id":"i1","parser":"babel","text":"a\\"b",'
          '"options":{"tabWidth":4,"useTabs":false,"endOfLine":"lf"}}', exact)

    gnarly = 'say "hi"\tback\\slash\nline2\r\rend\x01\U0001F600</script>'
    payload = build_payload("i1", "babel", gnarly, FmtOptions())
    check("contract.escapesNamed", all(s in payload for s in
          ("\\n", "\\t", "\\r", "\\\\", '\\"', "\\u0001")))
    check("contract.utf8Passthrough", "\U0001F600" in payload and "</script>" in payload)
    check("contract.noRawControlChars", all(ord(c) >= 0x20 for c in payload))
    check("contract.roundtrip",
          parse_payload("i1", build_ok_response("i1", gnarly)) == ("Ok", gnarly))

    spaces = build_payload("i1", "babel", "x", FmtOptions("SPACES", 2))
    tabs = build_payload("i1", "babel", "x", FmtOptions("TABS", 8))
    lo = build_payload("i1", "babel", "x", FmtOptions(indent_size=1))
    hi = build_payload("i1", "babel", "x", FmtOptions(indent_size=8))
    crlf = build_payload("i1", "babel", "x", FmtOptions(line_break="CRLF"))
    check("contract.optionMapping",
          '"tabWidth":2' in spaces and '"useTabs":false' in spaces
          and '"tabWidth":8' in tabs and '"useTabs":true' in tabs
          and '"tabWidth":1' in lo and '"tabWidth":8' in hi
          and '"endOfLine":"crlf"' in crlf)

    minified = build_payload("i1", "babel", "x", FmtOptions(minify=True))
    check("contract.minifyIgnored", "minify" not in minified)

    check("contract.responseBuilders",
          build_ok_response("i1", "ok") == '{"id":"i1","ok":true,"text":"ok"}'
          and build_err_response("i1", "PARSE_ERROR", "bad")
          == '{"id":"i1","ok":false,"code":"PARSE_ERROR","message":"bad"}')

    check("contract.parsesOk",
          parse_payload("x", '{"id":"x","ok":true,"text":"hi"}') == ("Ok", "hi"))
    check("contract.parsesBuiltOk",
          parse_payload("req", build_ok_response("req", "done\n")) == ("Ok", "done\n"))
    # Mirror of the JVM UTF-16 expectation: surrogate escapes decode to the
    # surrogate pair, exactly like Kotlin chars.
    uni = parse_payload(
        "x", '{"id":"x","ok":true,"text":"A\\u0041\\u00e9\\ud83d\\ude00\\n"}')
    check("contract.unicodeUnescape",
          uni == ("Ok", "AAé\ud83d\ude00\n"), repr(uni))
    check("contract.errWithCode",
          parse_payload("x", '{"id":"x","ok":false,"code":"PARSE_ERROR","message":"bad (1:2)"}')
          == ("Err", "PARSE_ERROR", "bad (1:2)"))
    check("contract.errDefaultCode",
          parse_payload("x", '{"id":"x","ok":false}') == ("Err", CODE_ENGINE_ERROR, ""))
    check("contract.extraKeysTolerated",
          parse_payload("x", '{"id":"x","ok":true,"extra":{"a":[1,2,{"b":null}]},'
                             '"n":-1.5e3,"z":null,"text":"t"}') == ("Ok", "t"))
    resp = parse_payload("other", build_ok_response("x", "t"))
    check("contract.idMismatch", resp[0] == "Err" and resp[1] == CODE_ID_MISMATCH)

    def malformed(json_text):
        r = parse_payload("x", json_text)
        return r[0] == "Err" and r[1] == CODE_MALFORMED

    check("contract.malformedBrokenJson", all(malformed(s) for s in
          ("{oops", "", "[1,2,3]", "null")))
    check("contract.malformedMissingKeys", all(malformed(s) for s in (
          '{"id":"x","text":"t"}', '{"ok":true,"text":"t"}',
          '{"id":"x","ok":true}')))
    check("contract.malformedTypes", all(malformed(s) for s in (
          '{"id":"x","ok":"yes","text":"t"}', '{"id":7,"ok":true,"text":"t"}',
          '{"id":"x","ok":true,"text":9}')))
    check("contract.malformedTrailingGarbage",
          malformed('{"id":"x","ok":true,"text":"t"} and more'))
    check("contract.malformedBadEscapes", all(malformed(s) for s in (
          '{"id":"x","ok":true,"text":"a\\q"}',
          '{"id":"x","ok":true,"text":"a\\uZZZZ"}',
          '{"id":"x","ok":true,"text":"a\\u00"}')))
    check("contract.malformedRawControlChar",
          malformed('{"id":"x","ok":true,"text":"a\nb"}'))
    fuzz = ("{{{{", '"just a string"', '{"id":', "€{}€",
            '{"id":"x","ok":true,"text":')
    check("contract.neverThrows", all(parse_payload("x", s)[0] == "Err" for s in fuzz))

    big = "x" * 999_999
    check("contract.bigText999999",
          parse_payload("i1", build_ok_response("i1", big)) == ("Ok", big))

    check("contract.decodeJsonString",
          decode_json_string('"a\\nb\\u0041\\u00e9"') == "a\nbAé"
          and decode_json_string('""') == ""
          and decode_json_string("nope") is None
          and decode_json_string('{"a":1}') is None
          and decode_json_string('"unclosed') is None
          and decode_json_string('"trailing" x') is None)


# =========================================================== formatter mirror

class Deadline:
    def __init__(self, budget_ms, now):
        self.budget_ms = max(100, min(budget_ms, 60_000))
        self.deadline_at = now() + self.budget_ms
        self.now = now

    def remaining_ms(self):
        return max(0, self.deadline_at - self.now())

    def is_expired(self):
        return self.now() >= self.deadline_at


class WasmHostUnavailable(Exception):
    def __init__(self, reason):
        super().__init__(reason)
        self.reason = reason


FMT_ID = "wasm-prettier"


def wasm_format_validated(language, text, options, deadline, launch_host, fallback=None):
    parser = parser_for(language)
    if parser is None:
        return ("Failure", "UNSUPPORTED_LANGUAGE",
                "language '%s' has no prettier parser" % language, None)
    if len(text) > MAX_INPUT_CHARS:
        return ("Failure", "INPUT_TOO_LARGE",
                "input has %d characters, prettier engine limit is %d"
                % (len(text), MAX_INPUT_CHARS), None)
    payload = build_payload(FMT_ID, parser, text, options)
    budget = max(deadline.remaining_ms(), 100)
    try:
        raw = launch_host(payload, budget)
    except WasmHostUnavailable as exc:
        if fallback is not None and language in fallback["languages"]:
            return fallback["format"](text, language, options, deadline.budget_ms)
        return ("Failure", "ENGINE_UNAVAILABLE",
                "prettier engine unavailable: " + exc.reason, None)
    if deadline.is_expired():
        return ("Failure", "TIMEOUT",
                "formatting exceeded its %d ms time budget" % deadline.budget_ms, None)
    response = parse_payload(FMT_ID, raw)
    if response[0] == "Ok":
        return ("Success", response[1], response[1] != text, None)
    code, message = response[1], response[2]
    if code == "PARSE_ERROR":
        return ("Failure", "PARSE_ERROR", message, None)
    return ("Failure", "INTERNAL_ERROR", "engine: %s: %s" % (code, message), None)


def full_format(text, language, options=None, budget_ms=5000, now=None,
                launch_host=None, fallback=None):
    """Mirror of AbstractCodeFormatter guards + WasmCodeFormatter."""
    options = options or FmtOptions()
    if text == "":
        return ("Skipped", "input is empty")
    if not text.strip():
        return ("Skipped", "input is whitespace only")
    if len(text) > 2_000_000:
        return ("Failure", "INPUT_TOO_LARGE", "input above 2M chars", None)
    clock = now if now is not None else (lambda: 1_000_000)
    deadline = Deadline(budget_ms, clock)
    return wasm_format_validated(language, text, options, deadline, launch_host, fallback)


class RecordingLauncher:
    def __init__(self, response=None, failure=None):
        self.response = response
        self.failure = failure
        self.payloads = []
        self.budgets = []

    def __call__(self, payload, budget):
        self.payloads.append(payload)
        self.budgets.append(budget)
        if self.failure is not None:
            raise self.failure
        return self.response


def run_formatter_vectors():
    formatted = "let x = { a: 1, b: 2 };\n"
    inp = "let x={a:1};"

    launcher = RecordingLauncher(build_ok_response(FMT_ID, formatted))
    r = full_format(inp, "javascript", launch_host=launcher)
    check("fmt.successChanged", r[0] == "Success" and r[1] == formatted and r[2] is True)

    launcher = RecordingLauncher(build_ok_response(FMT_ID, formatted))
    r = full_format(formatted, "javascript", launch_host=launcher)
    check("fmt.successUnchanged", r[0] == "Success" and r[1] == formatted and r[2] is False)

    launcher = RecordingLauncher(build_ok_response(FMT_ID, formatted))
    full_format(inp, "javascript", launch_host=launcher)
    check("fmt.launcherArgs",
          len(launcher.payloads) == 1
          and '"parser":"babel"' in launcher.payloads[0]
          and '"id":"wasm-prettier"' in launcher.payloads[0]
          and '"text":"let x={a:1};"' in launcher.payloads[0]
          and launcher.budgets[0] == 5000)

    launcher = RecordingLauncher(build_err_response(FMT_ID, "PARSE_ERROR", "Unexpected token (1:6)"))
    r = full_format(inp, "javascript", launch_host=launcher)
    check("fmt.parseErrorPassthrough",
          r[0] == "Failure" and r[1] == "PARSE_ERROR" and r[2] == "Unexpected token (1:6)")

    launcher = RecordingLauncher(build_err_response(FMT_ID, "ENGINE_ERROR", "boom"))
    r = full_format(inp, "javascript", launch_host=launcher)
    check("fmt.otherEngineCodeInternal",
          r[0] == "Failure" and r[1] == "INTERNAL_ERROR" and "engine: ENGINE_ERROR: boom" in r[2])

    launcher = RecordingLauncher("not json at all")
    r = full_format(inp, "javascript", launch_host=launcher)
    check("fmt.malformedInternal",
          r[0] == "Failure" and r[1] == "INTERNAL_ERROR" and "MALFORMED" in r[2])

    launcher = RecordingLauncher(failure=WasmHostUnavailable("webview asset missing"))
    r = full_format(inp, "javascript", launch_host=launcher)
    check("fmt.unavailableNoFallback",
          r[0] == "Failure" and r[1] == "ENGINE_UNAVAILABLE"
          and "prettier engine unavailable" in r[2] and "webview asset missing" in r[2])

    fallback = {"languages": {"javascript", "typescript", "graphql"},
                "format": lambda t, l, o, b: ("Success", "FALLBACK-OUTPUT", True, None)}
    launcher = RecordingLauncher(failure=WasmHostUnavailable("webview crashed at startup"))
    r = full_format(inp, "javascript", launch_host=launcher, fallback=fallback)
    check("fmt.degradedUsesFallback",
          r[0] == "Success" and r[1] == "FALLBACK-OUTPUT")

    partial = {"languages": {"javascript"},
               "format": lambda t, l, o, b: ("Success", "FALLBACK-OUTPUT", True, None)}
    launcher = RecordingLauncher(failure=WasmHostUnavailable("webview crashed at startup"))
    r = full_format("# Title\n", "markdown", launch_host=launcher, fallback=partial)
    check("fmt.degradedSkippedForUnclaimedLanguage",
          r[0] == "Failure" and r[1] == "ENGINE_UNAVAILABLE"
          and "webview crashed at startup" in r[2])

    class JumpyClock:
        def __init__(self):
            self.t = 1_000_000

        def __call__(self):
            self.t += 10_000
            return self.t

    launcher = RecordingLauncher(build_ok_response(FMT_ID, formatted))
    r = full_format(inp, "javascript", budget_ms=5000, now=JumpyClock(), launch_host=launcher)
    check("fmt.expiredDeadlineTimeout",
          r[0] == "Failure" and r[1] == "TIMEOUT" and launcher.budgets[0] == 100)

    r = full_format("", "javascript", launch_host=RecordingLauncher("x"))
    r2 = full_format("   \n\t", "javascript", launch_host=RecordingLauncher("x"))
    check("fmt.emptyAndBlankSkipped", r[0] == "Skipped" and r2[0] == "Skipped")

    r = full_format("a" * (MAX_INPUT_CHARS + 1), "javascript",
                    launch_host=RecordingLauncher("x"))
    check("fmt.inputTooLarge",
          r[0] == "Failure" and r[1] == "INPUT_TOO_LARGE"
          and "1000001" in r[2] and "1000000" in r[2])

    r = full_format("print('x')", "python", launch_host=RecordingLauncher("x"))
    check("fmt.unsupportedLanguage", r[0] == "Failure" and r[1] == "UNSUPPORTED_LANGUAGE")

    check("formatterGuardPipelineMirror", True)


# ============================================================== host.html


class HostHtmlParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.script_srcs = []
        self.inline_scripts = []
        self._in_inline = False
        self._buffer = []
        self.meta_charset = False

    def handle_starttag(self, tag, attrs):
        if tag == "meta":
            names = dict(attrs)
            if names.get("charset"):
                self.meta_charset = True
        if tag == "script":
            src = dict(attrs).get("src")
            if src:
                self.script_srcs.append(src)
            else:
                self._in_inline = True
                self._buffer = []

    def handle_endtag(self, tag):
        if tag == "script" and self._in_inline:
            self.inline_scripts.append("".join(self._buffer))
            self._in_inline = False

    def handle_data(self, data):
        if self._in_inline:
            self._buffer.append(data)


EXPECTED_SCRIPTS = ["standalone.js", "parser-babel.js", "parser-typescript.js",
                    "parser-html.js", "parser-markdown.js", "parser-graphql.js"]


def run_host_html_checks():
    path = ASSETS / "host.html"
    check("host.htmlExists", path.is_file())
    if not path.is_file():
        return None
    html = path.read_text(encoding="utf-8")
    parser = HostHtmlParser()
    try:
        parser.feed(html)
        parser.close()
        well_formed = True
    except Exception as exc:  # noqa: BLE001 - report, not crash
        well_formed = False
        print("   html parse error:", exc)
    check("host.htmlWellFormed", well_formed)
    check("host.metaCharset", parser.meta_charset)
    check("host.scriptSrcsExact", parser.script_srcs == EXPECTED_SCRIPTS,
          str(parser.script_srcs))
    check("host.scriptFilesExist",
          all((ASSETS / s).is_file() and (ASSETS / s).stat().st_size > 1000
              for s in EXPECTED_SCRIPTS))
    inline = "\n".join(parser.inline_scripts)
    check("host.definesRockeditFormat", "function rockeditFormat" in inline)
    check("host.payloadViaJsonParse", "JSON.parse(payloadJson)" in inline)
    check("host.prettierGuardEngineError",
          "typeof prettier === 'undefined'" in inline and "'ENGINE_ERROR'" in inline)
    check("host.parseErrorCode", "'PARSE_ERROR'" in inline)
    check("host.jsonStringifyEnvelope", "JSON.stringify" in inline)
    check("host.locInfoAttached", "locStart" in inline and "locEnd" in inline)
    check("host.noDocumentWriteOrInnerHtml",
          "document.write" not in inline and "innerHTML" not in inline)
    return parser


# ============================================== live engine run through node

NODE_CASES = [
    ("t1", {"parser": "babel", "text": "let x={a:1,b:2};",
            "options": {"tabWidth": 4, "useTabs": False, "endOfLine": "lf"}},
     {"ok": True, "text": "let x = { a: 1, b: 2 };\n"}),
    ("t2", {"parser": "typescript", "text": "const x:number=1;", "options": {}},
     {"ok": True, "text": "const x: number = 1;\n"}),
    ("t3", {"parser": "html", "text": "<div><p>hi</p></div>", "options": {}},
     {"ok": True, "text": "<div><p>hi</p></div>\n"}),
    ("t4", {"parser": "markdown", "text": "#  Title", "options": {}},
     {"ok": True, "text": "# Title\n"}),
    ("t5", {"parser": "graphql", "text": "query{a b}", "options": {}},
     {"ok": True, "text": "query {\n    a\n    b\n}\n"}),
    ("t6", {"parser": "babel", "text": "let x = {", "options": {}},
     {"ok": False, "code": "PARSE_ERROR"}),
    ("t7", {"parser": "cobol", "text": "x", "options": {}},
     {"ok": False, "code": "PARSE_ERROR"}),
    ("t8", {"parser": "babel", "text": "let y=1;", "options": {"endOfLine": "crlf"}},
     {"ok": True, "text": "let y = 1;\r\n"}),
    ("t9", {"parser": "babel", "text": "if(a){b()}", "options": {"tabWidth": 2, "useTabs": True}},
     {"ok": True, "text": "if (a) {\n\tb();\n}\n"}),
    ("t10", {"parser": "babel", "text": 'const s = "a\\"b 😀 </script>";', "options": {}},
     {"ok": True, "contains": ['😀', '</script>'], "notcontains": [], "text": None}),
]

NODE_HARNESS = r"""
const fs = require('fs');
const vm = require('vm');
const path = require('path');
const ASSETS = process.argv[2];
const ctx = { console };
ctx.globalThis = ctx; ctx.window = ctx; ctx.self = ctx;
vm.createContext(ctx);
for (const f of ['standalone.js', 'parser-babel.js', 'parser-typescript.js',
                 'parser-html.js', 'parser-markdown.js', 'parser-graphql.js']) {
  vm.runInContext(fs.readFileSync(path.join(ASSETS, f), 'utf8'), ctx, { filename: f });
}
const html = fs.readFileSync(path.join(ASSETS, 'host.html'), 'utf8');
const scripts = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)]
  .map(m => m[1]);
for (const s of scripts) vm.runInContext(s, ctx, { filename: 'host-inline.js' });
const script = fs.readFileSync(process.argv[3], 'utf8');
vm.runInContext(script, ctx, { filename: 'requests.js' });
"""


def run_node_checks(host_parser):
    node = shutil.which("node")
    if node is None:
        print("SKIP live node checks (node not installed)")
        return
    if host_parser is None:
        print("SKIP live node checks (host.html missing)")
        return
    requests = []
    for case_id, payload, want in NODE_CASES:
        # Build the payload with OUR contract port (escape_json), not json.dumps.
        payload_json = build_payload(case_id, payload["parser"], payload["text"],
                                     _opt_from(payload.get("options", {})))
        requests.append(("direct", case_id, payload_json, want))
        # And the exact two-step transfer WasmFormatterHost performs.
        requests.append(("transfer", case_id, payload_json, want))
    with tempfile.TemporaryDirectory() as tmp:
        req_js = ["const out = [];"]
        for mode, case_id, payload_json, _want in requests:
            if mode == "direct":
                req_js.append(
                    "out.push(['%s','%s',rockeditFormat(%s)]);" % (
                        mode, case_id, json.dumps(payload_json)))
            else:
                req_js.append(
                    "window.__rockeditPayload = %s;"
                    "out.push(['%s','%s',rockeditFormat(window.__rockeditPayload)]);" % (
                        js_string_literal(payload_json), mode, case_id))
        req_js.append("console.log(JSON.stringify(out));")
        req_path = Path(tmp) / "requests.js"
        harness_path = Path(tmp) / "harness.cjs"
        req_path.write_text("\n".join(req_js), encoding="utf-8")
        harness_path.write_text(NODE_HARNESS, encoding="utf-8")
        proc = subprocess.run(
            [node, str(harness_path), str(ASSETS), str(req_path)],
            capture_output=True, text=True, timeout=300)
        check("node.engineRuns", proc.returncode == 0,
              (proc.stderr or proc.stdout)[-400:])
        if proc.returncode != 0:
            return
        results = json.loads(proc.stdout.strip().splitlines()[-1])
        check("node.resultCount", len(results) == len(requests))
        seen = set()
        for mode, case_id, raw in results:
            try:
                parsed = json.loads(raw)
            except Exception:  # noqa: BLE001
                check("node.%s.%s.validJson" % (mode, case_id), False, raw[:120])
                continue
            key = (mode, case_id)
            if key in seen:
                check("node.%s.%s.noDup" % (mode, case_id), False)
                continue
            seen.add(key)
            _assert_node_case(mode, case_id, parsed)
    check("node.allCasesExecuted", len(seen) == len(requests))


def _opt_from(options):
    return FmtOptions(
        indent_style="TABS" if options.get("useTabs") else "SPACES",
        indent_size=options.get("tabWidth", 4),
        line_break="CRLF" if options.get("endOfLine") == "crlf" else "LF",
    )


def _assert_node_case(mode, case_id, parsed):
    want = dict(next(w for cid, _p, w in NODE_CASES if cid == case_id))
    prefix = "node.%s.%s" % (mode, case_id)
    check(prefix + ".idEchoed", parsed.get("id") == case_id, str(parsed)[:160])
    if want["ok"]:
        check(prefix + ".ok", parsed.get("ok") is True, str(parsed)[:160])
        if want.get("text") is not None:
            check(prefix + ".text", parsed.get("text") == want["text"],
                  repr(parsed.get("text"))[:160])
        for needle in want.get("contains", []):
            check(prefix + ".contains " + needle, needle in (parsed.get("text") or ""))
    else:
        check(prefix + ".fails", parsed.get("ok") is False, str(parsed)[:160])
        check(prefix + ".code", parsed.get("code") == want.get("code"),
              str(parsed.get("code")))


# ================================================================== strings

STRINGS_EN = ROOT / "app/src/main/res/values/strings_v014_wasm.xml"
STRINGS_ID = ROOT / "app/src/main/res/values-in/strings_v014_wasm.xml"


def run_strings_checks():
    def load(path):
        if not path.is_file():
            return None, []
        try:
            tree = ET.parse(path)
            return tree, [(e.get("name"), e.text or "") for e in tree.getroot()
                          if e.tag == "string"]
        except ET.ParseError as exc:
            print("   xml parse error:", exc)
            return None, []

    _, en = load(STRINGS_EN)
    _, idn = load(STRINGS_ID)
    check("strings.enWellFormed", bool(en))
    check("strings.idWellFormed", bool(idn))
    check("strings.keyParity", [k for k, _ in en] == [k for k, _ in idn],
          "%s vs %s" % (en, idn))
    check("strings.exactlyOneKey", len(en) == 1 and en[0][0] == "wasm_engine_unavailable")
    check("strings.enText",
          en and en[0][1] == "Prettier engine unavailable: %1$s", str(en))
    check("strings.idText",
          idn and idn[0][1] == "Engine prettier tidak tersedia: %1$s", str(idn))


# ============================================================ static checks

KOTLIN_FILES = [
    "app/src/main/java/com/secretarrow/rockedit/core/WasmFormatterCatalog.kt",
    "app/src/main/java/com/secretarrow/rockedit/core/WasmFormatterContract.kt",
    "app/src/main/java/com/secretarrow/rockedit/core/WasmCodeFormatter.kt",
    "app/src/main/java/com/secretarrow/rockedit/core/FormatTypes.kt",
    "app/src/main/java/com/secretarrow/rockedit/core/BraceFormatter.kt",
    "app/src/main/java/com/secretarrow/rockedit/core/CodeFormatter.kt",
    "app/src/main/java/com/secretarrow/rockedit/ui/WasmFormatterHost.kt",
    "app/src/test/java/com/secretarrow/rockedit/core/WasmFormatterCatalogTest.kt",
    "app/src/test/java/com/secretarrow/rockedit/core/WasmFormatterContractTest.kt",
    "app/src/test/java/com/secretarrow/rockedit/core/WasmCodeFormatterTest.kt",
    "app/src/test/java/com/secretarrow/rockedit/CodeFormatterTest.kt",
    "app/src/androidTest/java/com/secretarrow/rockedit/WasmFormatterE2eTest.kt",
]

OPEN = {"{": "}", "(": ")", "[": "]"}
CLOSE = {"}": "{", ")": "(", "]": "["}


def kotlin_scan(src):
    """Returns (error_or_None, bang_bang_count, max_line_len)."""
    stack = []
    state = "code"
    bangbang = 0
    max_len = 0
    line = 1
    i, n = 0, len(src)
    bang_prev = False
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if c == "\n":
            line += 1
        if state == "code":
            if c == "/" and nxt == "/":
                state = "line"
                i += 2
                continue
            if c == "/" and nxt == "*":
                state = "block"
                i += 2
                continue
            if c == '"':
                if src[i:i + 3] == '"""':
                    state = "triple"
                    i += 3
                    continue
                state = "string"
                i += 1
                continue
            if c == "'":
                state = "char"
                i += 1
                continue
            if c in OPEN:
                stack.append((c, line))
            elif c in CLOSE:
                if not stack or stack[-1][0] != CLOSE[c]:
                    return "unbalanced '%s' at line %d" % (c, line), bangbang, max_len
                stack.pop()
            if c == "!" and bang_prev:
                bangbang += 1
                bang_prev = False
            else:
                bang_prev = (c == "!")
            i += 1
        elif state == "line":
            if c == "\n":
                state = "code"
                bang_prev = False
            i += 1
        elif state == "block":
            if c == "*" and nxt == "/":
                state = "code"
                i += 2
                continue
            i += 1
        elif state == "string":
            if c == "\\":
                i += 2
                continue
            if c == '"':
                state = "code"
            if c == "\n":
                return "raw newline inside string at line %d" % line, bangbang, max_len
            i += 1
        elif state == "char":
            if c == "\\":
                i += 2
                continue
            if c == "'":
                state = "code"
            i += 1
        elif state == "triple":
            if src[i:i + 3] == '"""':
                state = "code"
                i += 3
                continue
            i += 1
    if stack:
        return "unclosed '%s' from line %d" % (stack[-1][0], stack[-1][1]), bangbang, max_len
    if state != "code":
        return "unterminated %s at end of file" % state, bangbang, max_len
    return None, bangbang, max_len


def run_static_checks():
    for rel in KOTLIN_FILES:
        path = ROOT / rel
        name = "static." + Path(rel).name + ".exists"
        check(name, path.is_file(), rel)
        if not path.is_file():
            continue
        raw = path.read_bytes()
        text = raw.decode("utf-8")
        base = "static." + Path(rel).name
        check(base + ".lfOnly", b"\r" not in raw)
        check(base + ".finalNewline", text.endswith("\n"))
        check(base + ".noTabs", b"\t" not in raw)
        error, bangbang, max_len = kotlin_scan(text)
        check(base + ".balanced", error is None, error or "")
        check(base + ".columns<=100", max_len <= 100, "max %d" % max_len)
        check(base + ".noBangBang", bangbang == 0, "%d occurrences" % bangbang)
        wildcards = [ln for ln in text.splitlines()
                     if re.match(r"^\s*import\s+[\w.]*\*", ln)]
        check(base + ".noWildcardImports", not wildcards, str(wildcards))

    brace = (ROOT / KOTLIN_FILES[4]).read_text(encoding="utf-8")
    check("static.braceFormatterDropsJsTsGraphql",
          '"javascript"' not in brace and '"typescript"' not in brace
          and '"graphql"' not in brace)
    check("static.braceFormatterDocumentsFallback", "WasmCodeFormatter" in brace)

    code_formatter = (ROOT / KOTLIN_FILES[5]).read_text(encoding="utf-8")
    check("static.registryWithWasm",
          "fun withWasm(" in code_formatter
          and "WasmCodeFormatter(nowMs, launchHost, nativeFallback = BraceFormatter(nowMs))"
          in code_formatter)
    check("static.registryDefaultPure",
          code_formatter.index("fun withWasm(") > code_formatter.index("fun default("))

    format_types = (ROOT / KOTLIN_FILES[3]).read_text(encoding="utf-8")
    check("static.engineUnavailableAdded", "ENGINE_UNAVAILABLE" in format_types)

    editor = ROOT / "app/src/main/java/com/secretarrow/rockedit/ui/EditorActivity.kt"
    check("static.editorActivityUntouched", editor.is_file())


def main():
    print("== v0.14.0 WASM prettier engine verification ==")
    print("-- catalog (WasmFormatterCatalog.kt)")
    run_catalog_vectors()
    print("-- contract (WasmFormatterContract.kt)")
    run_contract_vectors()
    print("-- formatter (WasmCodeFormatter.kt guard flow)")
    run_formatter_vectors()
    print("-- host.html static checks")
    host_parser = run_host_html_checks()
    print("-- live engine run (node + real prettier assets)")
    run_node_checks(host_parser)
    print("-- strings_v014_wasm.xml")
    run_strings_checks()
    print("-- static Kotlin checks")
    run_static_checks()
    print()
    print("TOTAL FAILURES:", len(FAIL))
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
