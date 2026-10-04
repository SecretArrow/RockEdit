#!/usr/bin/env python3
"""1:1 Python mirror of core/ClipboardHistoryStore.kt (v0.13.0) to verify the
Kotlin test expectations before CI (same technique as scripts/*_verify.py)."""
import sys

MIN_MAX_ENTRIES, MAX_MAX_ENTRIES, MAX_LIMIT_CHARS = 5, 200, 1_000_000

FAIL = []


def check(name, cond):
    if not cond:
        FAIL.append(name)
    print(("PASS " if cond else "FAIL ") + name)


class Store:
    def __init__(self, entries=None, max_entries=50, max_entry_chars=100_000):
        self.entries = entries if entries is not None else []  # newest first
        self.max_entries = max(min(max_entries, MAX_MAX_ENTRIES), MIN_MAX_ENTRIES)
        self.max_entry_chars = max(min(max_entry_chars, MAX_LIMIT_CHARS), 1)
        self._next = 1

    def _next_id(self):
        used = [int(e["id"][1:]) for e in self.entries if e["id"].startswith("c") and e["id"][1:].isdigit()]
        nid = max(used) + 1 if used else 1
        return f"c{nid}"

    def add(self, text):
        if not text.strip():
            return ("BLANK_TEXT",)
        if len(text) > self.max_entry_chars:
            return ("TEXT_TOO_LARGE", len(text), self.max_entry_chars)
        if self.entries and self.entries[0]["text"] == text:
            moved = dict(self.entries.pop(0))
            moved["createdAt"] = 1234567890  # fresh timestamp in real impl
            self.entries.insert(0, moved)
            return ("SUCCESS", [e["text"] for e in self.entries])
        self.entries.insert(0, {"id": self._next_id(), "text": text,
                                "createdAt": 1234567890, "pinned": False})
        while len(self.entries) > self.max_entries:
            self.entries.pop(self._victim_index())
        return ("SUCCESS", [e["text"] for e in self.entries])

    def _victim_index(self):
        oldest_unpinned = max((i for i, e in enumerate(self.entries) if not e["pinned"]),
                              default=-1)
        return oldest_unpinned if oldest_unpinned > 0 else len(self.entries) - 1

    def list(self, query=None):
        if query is None or not query.strip():
            filtered = list(self.entries)
        else:
            needle = query.strip()
            filtered = [e for e in self.entries if needle.lower() in e["text"].lower()]
        return ([e for e in filtered if e["pinned"]] +
                [e for e in filtered if not e["pinned"]])

    def pin(self, eid):
        return self._set_pin(eid, True)

    def unpin(self, eid):
        return self._set_pin(eid, False)

    def _set_pin(self, eid, val):
        for e in self.entries:
            if e["id"] == eid:
                e["pinned"] = val
                return True
        return False

    def delete(self, eid):
        before = len(self.entries)
        self.entries = [e for e in self.entries if e["id"] != eid]
        return len(self.entries) != before

    def size(self):
        return len(self.entries)


# --- vectors (mirror ClipboardHistoryStoreTest.kt) ---
s = Store()
s.add("alpha"); s.add("beta"); s.add("gamma")
check("addAndListAreNewestFirst", [e["text"] for e in s.list()] == ["gamma", "beta", "alpha"]
      and s.size() == 3)

kv_entries = []
s1 = Store(kv_entries)
s1.add("alpha"); s1.add("beta")
s2 = Store(kv_entries)
check("orderSurvivesReopen", [e["text"] for e in s2.list()] == ["beta", "alpha"])

s = Store()
s.add("a"); s.add("b")
check("idsAreUnique", len({e["id"] for e in s.list()}) == 2)

s = Store()
s.add("alpha"); s.add("beta")
beta_id = s.list()[0]["id"]
s.add("beta")
e = s.list()
check("consecutiveDuplicateMovesToTopAndKeepsId",
      len(e) == 2 and e[0]["text"] == "beta" and e[0]["id"] == beta_id and not e[0]["pinned"])

s = Store()
s.add("alpha"); s.add("beta")
beta_id = s.list()[0]["id"]
s.pin(beta_id)
s.add("beta")
first = s.list()[0]
check("consecutiveDuplicateKeepsPinnedState",
      first["id"] == beta_id and first["pinned"] and s.size() == 2)

s = Store()
s.add("alpha"); s.add("beta"); s.add("alpha")
check("nonConsecutiveDuplicateStaysSeparate",
      s.size() == 3 and s.list()[0]["text"] == "alpha")

check("blankTextIsRejected", Store().add("")[0] == "BLANK_TEXT")
check("whitespaceOnlyTextIsRejected", Store().add("  \n\t ")[0] == "BLANK_TEXT")

s = Store(max_entry_chars=5)
r = s.add("abcdef")
check("oversizeTextIsRejectedWithActualAndCap",
      r[0] == "TEXT_TOO_LARGE" and "6" in str(r) and "5" in str(r) and
      s.add("abcde")[0] == "SUCCESS")

s = Store(max_entries=5)
for i in range(1, 6):
    s.add(f"e{i}")
s.add("e6")
check("evictsOldestUnpinned", s.size() == 5 and
      [e["text"] for e in s.list()] == ["e6", "e5", "e4", "e3", "e2"])

s = Store(max_entries=5)
for i in range(1, 6):
    s.add(f"e{i}")
s.pin(s.list()[-1]["id"])
s.add("e6")
# list() is a pinned-first VIEW: pinned e1 lists first, internal order is
# [e6, e5, e4, e3, e1] (e2, the oldest unpinned, was evicted).
check("evictionSkipsPinnedOldest",
      [e["text"] for e in s.list()] == ["e1", "e6", "e5", "e4", "e3"])

s = Store(max_entries=5)
for i in range(1, 6):
    s.add(f"e{i}")
for e in s.list():
    s.pin(e["id"])
s.add("e6")
# Oldest overall (e1) evicted although pinned; fresh entry never a victim;
# list() shows surviving pinned entries first (newest first): [e5,e4,e3,e2,e6].
check("allPinnedEvictsOldestOverall", s.size() == 5 and
      all(e["text"] != "e1" for e in s.list()) and
      any(e["text"] == "e6" for e in s.list()) and
      [e["text"] for e in s.list()] == ["e5", "e4", "e3", "e2", "e6"])

s = Store(max_entries=1)
check("maxEntriesClampedLow", s.max_entries == 5)
for i in range(1, 7):
    s.add(f"e{i}")
check("maxEntriesClampedLowBehavior", s.size() == 5 and
      all(e["text"] != "e1" for e in s.list()))

s = Store(max_entries=500)
check("maxEntriesClampedHigh", s.max_entries == 200)
for i in range(1, 202):
    s.add(f"e{i}")
check("maxEntriesClampedHighBehavior", s.size() == 200 and s.list()[0]["text"] == "e201")

low = Store(max_entry_chars=0)
check("maxEntryCharsClampedLow", low.max_entry_chars == 1 and
      low.add("ab")[0] == "TEXT_TOO_LARGE" and low.add("a")[0] == "SUCCESS")
high = Store(max_entry_chars=2_000_000)
check("maxEntryCharsClampedHigh", high.max_entry_chars == 1_000_000)

s = Store()
s.add("alpha"); s.add("beta")
check("pinUnpinDeleteAndMissingIds",
      not s.pin("c404") and not s.unpin("c404") and not s.delete("c404") and
      s.pin(s.list()[0]["id"]) and s.list()[0]["pinned"] and
      s.unpin(s.list()[0]["id"]) and not s.list()[0]["pinned"] and
      s.delete(s.list()[0]["id"]) and s.size() == 1)

s = Store()
s.add("alpha"); s.add("beta")
s.entries = []
check("clearRemovesEverything", s.size() == 0 and s.list() == [])

s = Store()
s.add("Hello World"); s.add("kotlin code")
check("listSearchIsCaseInsensitive",
      [e["text"] for e in s.list("hello wor")] == ["Hello World"] and
      [e["text"] for e in s.list("KOTLIN")] == ["kotlin code"])

s = Store()
s.add("Hello World"); s.add("kotlin code")
check("listSearchNoHitsAndBlankQueryReturnsAll",
      s.list("zzz") == [] and len(s.list(None)) == 2 and len(s.list("   ")) == 2)

s = Store()
s.add("alpha"); s.add("beta"); s.add("gamma")
s.pin(s.list()[-1]["id"])
check("pinnedFirstOrderingOverNewerUnpinned",
      [e["text"] for e in s.list()] == ["alpha", "gamma", "beta"])

# Eviction victim edge: fresh unpinned entry is never the victim while an
# older unpinned exists (covered by evictionSkipsPinnedOldest) and when it is
# the ONLY unpinned entry the oldest overall is evicted instead (allPinned...).

print()
print("TOTAL FAILURES:", len(FAIL))
sys.exit(1 if FAIL else 0)
