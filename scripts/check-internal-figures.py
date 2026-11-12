#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""No figures from internal data sets in public files or commit messages.

Every commit is treated as public. Results obtained on data that is not public (photo sets, runs and benchmarks that
only the maintainers can repeat) must not appear in tracked files or commit messages, not even as rounded values or
maxima. Authors usually name the source when they quote such a value, so this gate looks for internal-data wording
near a number: a line that matches a term of scripts/internal-figures-terms.txt ("measured", "headroom", "test set",
"our photos", ...; generic wording only, never a value and never the name of a data set) and has a number on the same
line or within two lines before or after it. Overlapping windows form one finding (a block of lines).

A block that has been reviewed and found public-safe (for example a value measured on the public test fixtures, or a
rule statement that quotes no measured value) is pinned in scripts/internal-figures-allow.txt by path and SHA-256 of
the block (its lines after normalisation, whitespace-trimmed, joined by a newline). Any change inside the block,
including a number added next to the wording, changes the hash and needs a new review. In full-tree mode an entry that
excuses no block is stale and fails, so the list cannot keep exceptions for text that was removed or changed. Entries are "permanent" or "temporary" (reworded text pending; to be removed);
--no-temporary makes temporary entries count as findings (release gate).

What counts as a number: a run of digits that is not part of a word or identifier (int32, sha256), not the suffix of
an ID or name joined by a hyphen (ST-002, UTF-8), not an ordered-list marker or a numbered Markdown heading at the start
of a line, and not the number of a reference: "ADR 0012", "decision 6", "section 4.2", "step 3", "item 2".

Usage:
  check-internal-figures.py --tree [--no-temporary] [--print-keys]   every tracked text file (CI); stale rule on
  check-internal-figures.py --files [--print-keys] <f>...             the given files (pre-commit hook)
  check-internal-figures.py --message <file>                          a commit message (commit-msg hook)
  check-internal-figures.py --range <rev-range>                       commit messages of a range incl. merge commits
                                                                      (CI); commits before CUTOFF are not checked
--print-keys prints the allow-list line of each finding to standard error (for preparing a review); the run still
fails. Output names path, lines and block hash, never the text of the block, so a CI log of a finding does not repeat
the figure. Commit messages have no allow-list: reword the message before pushing.
Exit codes: 0 clean, 1 finding or stale allow-list entry, 2 usage error, missing or invalid term list, malformed
allow-list, or a file / message that cannot be read (fail closed).
"""
import hashlib
import re
import subprocess
import sys
import unicodedata

TERMS = "scripts/internal-figures-terms.txt"
ALLOW = "scripts/internal-figures-allow.txt"
# The gate's own files are not scanned (they describe the terms and pin hashes).
SELF = {TERMS, ALLOW, "scripts/check-internal-figures.py"}
CUTOFF = "2026-11-02T22:00:00Z"
WINDOW = 2
KINDS = ("permanent", "temporary")

NUMBER = re.compile(r"(?<![A-Za-z0-9_])(?<![A-Za-z0-9_]-)[0-9]")
LIST_MARKER = re.compile(r"^\s*(?:[-*+]\s+|#+\s+)?[0-9]+[.)](?=\s)")
REFERENCE = re.compile(
    r"\b(?:ADR|decisions?|sections?|steps?|items?)\s+[0-9]+(?:\.[0-9]+)*(?:(?:\s*(?:,|and|or|to)\s*|-)[0-9]+)*",
    re.IGNORECASE,
)


def die(msg: str) -> "None":
    print(f"check-internal-figures: ERROR - {msg}", file=sys.stderr)
    sys.exit(2)


DASHES = {c: "-" for c in [0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2015, 0x2212]}


def normalise(text: str) -> str:
    """Unicode NFKC; the dashes U+2010..U+2015 and the minus sign count as "-" (text pasted from rendered pages)."""
    return unicodedata.normalize("NFKC", text).translate(DASHES)


def load_terms() -> re.Pattern:
    try:
        with open(TERMS, encoding="utf-8") as fh:
            lines = [ln.strip() for ln in fh]
    except OSError:
        die(f"{TERMS} is missing or unreadable (the term list is required)")
    terms = [ln for ln in lines if ln and not ln.startswith("#")]
    if not terms:
        die(f"{TERMS} has no entries")
    for n, t in enumerate(terms, 1):
        try:
            re.compile(t)
        except re.error:
            die(f"{TERMS}: entry {n} is not a valid regular expression")
    return re.compile("|".join(f"(?:{t})" for t in terms), re.IGNORECASE)


def load_allow() -> dict:
    allowed = {}
    try:
        with open(ALLOW, encoding="utf-8") as fh:
            rows = fh.read().splitlines()
    except FileNotFoundError:
        return allowed
    except OSError:
        die(f"{ALLOW} is unreadable")
    for n, line in enumerate(rows, 1):
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.split("\t")
        if (len(parts) != 4 or not re.fullmatch(r"[0-9a-f]{64}", parts[1]) or parts[2] not in KINDS
                or not parts[3].strip()):
            die(f"{ALLOW} line {n}: expected path<TAB>sha256<TAB>permanent|temporary<TAB>review reference")
        key = (parts[0], parts[1])
        if key in allowed:
            die(f"{ALLOW} line {n}: duplicate entry")
        allowed[key] = (n, parts[2])
    return allowed


def has_number(line: str) -> bool:
    line = LIST_MARKER.sub(" ", line, count=1)
    line = REFERENCE.sub(" ", line)
    return NUMBER.search(line) is not None


def blocks(text: str, terms: re.Pattern) -> list:
    """(first line, last line, sha256) of every block, 1-based inclusive."""
    lines = normalise(text).splitlines()
    numbers = [has_number(ln) for ln in lines]
    windows = []
    for i, ln in enumerate(lines):
        if not terms.search(ln):
            continue
        lo, hi = max(0, i - WINDOW), min(len(lines) - 1, i + WINDOW)
        if any(numbers[lo:hi + 1]):
            windows.append([lo, hi])
    merged = []
    for lo, hi in windows:
        if merged and lo <= merged[-1][1] + 1:
            merged[-1][1] = max(merged[-1][1], hi)
        else:
            merged.append([lo, hi])
    out = []
    for lo, hi in merged:
        body = "\n".join(ln.strip() for ln in lines[lo:hi + 1])
        out.append((lo + 1, hi + 1, hashlib.sha256(body.encode("utf-8")).hexdigest()))
    return out


def read_text(path: str):
    try:
        with open(path, "rb") as fh:
            data = fh.read()
    except OSError:
        die(f"cannot read {path}")
    if b"\0" in data[:8192]:
        return None  # binary (registered assets are checked by data admission)
    return data.decode("utf-8", errors="replace")


def tracked_files() -> list:
    try:
        out = subprocess.run(["git", "ls-files", "-z"], capture_output=True, check=True).stdout
    except (OSError, subprocess.CalledProcessError):
        die("git ls-files failed")
    return [p for p in out.decode("utf-8", errors="surrogateescape").split("\0") if p]


def scan_files(paths: list, full: bool, no_temporary: bool, print_keys: bool) -> int:
    terms = load_terms()
    allowed = load_allow()
    used = set()
    findings = temporary = 0
    for path in paths:
        if path in SELF:
            continue
        text = read_text(path)
        if text is None:
            continue
        for first, last, digest in blocks(text, terms):
            entry = allowed.get((path, digest))
            if entry is not None:
                used.add((path, digest))
                if entry[1] == "temporary":
                    temporary += 1
                    if no_temporary:
                        print(f"check-internal-figures: {path}:{first}-{last}: temporary allow-list entry (line "
                              f"{entry[0]}) not allowed with --no-temporary", file=sys.stderr)
                        findings += 1
                continue
            findings += 1
            print(f"check-internal-figures: {path}:{first}-{last}: internal-data wording within {WINDOW} lines of a "
                  f"number (block {digest[:12]}); text not shown", file=sys.stderr)
            if print_keys:
                print(f"check-internal-figures: allow-list key: {path}\t{digest}\t<permanent|temporary>\t"
                      f"<review reference>", file=sys.stderr)
    stale = 0
    if full:
        for (path, digest), (n, _) in sorted(allowed.items(), key=lambda kv: kv[1][0]):
            if (path, digest) not in used:
                print(f"check-internal-figures: stale allow-list line {n} in {ALLOW} (it excuses no block)",
                      file=sys.stderr)
                stale += 1
    if findings or stale:
        print(f"check-internal-figures: FAIL - {findings} finding(s), {stale} stale allow-list line(s). Remove "
              f"figures that come from internal data; a public-safe block needs a reviewed entry in {ALLOW}",
              file=sys.stderr)
        return 1
    note = f" ({temporary} block(s) excused by temporary entries marked for removal)" if temporary else ""
    print(f"check-internal-figures: PASS{note}")
    return 0


def scan_message(label: str, text: str, terms: re.Pattern) -> int:
    found = blocks(text, terms)
    for first, last, _ in found:
        print(f"check-internal-figures: {label} lines {first}-{last}: internal-data wording within {WINDOW} lines "
              f"of a number; text not shown", file=sys.stderr)
    return len(found)


def main(argv: list) -> int:
    if not argv or argv[0] in ("-h", "--help"):
        print(__doc__.strip())
        return 0 if argv else 2
    mode, rest = argv[0], argv[1:]
    if mode == "--tree":
        flags = set(rest)
        if not flags <= {"--no-temporary", "--print-keys"}:
            die("unknown option for --tree")
        return scan_files(tracked_files(), True, "--no-temporary" in flags, "--print-keys" in flags)
    if mode == "--files":
        print_keys = bool(rest) and rest[0] == "--print-keys"
        files = rest[1:] if print_keys else rest
        return scan_files(files, False, False, print_keys)
    if mode == "--message" and len(rest) == 1:
        terms = load_terms()
        try:
            with open(rest[0], encoding="utf-8", errors="replace") as fh:
                text = fh.read()
        except OSError:
            die("cannot read the commit message file")
        # Lines git strips from the message (comments) are not part of it.
        text = "\n".join(ln for ln in text.splitlines() if not ln.startswith("#"))
        if scan_message("commit message", text, terms):
            print("check-internal-figures: FAIL - reword the commit message (no figures from internal data)",
                  file=sys.stderr)
            return 1
        print("check-internal-figures: PASS (commit message)")
        return 0
    if mode == "--range" and len(rest) == 1:
        terms = load_terms()
        try:
            out = subprocess.run(["git", "log", "--format=%H%x00%cI%x00%B%x00", rest[0], "--"],
                                 capture_output=True, check=True).stdout.decode("utf-8", errors="replace")
        except (OSError, subprocess.CalledProcessError):
            die("git log failed for the given range")
        fields = out.split("\0")
        hits = checked = 0
        for i in range(0, len(fields) - 2, 3):
            sha, date, body = fields[i].strip(), fields[i + 1], fields[i + 2]
            if not sha:
                continue
            if _utc(date) < CUTOFF:
                continue
            checked += 1
            hits += scan_message(f"commit {sha[:12]}", body, terms)
        if hits:
            print(f"check-internal-figures: FAIL - {hits} finding(s) in commit messages", file=sys.stderr)
            return 1
        print(f"check-internal-figures: PASS ({checked} commit message(s) checked)")
        return 0
    die("unknown mode or wrong arguments (see --help)")
    return 2


def _utc(iso: str) -> str:
    from datetime import datetime, timezone
    return datetime.fromisoformat(iso).astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
