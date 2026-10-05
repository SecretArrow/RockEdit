#!/usr/bin/env python3
"""Static brace/paren/bracket balance check for Kotlin sources (string,
template, and comment aware). Usage: python3 scripts/check_kt_balance.py FILE..."""
import sys

TRIPLE = '"""'


def balance(src: str):
    depth = {"{": 0, "}": 0, "(": 0, ")": 0, "[": 0, "]": 0}
    i, n = 0, len(src)
    in_line = in_block = False
    in_str = None  # None | '"' | 'tpl3' | "'"
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if in_line:
            if c == "\n":
                in_line = False
        elif in_block:
            if c == "*" and nxt == "/":
                in_block = False
                i += 1
        elif in_str:
            if c == "\\":
                i += 1
            elif in_str == "tpl3" and src.startswith(TRIPLE, i):
                in_str = None
                i += 2
            elif in_str != "tpl3" and c == in_str:
                in_str = None
        else:
            if c == "/" and nxt == "/":
                in_line = True
                i += 1
            elif c == "/" and nxt == "*":
                in_block = True
                i += 1
            elif src.startswith(TRIPLE, i):
                in_str = "tpl3"
                i += 2
            elif c in ('"', "'"):
                in_str = c
            elif c in depth:
                depth[c] += 1
        i += 1
    return (
        depth["{"] - depth["}"],
        depth["("] - depth[")"],
        depth["["] - depth["]"],
    )


if __name__ == "__main__":
    ok = True
    for path in sys.argv[1:]:
        b, p, s = balance(open(path, encoding="utf-8").read())
        status = "OK " if (b, p, s) == (0, 0, 0) else "BAD"
        if (b, p, s) != (0, 0, 0):
            ok = False
        print(f"{status} braces={b:+d} parens={p:+d} brackets={s:+d}  {path}")
    sys.exit(0 if ok else 1)
