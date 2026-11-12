#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Internal process IDs and workspace paths stay out of public files and commit messages.

Every commit is treated as public. Identifiers that only make sense inside the maintainers' private workspace
(decision, question, risk and incident numbers, review-finding and assignment labels, threat-model test labels) and
paths of that workspace must not appear in tracked files or commit messages. Requirement and story IDs (FR-, NFR-,
PRIV-, LIC-, SEC-, DX-, DOC-, ST-, ...) and ADR numbers are public and allowed.

Text is normalised first (Unicode NFKC; the dashes U+2010..U+2015 and the minus sign U+2212 count as "-"), so
IDs pasted from rendered Markdown are caught too. Patterns (built from parts below):
  anywhere            decision/question numbers (letter D or Q, dash, 3 digits), risk/incident numbers (R-/INC- with
                      3 digits), release-gate items RG-n/RG-nn, review/finding labels SR-nn, S2-nn, TM-nn, SECT-nn,
                      IV-nn, RDY-XX-n, DSC-XX-n, PLN-XX-n, assignment labels like M1-XX-n (case-sensitive)
  workspace paths     management/<x>, the per-role folders reports/<role>/ and logs/<role>/, security-local/<x>,
                      docs/legal/<x>, also after another path segment (../management/...), the agents' scratch folders
                      and the workspace folder name (case-insensitive)
  comments and text   short review-finding labels such as m-3, M-02, n-1, B-1, R-3 (only in comments, Markdown and
                      plain text, because code like `a[m-1]` is legitimate)
The ID requirement/story families (FR-, NFR-, PRIV-, ST-, ...) and the data-register key DR-nnn are public.
Baseline: scripts/internal-id-baseline.txt pins lines (path + SHA-256 of the stripped line) in files whose owners have
not cleaned them yet; a pinned line passes unchanged, any new or changed line is checked. Entries may only be
removed.

Usage:
  check-internal-ids.py --tree                    every tracked text file (CI)
  check-internal-ids.py --files <f>...            the given files (pre-commit hook, staged copies)
  check-internal-ids.py --message <file>          a commit message (commit-msg hook)
  check-internal-ids.py --range <rev-range>       commit messages of a range incl. merge commits (CI); commits
                                                  dated before CUTOFF (when this gate was introduced) are not checked
  check-internal-ids.py --baseline-shrinks-only <rev>   the baseline has no entry that <rev> did not have
File PATHS are checked too (tracked paths with --tree, the given paths with --files): an internal ID or workspace
path in a file or directory name fails like one in the content.
Output names the file, line, column (of the original line) and kind of each match but never the matched text, so CI
logs that show a failure (or the planted probes of the self-tests) do not repeat an internal ID or path. A file whose
path itself matches is named only as "file #<n> (path SHA-256 <12 hex>)", <n> being its 1-based position in the
scanned list (for --tree: entry <n> of `git ls-files -z`). git error text and revision arguments are redacted the
same way.
Paths are listed NUL-separated (`git ls-files -z`), so non-ASCII names are opened as they are.
Exit codes: 0 clean, 1 internal ID or workspace path found, 2 usage error or a listed file / message that cannot be
read (fail closed).
"""
import hashlib
import os
import unicodedata
import re
import subprocess
import sys

CUTOFF = "2026-11-01T23:15:00Z"
BASELINE = "scripts/internal-id-baseline.txt"

D3 = r"[0-9]{3}"
ANYWHERE = re.compile(
    r"(?<![A-Za-z0-9])(?:"
    + r"[DQ]-" + D3 + r"|R-" + D3 + r"|INC-" + D3
    + r"|RG-[0-9]{1,2}"
    + r"|(?:SR|S2|TM|SECT|IV)-[0-9]{2}"
    + r"|(?:RDY|DSC|PLN)-[A-Z]{2}-[0-9]+"
    + r"|M[0-9]-[A-Z]{2,3}-[0-9]+"
    + r")(?![0-9])"
)
ROLE = r"(?:reviews|uat|perf|accuracy|qa|smoke|baseline|[a-z]+-(?:engineer|reviewer|advisor|lead|owner|designer|developer|architect|writer))"
WORKSPACE = re.compile(
    r"(?<![A-Za-z0-9._-])(?:management/[A-Za-z0-9_]|reports/" + ROLE + r"/|logs/" + ROLE + r"/"
    + r"|security-local/[A-Za-z0-9_]|docs/legal/[A-Za-z0-9_])"
    + r"|scratch" + r"pad|face" + r" blur/",
    re.IGNORECASE,
)
DASHES = {ord(c): "-" for c in "\u2010\u2011\u2012\u2013\u2014\u2015\u2212"}


def normalise(text: str) -> str:
    return unicodedata.normalize("NFKC", text).translate(DASHES)


SHORT = re.compile(r"(?<![A-Za-z0-9_.\[\]-])[mMnBR]-[0-9]{1,2}(?![0-9A-Za-z])")

SLASH_LANGS = (".kt", ".kts", ".java", ".swift", ".gradle", ".c", ".h", ".m", ".js", ".ts")
HASH_LANGS = (".py", ".sh", ".yml", ".yaml", ".toml", ".properties", ".cfg", ".ini", ".pro")
TEXT_LANGS = (".md", ".txt", ".tsv", ".csv", "")


def comment_part(path: str, line: str) -> str:
    low = path.lower()
    base = os.path.basename(low)
    ext = os.path.splitext(base)[1]
    if ext in TEXT_LANGS and not low.startswith(".githooks/") and base not in ("gradlew",):
        return line
    stripped = line.lstrip()
    if ext in SLASH_LANGS:
        if stripped.startswith(("*", "/*")):
            return line
        return line.split("//", 1)[1] if "//" in line else ""
    if ext in HASH_LANGS or low.startswith(".githooks/") or base == "gradlew":
        return line.split("#", 1)[1] if "#" in line else ""
    return line  # other text formats: whole line


def load_baseline() -> set:
    pinned = set()
    if os.path.isfile(BASELINE):
        with open(BASELINE, encoding="utf-8") as fh:
            for raw in fh:
                raw = raw.rstrip("\n")
                if raw and not raw.startswith("#"):
                    path, _, digest = raw.partition("\t")
                    pinned.add((path, digest.strip()))
    return pinned


def raw_columns(raw: str, line: str):
    """Map each index of the normalised line to the 1-based column of the original line. Normalising character by
    character gives the same text as normalising the whole line except around combining sequences; then None (the
    column of the normalised line is reported)."""
    cols = []
    for i, ch in enumerate(raw):
        cols += [i + 1] * len(normalise(ch))
    return cols if len(cols) == len(line) else None


def path_hits(path: str) -> list:
    """Kinds of matches in a file path (directory and file names)."""
    p = normalise(path)
    return sorted({"internal ID" for _ in ANYWHERE.finditer(p)} | {"workspace path" for _ in WORKSPACE.finditer(p)})


def redact(text: str) -> str:
    """Text with every internal ID, workspace path or short label replaced, for messages that quote git or paths."""
    text = normalise(text)
    for pattern in (ANYWHERE, WORKSPACE, SHORT):
        text = pattern.sub("<redacted>", text)
    return text


def label(path: str, index: int) -> str:
    """How a scanned file is named in the output: its path, unless the path itself matches or is not valid UTF-8."""
    raw = os.fsencode(path)
    try:
        raw.decode("utf-8")
    except UnicodeDecodeError:
        return f"file #{index} (path SHA-256 {hashlib.sha256(raw).hexdigest()[:12]})"
    if not path_hits(path):
        return path
    return f"file #{index} (path SHA-256 {hashlib.sha256(raw).hexdigest()[:12]})"


def scan_text(name: str, text: str, pinned: set, comments: bool = True, shown: str = "") -> list:
    shown = shown or name
    hits = []
    for n, raw in enumerate(text.splitlines(), 1):
        line = normalise(raw)
        found = [(m.start(), "internal ID") for m in ANYWHERE.finditer(line)]
        found += [(m.start(), "workspace path") for m in WORKSPACE.finditer(line)]
        if comments:
            part = comment_part(name, line)
            offset = len(line) - len(part)
            found += [(offset + m.start(), "review label") for m in SHORT.finditer(part)]
        if not found:
            continue
        digest = hashlib.sha256(raw.strip().encode("utf-8")).hexdigest()
        if (name, digest) in pinned:
            continue
        cols = raw_columns(raw, line)
        where = sorted({(cols[i] if cols else i + 1, kind) for i, kind in found})
        hits.append(f"{shown}:{n}: " + ", ".join(f"{kind} at column {col}" for col, kind in where))
    return hits


def read_text(path: str):
    """Text of a file, None for binary content; raises OSError if the file cannot be read."""
    if os.path.islink(path):
        return os.readlink(path)  # git stores a symbolic link as its target path; check that text
    with open(path, "rb") as fh:
        data = fh.read()
    if b"\0" in data[:8192]:
        return None
    return data.decode("utf-8", errors="replace")


def git(*args: str) -> str:
    res = subprocess.run(["git", *args], capture_output=True)
    if res.returncode != 0:
        err = res.stderr.decode("utf-8", errors="replace").strip()
        print(f"check-internal-ids: git {args[0]} failed: {redact(err)}", file=sys.stderr)
        sys.exit(2)
    return res.stdout.decode("utf-8", errors="surrogateescape")


def report(hits: list, what: str) -> int:
    if hits:
        for h in hits:
            print(f"check-internal-ids: {h}", file=sys.stderr)
        print(f"check-internal-ids: FAIL - {len(hits)} finding(s) of internal IDs or workspace paths in {what}; use "
              "requirement/story IDs, ADR numbers or plain words", file=sys.stderr)
        return 1
    print(f"check-internal-ids: PASS - {what}")
    return 0


def main(argv: list) -> int:
    if not argv:
        print(__doc__.split("Usage:")[1].split("Exit codes")[0], file=sys.stderr)
        return 2
    mode, rest = argv[0], argv[1:]
    pinned = load_baseline()
    if mode in ("--tree", "--files"):
        files = [f for f in git("ls-files", "-z").split("\0") if f] if mode == "--tree" else rest
        hits = []
        unreadable = []
        count = 0
        for index, f in enumerate(files, 1):
            shown = label(f, index)
            hits += [f"{shown}: {kind} in the path" for kind in path_hits(f)]
            if f == BASELINE:
                continue
            try:
                text = read_text(f)
            except OSError:
                unreadable.append(shown)
                continue
            if text is None:
                continue
            count += 1
            hits += scan_text(f, text, pinned, shown=shown)
        if unreadable:
            for h in hits:
                print(f"check-internal-ids: {h}", file=sys.stderr)
            for u in unreadable:
                print(f"check-internal-ids: cannot read {u}", file=sys.stderr)
            print(f"check-internal-ids: ERROR - {len(unreadable)} listed file(s) could not be read", file=sys.stderr)
            return 2
        return report(hits, f"{count} files, {len(files)} paths")
    if mode == "--message" and len(rest) == 1:
        try:
            text = read_text(rest[0]) or ""
        except OSError:
            print("check-internal-ids: ERROR - the commit message file cannot be read", file=sys.stderr)
            return 2
        body = "\n".join(l for l in text.splitlines() if not l.startswith("#"))
        return report(scan_text("commit message", body, set()), "the commit message")
    if mode == "--range" and len(rest) == 1:
        hits = []
        commits = git("rev-list", f"--since={CUTOFF}", rest[0]).split()
        for c in commits:
            msg = git("log", "-1", "--format=%B", c)
            hits += [f"commit {c[:10]} {h}" for h in scan_text("message", msg, set())]
        return report(hits, f"{len(commits)} commit message(s) since {CUTOFF}")
    if mode == "--baseline-shrinks-only" and len(rest) == 1:
        res = subprocess.run(["git", "show", f"{rest[0]}:{BASELINE}"], capture_output=True, text=True)
        before = set()
        if res.returncode == 0:
            before = {l for l in res.stdout.splitlines() if l and not l.startswith("#")}
        else:
            print(f"check-internal-ids: PASS - no baseline at {redact(rest[0][:10])} (first introduction)")
            return 0
        now = set()
        if os.path.isfile(BASELINE):
            with open(BASELINE, encoding="utf-8") as fh:
                now = {l.rstrip("\n") for l in fh if l.strip() and not l.startswith("#")}
        added = sorted(now - before)
        if added:
            for a in added:
                print(f"check-internal-ids: baseline entry added: {redact(a.split(chr(9))[0])}", file=sys.stderr)
            print("check-internal-ids: FAIL - the baseline may only shrink", file=sys.stderr)
            return 1
        print(f"check-internal-ids: PASS - baseline {len(before)} -> {len(now)} entries")
        return 0
    print("check-internal-ids: usage error", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
