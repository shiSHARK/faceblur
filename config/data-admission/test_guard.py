#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Self-test of the data-admission guard: confidential-pattern matching and the reviewed line exceptions.

Everything runs in throw-away git repositories under a temporary directory. The pattern list and the exception
list are generated at run time from random tokens (hashes made with `guard.py hash`), so this file contains no
confidential pattern, no hash of one and no token that could be one. Nothing in this repository is changed.

Every case states the exact expected result: exit code, the set of PATTERN findings (location + pattern ID) and the
set of applied exceptions. Covered: exact line passes; the same line at another path, a one-byte change, a second
pattern on the line, a match across two lines, a commit message, a binary file, a symlink target and a file name
all block; CRLF and repeated identical lines pass; pre-push and audit apply the same scoping per commit; stale,
malformed (hex case, path segments), duplicate, unreadable, non-UTF-8 and missing lists stop the guard (exit 2);
a pattern list or exception list inside a git work tree is refused; a committed pattern hash blocks; unused
entries are reported.

Usage: FACEBLUR_GITLEAKS=<gitleaks binary, pinned version> python3 -I config/data-admission/test_guard.py
Exit codes: 0 every case behaved as expected, 1 otherwise, 2 setup error (gitleaks missing).
"""
from __future__ import annotations

import hashlib
import os
import re
import secrets
import shutil
import string
import subprocess
import sys
import tempfile
from pathlib import Path

GUARD = Path(__file__).resolve().parent / "guard.py"
FINDING_RE = re.compile(r"BLOCK \[PATTERN\] (.+?): (?:SHA-256 of )?confidential pattern (\S+)")
EXCEPTED_RE = re.compile(r"data-admission: (.+?): pattern (\S+) not blocked: reviewed exception (PX-\d{4})")
UNUSED_RE = re.compile(r"reviewed pattern exceptions NOT used in this \w+ run: (.*)")


def line_hash(line: str) -> str:
    return hashlib.sha256(line.encode("utf-8")).hexdigest()


class Suite:
    def __init__(self, work: Path, gitleaks: str) -> None:
        self.work = work
        self.passed = 0
        self.failed = 0
        self.lists = work / "lists"          # outside every repository, like the maintainer's real lists
        self.lists.mkdir()
        (work / "gitconfig").write_text("", encoding="utf-8")
        env = {k: v for k, v in os.environ.items() if not k.startswith(("GIT_", "FACEBLUR_"))}
        env.update({"GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": str(work / "gitconfig"),
                    "FACEBLUR_GITLEAKS": gitleaks, "LC_ALL": "C"})
        self.base_env = env
        self.patterns = self.lists / "patterns.txt"
        self.exceptions = self.lists / "exceptions.tsv"
        self.env = dict(env, FACEBLUR_GUARD_PATTERNS=str(self.patterns),
                        FACEBLUR_GUARD_PATTERN_EXCEPTIONS=str(self.exceptions))
        ident = next(l.split(" ", 1)[1].strip() for l in
                     (GUARD.parent / "allowed-identities.txt").read_text(encoding="utf-8").splitlines()
                     if l.startswith("author+committer "))
        self.ident = ident
        # Random synthetic tokens: a 7-digit figure, two plain words, and a word that follows the figure.
        self.fig = str(1_000_000 + secrets.randbelow(9_000_000))
        self.w1, self.w2, self.nxt = ("".join(secrets.choice(string.ascii_lowercase) for _ in range(9))
                                      for _ in range(3))
        self.h_fig = self.hash(self.fig)
        self.h_pair = self.hash(f"{self.fig} {self.nxt}")
        self.h_plain = self.hash(f"{self.w1} {self.w2}")
        self.patterns.write_text(
            f"SYN-1\tF\tsha256:{self.h_fig}\t1\n"
            f"SYN-2\tL\t{self.w1} {self.w2}\n"
            f"SYN-3\tF\tsha256:{self.h_pair}\t2\n", encoding="utf-8")
        self.path = "gradle/meta.xml"
        self.line = f'    <component group="org.example" name="lib" version="{self.fig}">'
        self.line_two = f'<c v="{self.fig}"/> {self.w1} {self.w2}'
        self.line_pair = f"  version {self.fig}"
        self.repo = work / "repo"

    # ------------------------------------------------------------------ helpers
    def hash(self, text: str) -> str:
        out = subprocess.run([sys.executable, "-I", str(GUARD), "hash", text], env=self.base_env,
                             stdout=subprocess.PIPE, check=True).stdout.decode().split()
        return out[0]

    def git(self, *args: str, cwd: Path | None = None) -> str:
        return subprocess.run(["git", *args], cwd=cwd or self.repo, env=self.base_env, check=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.decode()

    def new_repo(self) -> None:
        if self.repo.exists():
            shutil.rmtree(self.repo)
        self.repo.mkdir()
        self.git("init", "-q", ".")
        name, email = "shiSHARK", self.ident
        for k, v in (("user.name", name), ("user.email", email), ("core.autocrlf", "false"),
                     ("core.hooksPath", "/dev/null"), ("commit.gpgsign", "false")):
            self.git("config", k, v)

    def write(self, rel: str, data: bytes | str) -> None:
        p = self.repo / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data.encode("utf-8") if isinstance(data, str) else data)

    def commit(self, msg: str = "test: content") -> None:
        self.git("add", "-A")
        self.git("commit", "-q", "--no-verify", "-m", msg)

    def set_exceptions(self, rows: list[tuple[str, str, str, str]]) -> None:
        self.exceptions.write_text("".join(f"{i}\tsha256:{p}\t{path}\tsha256:{lh}\tsynthetic\n"
                                           for i, p, path, lh in rows), encoding="utf-8")

    def ex(self, px: str = "PX-0001", pattern: str | None = None, path: str | None = None,
           line: str | None = None) -> tuple[str, str, str, str]:
        return (px, pattern or self.h_fig, path or self.path, line_hash(self.line if line is None else line))

    def run(self, *args: str, stdin: str = "", env: dict | None = None) -> tuple[int, set, set, str]:
        proc = subprocess.run([sys.executable, "-I", str(GUARD), *args], cwd=self.repo, env=env or self.env,
                              input=stdin.encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        out, err = proc.stdout.decode("utf-8", "replace"), proc.stderr.decode("utf-8", "replace")
        findings = {(m.group(1), m.group(2)) for m in FINDING_RE.finditer(err)}
        applied = {(m.group(1), m.group(2), m.group(3)) for m in EXCEPTED_RE.finditer(out)}
        return proc.returncode, findings, applied, out + err

    def expect(self, case: str, got: tuple[int, set, set, str], rc: int, findings: set | None = None,
               applied: set | None = None, contains: str | None = None) -> None:
        code, f, a, text = got
        problems = []
        if code != rc:
            problems.append(f"exit {code}, expected {rc}")
        if findings is not None and f != findings:
            problems.append(f"PATTERN findings {sorted(f)}, expected {sorted(findings)}")
        if applied is not None and a != applied:
            problems.append(f"applied exceptions {sorted(a)}, expected {sorted(applied)}")
        if contains is not None and contains not in text:
            problems.append(f"output lacks {contains!r}")
        if problems:
            self.failed += 1
            # Synthetic tokens only, but keep the log free of them anyway.
            shown = text
            for tok in (self.fig, self.w1, self.w2, self.nxt):
                shown = shown.replace(tok, "<synthetic>")
            print(f"test_guard: {case} FAIL: " + "; ".join(problems).replace(self.fig, "<synthetic>"))
            for ln in shown.splitlines()[:12]:
                print("    " + ln)
        else:
            self.passed += 1
            print(f"test_guard: {case} PASS (exit {code})")

    # ------------------------------------------------------------------ cases
    def content_cases(self) -> None:
        P, L = self.path, self.line
        px1 = (f"{P}:2", "SYN-1", "PX-0001")
        self.set_exceptions([self.ex()])

        self.new_repo(); self.write(P, f"<x>\n{L}\n</x>\n"); self.commit()
        self.expect("exact-line-passes", self.run("tree"), 0, set(), {px1})

        self.new_repo(); self.write(P, f"<x>\n{L}\n</x>\n"); self.write("docs/copy.xml", f"<x>\n{L}\n</x>\n")
        self.commit()
        self.expect("same-line-other-path-blocks", self.run("tree"), 1, {("docs/copy.xml:2", "SYN-1")}, {px1})

        for case, variant in (("trailing-space", L + " "), ("extra-indent", "\t" + L),
                              ("figure-changed", L.replace(self.fig, str(int(self.fig) - 1)) + f" {self.fig}"),
                              ("full-width-digits", L.replace(self.fig, "".join(chr(0xFF10 + int(c)) for c in self.fig)))):
            self.new_repo(); self.write(P, f"<x>\n{variant}\n</x>\n"); self.commit()
            self.expect(f"one-change-blocks-{case}", self.run("tree"), 1, {(f"{P}:2", "SYN-1")}, set())

        self.new_repo(); self.write(P, f"<x>\r\n{L}\r\n</x>\r\n"); self.commit()
        self.expect("crlf-passes", self.run("tree"), 0, set(), {px1})

        self.new_repo(); self.write(P, f"<x>\n{L}\r\r\n</x>\n"); self.commit()
        self.expect("double-cr-blocks", self.run("tree"), 1, {(f"{P}:2", "SYN-1")}, set())

        self.new_repo(); self.write(P, f"<x>\n{L}\rv={self.fig}\n</x>\n"); self.commit()
        self.expect("lone-cr-then-figure-blocks", self.run("tree"), 1, {(f"{P}:2", "SYN-1")}, set())

        self.new_repo(); self.write(P, f"{L}\n{L}\n{L}\n"); self.commit()
        self.expect("identical-copies-in-file-pass", self.run("tree"), 0, set(),
                    {(f"{P}:{n}", "SYN-1", "PX-0001") for n in (1, 2, 3)})

        self.set_exceptions([self.ex(line=self.line_two)])
        self.new_repo(); self.write(P, f"{self.line_two}\n"); self.commit()
        self.expect("other-pattern-on-line-blocks", self.run("tree"), 1, {(f"{P}:1", "SYN-2")},
                    {(f"{P}:1", "SYN-1", "PX-0001")})

        self.set_exceptions([self.ex(pattern=self.h_pair, line=self.line_pair),
                             self.ex(px="PX-0002", line=self.line_pair)])
        self.new_repo(); self.write(P, f"{self.line_pair}\n{self.nxt}\n"); self.commit()
        self.expect("match-across-lines-blocks", self.run("tree"), 1, {(f"{P}:1", "SYN-3")},
                    {(f"{P}:1", "SYN-1", "PX-0002")})

        self.set_exceptions([self.ex()])
        self.new_repo(); self.write(P, b"\x00\x01" + L.encode() + b"\n\x00"); self.commit()
        self.expect("binary-strings-block", self.run("tree"), 1, {(f"{P} (strings):1", "SYN-1")}, set())

        self.new_repo(); (self.repo / "gradle").mkdir(); os.symlink(L, self.repo / P); self.commit()
        self.expect("symlink-target-blocks", self.run("tree"), 1, {(f"{P}:1", "SYN-1")}, set())

        # A file name holding the figure: the content line is excepted, the path never is (path shown redacted).
        named = f"docs/v-{self.fig}.xml"
        self.set_exceptions([self.ex(path=named)])
        self.new_repo(); self.write(named, f"{L}\n"); self.commit()
        shown = f"<path redacted: confidential pattern; blob {self.git('rev-parse', 'HEAD:' + named).strip()[:12]}>"
        self.expect("file-name-blocks", self.run("tree"), 1, {(shown, "SYN-1")}, {(f"{shown}:1", "SYN-1", "PX-0001")})

        self.set_exceptions([self.ex()])
        self.new_repo(); self.write("a.txt", "a\n"); self.commit()
        msg = self.work / "msg.txt"
        msg.write_text(f"test: change\n\n{L}\n", encoding="utf-8")
        self.expect("commit-message-blocks", self.run("commit-msg", str(msg)), 1, {("commit message:3", "SYN-1")},
                    set())

        self.new_repo(); self.write("docs/notes.md", f"key sha256:{self.h_fig}\n"); self.commit()
        self.expect("pattern-hash-in-content-blocks", self.run("tree"), 1, {("docs/notes.md:1", "SYN-1")}, set())

        self.new_repo(); self.write("a.txt", "a\n"); self.commit()
        self.expect("unused-entry-reported", self.run("tree"), 0, set(), set(), contains="NOT used in this tree run: PX-0001")

    def history_cases(self) -> None:
        P, L = self.path, self.line
        self.set_exceptions([self.ex()])
        self.new_repo(); self.write("a.txt", "a\n"); self.commit("test: base")
        base = self.git("rev-parse", "HEAD").strip()
        self.write(P, f"<x>\n{L}\n</x>\n"); self.commit()
        head = self.git("rev-parse", "HEAD").strip()
        push = f"refs/heads/main {head} refs/heads/main {base}\n"
        self.expect("pre-push-exact-line-passes", self.run("pre-push", "origin", "x", stdin=push), 0, set(),
                    {(f"{P}:2", "SYN-1", "PX-0001")})
        self.write("docs/copy.xml", f"{L}\n"); self.commit()
        head2 = self.git("rev-parse", "HEAD").strip()
        push2 = f"refs/heads/main {head2} refs/heads/main {base}\n"
        self.expect("pre-push-other-path-blocks", self.run("pre-push", "origin", "x", stdin=push2), 1,
                    {("docs/copy.xml:1", "SYN-1")})
        self.git("rm", "-q", "docs/copy.xml"); self.commit("test: drop copy")
        msg_commit = self.work / "m"
        self.write("b.txt", "b\n"); self.git("add", "-A")
        msg_commit.write_text(f"test: message\n\n{L}\n", encoding="utf-8")
        self.git("commit", "-q", "--no-verify", "-F", str(msg_commit))
        got = self.run("audit")
        self.expect("audit-blocks-copy-and-message", got, 1,
                    {("docs/copy.xml:1", "SYN-1"),
                     (f"commit {self.git('rev-parse', 'HEAD').strip()[:12]} message:3", "SYN-1")})

    def loading_cases(self) -> None:
        self.new_repo(); self.write("a.txt", "a\n"); self.commit()
        rows = {
            "stale-entry": f"PX-0001\tsha256:{'0' * 64}\t{self.path}\tsha256:{line_hash(self.line)}\n",
            "upper-case-hex": f"PX-0001\tsha256:{self.h_fig}\t{self.path}\tsha256:{line_hash(self.line).upper()}\n",
            "absolute-path": f"PX-0001\tsha256:{self.h_fig}\t/{self.path}\tsha256:{line_hash(self.line)}\n",
            "dot-segment": f"PX-0001\tsha256:{self.h_fig}\tgradle/./meta.xml\tsha256:{line_hash(self.line)}\n",
            "dotdot-segment": f"PX-0001\tsha256:{self.h_fig}\t../meta.xml\tsha256:{line_hash(self.line)}\n",
            "empty-segment": f"PX-0001\tsha256:{self.h_fig}\tgradle//meta.xml\tsha256:{line_hash(self.line)}\n",
            "duplicate-id": (f"PX-0001\tsha256:{self.h_fig}\t{self.path}\tsha256:{line_hash(self.line)}\n"
                             f"PX-0001\tsha256:{self.h_fig}\tdocs/a.md\tsha256:{line_hash(self.line)}\n"),
            "duplicate-entry": (f"PX-0001\tsha256:{self.h_fig}\t{self.path}\tsha256:{line_hash(self.line)}\n"
                                f"PX-0002\tsha256:{self.h_fig}\t{self.path}\tsha256:{line_hash(self.line)}\n"),
            "too-few-fields": f"PX-0001\tsha256:{self.h_fig}\t{self.path}\n",
        }
        for case, text in rows.items():
            self.exceptions.write_text(text, encoding="utf-8")
            self.expect(f"list-{case}-is-an-error", self.run("tree"), 2)
        self.exceptions.write_bytes(b"\xff\xfePX\n")
        self.expect("list-not-utf8-is-an-error", self.run("tree"), 2)
        self.set_exceptions([self.ex()])
        self.exceptions.chmod(0)
        if os.access(self.exceptions, os.R_OK):
            # Running with read access regardless of the mode (e.g. as root): the case cannot be produced here.
            self.failed += 1
            print("test_guard: list-unreadable-is-an-error NOT RUN (file stays readable at mode 000) - counted as FAIL")
        else:
            self.expect("list-unreadable-is-an-error", self.run("tree"), 2)
        self.exceptions.chmod(0o600)
        self.expect("list-missing-is-an-error",
                    self.run("tree", env=dict(self.env, FACEBLUR_GUARD_PATTERN_EXCEPTIONS=str(self.lists / "nope"))), 2)
        # Default location (next to the pattern list): optional, but a directory there is an error.
        env_default = dict(self.env)
        del env_default["FACEBLUR_GUARD_PATTERN_EXCEPTIONS"]
        self.expect("default-list-absent-passes", self.run("tree", env=env_default), 0)
        (self.lists / "pattern-exceptions.tsv").mkdir()
        self.expect("default-list-not-a-file-is-an-error", self.run("tree", env=env_default), 2)
        (self.lists / "pattern-exceptions.tsv").rmdir()
        # Lists inside a git work tree (this one, another one, or reached through a link) are refused.
        self.write("docs/exceptions.tsv", self.exceptions.read_text(encoding="utf-8"))
        self.expect("list-inside-this-repo-is-an-error", self.run(
            "tree", env=dict(self.env, FACEBLUR_GUARD_PATTERN_EXCEPTIONS=str(self.repo / "docs/exceptions.tsv"))), 2)
        other = self.work / "other"
        other.mkdir()
        self.git("init", "-q", ".", cwd=other)
        (other / "sub").mkdir()
        shutil.copy(self.exceptions, other / "sub" / "exceptions.tsv")
        shutil.copy(self.patterns, other / "sub" / "patterns.txt")
        self.expect("list-inside-other-repo-is-an-error", self.run(
            "tree", env=dict(self.env, FACEBLUR_GUARD_PATTERN_EXCEPTIONS=str(other / "sub" / "exceptions.tsv"))), 2)
        link = self.lists / "link.tsv"
        os.symlink(other / "sub" / "exceptions.tsv", link)
        self.expect("list-linked-into-repo-is-an-error", self.run(
            "tree", env=dict(self.env, FACEBLUR_GUARD_PATTERN_EXCEPTIONS=str(link))), 2)
        self.expect("pattern-list-inside-repo-is-an-error", self.run(
            "tree", env=dict(self.env, FACEBLUR_GUARD_PATTERNS=str(other / "sub" / "patterns.txt"))), 2)
        self.patterns.write_bytes(b"\xff\xfe\n")
        self.expect("pattern-list-not-utf8-is-an-error", self.run("tree"), 2)


def main() -> int:
    gitleaks = os.environ.get("FACEBLUR_GITLEAKS", "").strip()
    if not gitleaks or not Path(gitleaks).is_file():
        print("test_guard: NOT RUN - set FACEBLUR_GITLEAKS to the pinned gitleaks binary")
        return 2
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp).resolve()
        if any((d / ".git").exists() for d in work.parents):
            print("test_guard: setup error - the temporary directory is inside a git work tree (set TMPDIR)")
            return 2
        suite = Suite(work, gitleaks)
        suite.content_cases()
        suite.history_cases()
        suite.loading_cases()
        for p in work.rglob("*"):   # restore modes so that the temporary directory can be removed
            if not p.is_symlink():
                p.chmod(0o700 if p.is_dir() else 0o600)
    print(f"test_guard: {suite.passed} passed, {suite.failed} failed")
    return 0 if suite.failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
