#!/usr/bin/env python3
"""1:1 Python mirror of core/LoopbackRedirectServer.kt parsing/state rules
(v0.16.0 OAuth loopback flow) to verify the Kotlin test expectations before
CI — same technique as scripts/v014_pdf_verify.py.

Mirrored surface:
  - parseCallback(query): x-www-form-urlencoded query → callback dict
    (first occurrence wins, unknown params ignored, empty value → null,
    malformed % escape keeps the RAW value — mirrors the Kotlin catch of
    URLDecoder's IllegalArgumentException).
  - decodeFormValue: '+' → space, %XX hex; Java URLDecoder throws IAE for
    illegal/incomplete escapes (→ raw fallback), replaces bad UTF-8 with
    U+FFFD — urllib.parse.unquote_plus(errors="replace") matches the second
    half, the explicit % scan mirrors the first.
  - handleRequestLine(line): GET-only, HTTP/ version required, query
    required → 200 record / 405 / 400 exactly like handleRequest().
  - OAuthState.matches / OAuthState.HEX_LENGTH and server constants.
"""
import re
import sys
import urllib.parse

HEX_LENGTH = 32
PREFERRED_PORT = 8642
DEFAULT_TIMEOUT_MS = 300_000
SOCKET_TIMEOUT_MS = 500

HEXDIGITS = set("0123456789abcdefABCDEF")
HEX32 = re.compile(r"^[0-9a-f]{32}$")

FAIL = []
PASSED = 0


def check(name, cond):
    global PASSED
    if cond:
        PASSED += 1
    else:
        FAIL.append(name)
        print("FAIL:", name)


def decode_form_value(raw):
    """Java URLDecoder.decode(raw, "UTF-8") + Kotlin raw-fallback semantics."""
    i = 0
    while i < len(raw):
        if raw[i] == "%" and not (
            i + 2 < len(raw) and raw[i + 1] in HEXDIGITS and raw[i + 2] in HEXDIGITS
        ):
            return raw  # illegal/incomplete escape → Kotlin catch keeps raw
        i += 1
    return urllib.parse.unquote_plus(raw, errors="replace")


def parse_callback(query):
    """Mirror of LoopbackRedirectServer.parseCallback (empty → None)."""
    values = {"code": None, "error": None, "error_description": None, "state": None}
    for pair in query.split("&"):
        if pair == "":
            continue
        eq = pair.find("=")
        raw_name = pair if eq < 0 else pair[:eq]
        raw_value = "" if eq < 0 else pair[eq + 1:]
        name = decode_form_value(raw_name)
        if name in values and values[name] is None:
            values[name] = decode_form_value(raw_value)  # first occurrence wins
    return {k: (None if v == "" else v) for k, v in values.items()}


def handle_request_line(line):
    """Mirror of handleRequest(): returns (status, callback_or_None)."""
    tokens = line.split(" ")
    if len(tokens) < 3:
        return 400, None
    if tokens[0].upper() != "GET":
        return 405, None
    if not tokens[2].upper().startswith("HTTP/"):
        return 400, None
    target = tokens[1]
    q = target.find("?")
    if q < 0:
        return 400, None
    return 200, parse_callback(target[q + 1:])


def state_matches(expected, received):
    """Semantic mirror of the constant-time Kotlin comparison."""
    if expected is None or received is None:
        return False
    return expected == received


# ---------------------------------------------------------------- vectors

# --- query parsing (parseCallback) ---
c = parse_callback("code=abc&state=xyz")
check("basicCodeAndState", c == {"code": "abc", "error": None, "error_description": None, "state": "xyz"})

c = parse_callback("error=access_denied&error_description=Nope&state=s")
check("errorAndDescription", c["error"] == "access_denied" and c["error_description"] == "Nope" and c["code"] is None)

c = parse_callback("code=a%2Fb+c&state=rockedit%20x")
check("percentAndPlusDecode", c["code"] == "a/b c" and c["state"] == "rockedit x")

c = parse_callback("code=a%2Bb")
check("encodedPlusStaysPlus", c["code"] == "a+b")

c = parse_callback("code=&state=s")
check("emptyCodeNormalizesToNone", c["code"] is None and c["state"] == "s")

c = parse_callback("error=&state=s")
check("emptyErrorNormalizesToNone", c["error"] is None)

c = parse_callback("foo=bar&code=c&state=s")
check("unknownParamIgnored", c["code"] == "c" and c["state"] == "s" and "foo" not in c)

c = parse_callback("code=first&code=second&state=one&state=two")
check("duplicateFirstWins", c["code"] == "first" and c["state"] == "one")

c = parse_callback("code=&code=x")
check("duplicateEmptyFirstStillWins", c["code"] is None)

c = parse_callback("code&state=s")
check("paramWithoutEqualsIsEmpty", c["code"] is None and c["state"] == "s")

c = parse_callback("code=c&&state=s")
check("emptyPairSkipped", c["code"] == "c" and c["state"] == "s")

c = parse_callback("code=c&state=s&")
check("trailingAmpersandSkipped", c["code"] == "c" and c["state"] == "s")

c = parse_callback("")
check("emptyQueryAllNone", c == {"code": None, "error": None, "error_description": None, "state": None})

c = parse_callback("code=10%0&state=s")
check("incompleteEscapeKeepsRaw", c["code"] == "10%0")

c = parse_callback("code=a%2F%0")
check("mixedEscapeKeepsWholeRaw", c["code"] == "a%2F%0")

c = parse_callback("code=a%zz&state=s")
check("illegalHexKeepsRaw", c["code"] == "a%zz")

c = parse_callback("code=a%3Db&state=s")
check("encodedEqualsDecoded", c["code"] == "a=b")

c = parse_callback("code=%2f%2F")
check("upperAndLowerHexDecode", c["code"] == "//")

c = parse_callback("c%6Fde=c&st%61te=t")
check("encodedNameRecognized", c["code"] == "c" and c["state"] == "t")

c = parse_callback("error=x&error_description=try+again+later")
check("descriptionPlusDecoded", c["error_description"] == "try again later")

# --- request-line handling (handleRequest) ---
s, c = handle_request_line("GET /?code=c HTTP/1.1")
check("getRecords", s == 200 and c["code"] == "c")

s, c = handle_request_line("get /?code=c HTTP/1.1")
check("methodCaseInsensitive", s == 200 and c["code"] == "c")

s, c = handle_request_line("POST /?code=c HTTP/1.1")
check("postIs405", s == 405 and c is None)

s, c = handle_request_line("DELETE /?code=c http/1.1")
check("deleteIs405EvenBadVersion", s == 405 and c is None)

s, c = handle_request_line("GARBAGE")
check("noSpacesIs400", s == 400 and c is None)

s, c = handle_request_line("GET /?code=c")
check("noVersionIs400", s == 400 and c is None)

s, c = handle_request_line("GET /?code=c HTTPX/1.1")
check("badVersionIs400", s == 400 and c is None)

s, c = handle_request_line("GET / HTTP/1.1")
check("noQueryIs400", s == 400 and c is None)

s, c = handle_request_line("GET /?code=c HTTP/1.0")
check("http10Accepted", s == 200 and c["code"] == "c")

s, c = handle_request_line("GET /?error=access_denied HTTP/1.1")
check("errorRedirectRecords", s == 200 and c["error"] == "access_denied")

# --- OAuthState.matches semantics ---
check("matchesNullExpected", state_matches(None, "x") is False)
check("matchesNullReceived", state_matches("x", None) is False)
check("matchesNullBoth", state_matches(None, None) is False)
check("matchesEqual", state_matches("ab", "ab") is True)
check("matchesPrefixLength", state_matches("ab", "abc") is False)
check("matchesSameLengthReorder", state_matches("ab", "ba") is False)
check("matchesCaseSensitive", state_matches("AB", "ab") is False)
check("matchesWithSpace", state_matches("a b", "a b") is True)
check("matchesEmptyEqual", state_matches("", "") is True)

# --- constants mirrored from the Kotlin companion object ---
check("hexLengthConstant", HEX_LENGTH == 32)
check("preferredPortConstant", PREFERRED_PORT == 8642)
check("timeoutConstant", DEFAULT_TIMEOUT_MS == 300_000)
check("socketTimeoutConstant", SOCKET_TIMEOUT_MS == 500)
check("generateFormatShape", HEX32.match("0123456789abcdef" * 2) is not None and len("0" * 32) == HEX_LENGTH)

print()
print("TOTAL CHECKS:", PASSED + len(FAIL))
print("TOTAL FAILURES:", len(FAIL))
if not FAIL:
    print("ALL PASS")
sys.exit(1 if FAIL else 0)
