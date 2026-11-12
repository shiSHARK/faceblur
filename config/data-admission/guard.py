#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""FaceBlur data-admission guard (Python standard library only).

Blocks commits and pushes that would put non-admitted data into the repository:
  (a) secrets            - gitleaks (pinned version, repository config, no inline allow comments)
  (b) registration       - every non-code or binary file must be listed in register.tsv with its SHA-256
  (c) size               - files above the size limits are blocked
  (d) confidential data  - confidential patterns (normalised token n-grams of paths, file names, contents,
                           commit messages; the plain list lives OUTSIDE the repository, see below),
                           home-directory paths and e-mail addresses that are not allow-listed
  (e) image metadata     - JPEG/PNG must carry no EXIF beyond a small allowlist, no GPS, XMP, IPTC, comments,
                           thumbnails, MPF/extra images, text chunks or trailing data; other image formats blocked
  (f) identity           - commit author and committer e-mail must be an allowed (no-reply) identity
  (g) legal material     - the internal-legal marker in any file content or commit message, and any path
                           under a legal/internal folder (legal/, docs/legal/, management/, reports/legal*,
                           security-local/, at any depth), in every mode including ci and pre-push. Third-party
                           licence texts belong under licenses/ (allowed).

Modes (see README.md):
  pre-commit            staged files + the identity git will use + gitleaks --staged
  commit-msg FILE       commit message text + identity
  pre-push              commits about to be pushed (stdin as given by git to the pre-push hook)
  ci [--range R|--all]  tree at HEAD + every commit in the range (default: all history) + gitleaks over the range;
                        the confidential-pattern check is SKIPPED (CI has no pattern list) and paths are redacted
  tree [--rev REV]      tree check only
  audit                 ci --all plus every blob reachable from any ref (pre-publication audit)
  hash TEXT...          print the normalised form and SHA-256 of each TEXT (for maintaining pattern lists)

Confidential pattern list (nothing derived from it is committed): a plain-text file outside the
repository, lines "ID<TAB>tier<TAB>pattern" or, for entries kept as hashes only (no plain text even outside the
repository, e.g. a tier of internal figures), "ID<TAB>tier<TAB>sha256:<hex><TAB><token count>" with hex and count
as printed by `hash TEXT`. Located through the environment variable FACEBLUR_GUARD_PATTERNS,
else the repository-local git config key faceblur.patternsFile. Every mode except `ci` and `hash` needs it and
fails closed (exit 2) when it is not configured or not readable.
Reviewed false positives (also outside the repository): an optional exception list, one exact line per entry
(pattern hash + repository-relative path + SHA-256 of the exact line), from FACEBLUR_GUARD_PATTERN_EXCEPTIONS,
else git config faceblur.patternExceptionsFile, else pattern-exceptions.tsv next to the pattern list (README.md).

Exit codes: 0 = pass, 1 = blocked (findings), 2 = guard error (treat as blocked).
Output never prints the text that matched a confidential pattern; paths that match are redacted; in `ci` mode all
file paths are replaced by blob IDs.
"""
from __future__ import annotations

import hashlib
import os
import re
import shutil
import struct
import subprocess
import sys
import unicodedata
from pathlib import Path

CONFIG_DIR = Path(__file__).resolve().parent
REGISTER_REL = "config/data-admission/register.tsv"
GITLEAKS_VERSION = "8.30.1"

SIZE_LIMIT_UNREGISTERED = 1 * 1024 * 1024      # 1 MiB for any file that is not registered
SIZE_LIMIT_REGISTERED = 10 * 1024 * 1024       # 10 MiB hard cap even for registered files

# Files that are code or plain-text configuration: no registration needed (still scanned).
TEXT_EXTENSIONS = {
    ".kt", ".kts", ".java", ".swift", ".h", ".m", ".mm", ".c", ".cc", ".cpp", ".hpp", ".py", ".sh",
    ".bash", ".zsh", ".bat", ".cmd", ".gradle", ".toml", ".properties", ".pro", ".xml", ".yml", ".yaml",
    ".md", ".txt", ".api", ".klib.api", ".pbxproj", ".xcscheme", ".xcworkspacedata", ".xcsettings",
    ".entitlements", ".plist", ".xcprivacy", ".resolved", ".modulemap", ".def", ".cfg", ".ini",
    ".gitignore", ".gitattributes", ".editorconfig", ".gitkeep", ".pbxproj", ".strings", ".xcconfig",
}
# Extension-less text files allowed without registration when they are valid UTF-8 text.
# Guard configuration files (exempt from registration, still scanned).
CONFIG_FILES = {
    "config/data-admission/register.tsv",
    "config/data-admission/allowed-identities.txt",
    "config/data-admission/email-allowlist.txt",
    "config/data-admission/gitleaks.toml",
}

TOKEN_RE = re.compile(r"[^\W_]+")
HOME_PATH_RE = re.compile(r"(?:/Users/|/home/|[A-Za-z]:\\\\?Users\\\\?)([A-Za-z0-9._-]+)", re.IGNORECASE)
HOME_PATH_PLACEHOLDERS = {"shared", "user", "username", "you", "yourname", "name", "example", "me"}
EMAIL_RE = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}")

# (g) Internal legal material. The marker is assembled from parts so that this file never contains it.
# Matching runs on NFKC + casefold text; words may be separated by any run of spaces, hyphen/dash variants,
# underscores, dots, colons, slashes or pipes (including none); quotes or commas between the words do not match.
_SEP = r"[\s\-\u2010-\u2015\u2212\u00ad_:./|*]*"
LEGAL_MARKER_RE = re.compile(_SEP.join(["inter" + "nal", "le" + "gal", "do", "not", "pub" + "lish"]))
# Repository-relative folders that hold internal legal/management material (any depth, case-insensitive).
LEGAL_PATH_RE = re.compile(r"(?:^|/)(?:legal|management|security-local)/|(?:^|/)reports/legal[^/]*(?:/|$)",
                           re.IGNORECASE)


def has_legal_marker(text: str) -> bool:
    return bool(LEGAL_MARKER_RE.search(unicodedata.normalize("NFKC", text).casefold()))


class GuardError(Exception):
    pass


# ----------------------------------------------------------------------------------------------- utils
def normalise(text: str) -> str:
    """Normalisation rule v1: NFKC -> casefold -> maximal runs of Unicode letters/digits -> joined by one space."""
    return " ".join(TOKEN_RE.findall(unicodedata.normalize("NFKC", text).casefold()))


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def git(*args: str, input_bytes: bytes | None = None, check: bool = True) -> bytes:
    proc = subprocess.run(["git", *args], input=input_bytes, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and proc.returncode != 0:
        raise GuardError(f"git {' '.join(args[:3])} failed: {proc.stderr.decode('utf-8', 'replace').strip()}")
    return proc.stdout


def repo_root() -> Path:
    return Path(git("rev-parse", "--show-toplevel").decode().strip())


def inside_repository(p: Path, root: Path) -> bool:
    """True when the file (as given or after following links) lies in this repository or in any git work tree.
    The pattern list and the exception list must never be stored where they could be committed."""
    top = root.resolve()
    for cand in {Path(os.path.abspath(p)), p.resolve()}:
        if cand == top or top in cand.parents:
            return True
        if any((d / ".git").exists() for d in cand.parents):
            return True
    return False


def read_list(p: Path, what: str, origin: str) -> list[str]:
    try:
        return p.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeDecodeError) as e:
        raise GuardError(f"{what} (from {origin}) cannot be read as UTF-8 text ({type(e).__name__})") from None


class BlobReader:
    """Reads blobs through one `git cat-file --batch` process."""

    def __init__(self) -> None:
        self.proc = subprocess.Popen(["git", "cat-file", "--batch"], stdin=subprocess.PIPE, stdout=subprocess.PIPE)

    def read(self, obj: str) -> bytes | None:
        assert self.proc.stdin and self.proc.stdout
        self.proc.stdin.write(obj.encode() + b"\n")
        self.proc.stdin.flush()
        header = self.proc.stdout.readline().decode().strip()
        if header.endswith("missing") or not header:
            return None
        size = int(header.split()[2])
        data = self.proc.stdout.read(size)
        self.proc.stdout.read(1)
        return data

    def close(self) -> None:
        if self.proc.stdin:
            self.proc.stdin.close()
        self.proc.wait()


# ----------------------------------------------------------------------------------------------- config
class Patterns:
    """Confidential patterns from a plain list OUTSIDE the repository. Never hashed into the repo."""

    def __init__(self, root: Path, required: bool) -> None:
        self.hashes: dict[str, str] = {}   # sha256 of the normalised pattern -> pattern ID (IDs are safe to print)
        self.lengths: set[int] = set()
        self.exceptions: dict[tuple[str, str, str], str] = {}   # (pattern hex, path, line hex) -> PX ID
        self.exceptions_used: set[str] = set()
        self.status = "skipped (ci mode: no pattern list in CI)"
        if not required:
            return
        src = os.environ.get("FACEBLUR_GUARD_PATTERNS", "").strip()
        origin = "FACEBLUR_GUARD_PATTERNS"
        if not src:
            src = git("config", "--get", "faceblur.patternsFile", check=False).decode("utf-8").strip()
            origin = "git config faceblur.patternsFile"
        if not src:
            raise GuardError("confidential pattern list not configured: set FACEBLUR_GUARD_PATTERNS or "
                             "`git config faceblur.patternsFile <file>` (see README.md)")
        lp = Path(src).expanduser()
        if not lp.is_file():
            raise GuardError(f"confidential pattern list from {origin} is not a readable file")
        if inside_repository(lp, root):
            raise GuardError(f"confidential pattern list (from {origin}) is inside a git repository: keep it outside "
                             "every repository (README.md)")
        for line in read_list(lp, "confidential pattern list", origin):
            if not line.strip() or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 3:
                raise GuardError(f"malformed line in the pattern list (from {origin}): expected ID<TAB>tier<TAB>pattern")
            if parts[2].startswith("sha256:"):
                # Hashed entry (no plain text, e.g. a figure tier): ID<TAB>tier<TAB>sha256:<hex><TAB><token count>,
                # hex and count as printed by `guard.py hash TEXT` (SHA-256 of the normalised text).
                digest = parts[2][len("sha256:"):]
                count = parts[3].strip() if len(parts) > 3 else ""
                if not re.fullmatch(r"[0-9a-f]{64}", digest) or not re.fullmatch(r"[1-9][0-9]{0,2}", count):
                    raise GuardError(f"malformed hashed line in the pattern list (from {origin}): expected "
                                     "ID<TAB>tier<TAB>sha256:<64 lower-case hex><TAB><token count>")
                self.hashes[digest] = parts[0]
                self.lengths.add(int(count))
                continue
            norm = normalise(parts[2])
            if not norm:
                continue
            self.hashes[sha256_hex(norm.encode("utf-8"))] = parts[0]
            self.lengths.add(len(norm.split(" ")))
        if not self.hashes:
            raise GuardError(f"confidential pattern list (from {origin}) is empty")
        self.status = f"loaded {len(self.hashes)} patterns (from {origin})"
        self._load_exceptions(lp, root)
        if self.exceptions:
            self.status += f", {len(self.exceptions)} reviewed line exception(s)"

    def _load_exceptions(self, pattern_file: Path, root: Path) -> None:
        """Reviewed false positives, kept OUTSIDE the repository like the list itself. One exact line each:
        PX-nnnn<TAB>sha256:<pattern hex><TAB><repo-relative path><TAB>sha256:<hex of the exact line>[<TAB>note].
        A hit of that one pattern is ignored only inside a text file at exactly that path and only on a line whose
        bytes (UTF-8, without the line terminator) hash to that value. Never for paths, file names, commit
        messages, author names or binary strings; every other pattern on the same line still blocks."""
        src = os.environ.get("FACEBLUR_GUARD_PATTERN_EXCEPTIONS", "").strip()
        origin = "FACEBLUR_GUARD_PATTERN_EXCEPTIONS"
        if not src:
            src = git("config", "--get", "faceblur.patternExceptionsFile", check=False).decode("utf-8").strip()
            origin = "git config faceblur.patternExceptionsFile"
        if src:
            ep = Path(src).expanduser()
            if not ep.is_file():
                raise GuardError(f"pattern exception list from {origin} is not a readable file")
        else:
            ep = pattern_file.parent / "pattern-exceptions.tsv"
            origin = "pattern-exceptions.tsv next to the pattern list"
            if not ep.exists() and not ep.is_symlink():
                return
            if not ep.is_file():
                raise GuardError(f"pattern exception list ({origin}) is not a readable file")
        if inside_repository(ep, root):
            raise GuardError(f"pattern exception list (from {origin}) is inside a git repository: keep it outside "
                             "every repository (README.md)")
        ids: set[str] = set()
        for n, line in enumerate(read_list(ep, "pattern exception list", origin), 1):
            if not line.strip() or line.startswith("#"):
                continue
            parts = line.split("\t")
            if (len(parts) < 4 or not re.fullmatch(r"PX-\d{4}", parts[0])
                    or not re.fullmatch(r"sha256:[0-9a-f]{64}", parts[1])
                    or not re.fullmatch(r"sha256:[0-9a-f]{64}", parts[3])
                    or not valid_repo_path(parts[2])):
                raise GuardError(f"malformed line {n} in the pattern exception list (from {origin}): expected "
                                 "PX-nnnn<TAB>sha256:<pattern hex><TAB><repo-relative path><TAB>sha256:<line hex> "
                                 "(path: relative, '/' separated, no empty, '.' or '..' segment)")
            pattern = parts[1][len("sha256:"):]
            if pattern not in self.hashes:
                raise GuardError(f"pattern exception {parts[0]} (from {origin}) refers to a pattern that is not in "
                                 "the list (stale entry: remove or update it)")
            key = (pattern, parts[2], parts[3][len("sha256:"):])
            if parts[0] in ids:
                raise GuardError(f"pattern exception {parts[0]} (from {origin}) is listed twice (duplicate ID)")
            if key in self.exceptions:
                raise GuardError(f"pattern exception {parts[0]} (from {origin}) repeats the entry "
                                 f"{self.exceptions[key]} (duplicate pattern, path and line)")
            ids.add(parts[0])
            self.exceptions[key] = parts[0]

    def excepted(self, digest: str, path: str | None, line_text: str) -> str | None:
        if path is None or not self.exceptions:
            return None
        key = (digest, path, sha256_hex(line_text.removesuffix("\r").encode("utf-8")))
        px = self.exceptions.get(key)
        if px:
            self.exceptions_used.add(px)
        return px

    def match_tokens(self, tokens: list[str]) -> list[tuple[int, str, str, int]]:
        """(index of the first token, pattern ID, pattern hex, token count) for every matching n-gram."""
        hits: list[tuple[int, str, str, int]] = []
        if not self.hashes:
            return hits
        lengths = sorted(self.lengths)
        for i in range(len(tokens)):
            for n in lengths:
                if i + n > len(tokens):
                    break
                h = sha256_hex(" ".join(tokens[i:i + n]).encode("utf-8"))
                if h in self.hashes:
                    hits.append((i, self.hashes[h], h, n))
        return hits

    def match_text(self, text: str) -> list[str]:
        n = normalise(text)
        return [label for _, label, _, _ in self.match_tokens(n.split(" "))] if n and self.hashes else []


def valid_repo_path(path: str) -> bool:
    """A repository-relative path as git prints it: '/' separated, no empty, '.' or '..' segment, no control or
    backslash characters."""
    return (bool(path) and not re.search(r"[\x00-\x1f\x7f\\]", path)
            and all(seg not in ("", ".", "..") for seg in path.split("/")))


def load_lines(name: str) -> list[str]:
    p = CONFIG_DIR / name
    if not p.is_file():
        raise GuardError(f"{name} missing")
    return [l.strip() for l in p.read_text(encoding="utf-8").splitlines() if l.strip() and not l.lstrip().startswith("#")]


class Policy:
    def __init__(self, root: Path, patterns_required: bool = True, redact_all_paths: bool = False) -> None:
        self.patterns = Patterns(root, patterns_required)
        self.redact_all_paths = redact_all_paths
        self.email_allow = [re.compile(x) for x in load_lines("email-allowlist.txt")]
        self.authors: set[str] = set()
        self.committers: set[str] = set()
        for line in load_lines("allowed-identities.txt"):
            role, _, email = line.partition(" ")
            email = email.strip().lower()
            if role in ("author+committer", "author"):
                self.authors.add(email)
            if role in ("author+committer", "committer"):
                self.committers.add(email)

    def email_allowed(self, email: str) -> bool:
        return any(rx.fullmatch(email) for rx in self.email_allow)


def parse_register(data: bytes | None) -> dict[str, tuple[str, str]]:
    reg: dict[str, tuple[str, str]] = {}
    if not data:
        return reg
    for n, line in enumerate(data.decode("utf-8").splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 3 or not re.fullmatch(r"[0-9a-f]{64}", parts[1]) or not re.fullmatch(r"DR-\d{3,}", parts[2]):
            raise GuardError(f"register.tsv line {n}: expected 'path<TAB>sha256<TAB>DR-nnn'")
        reg[parts[0]] = (parts[1], parts[2])
    return reg


# ----------------------------------------------------------------------------------------------- findings
class Report:
    def __init__(self) -> None:
        self.findings: list[str] = []
        self.notes: list[str] = []

    def add(self, code: str, where: str, msg: str) -> None:
        self.findings.append(f"BLOCK [{code}] {where}: {msg}")


def display_path(path: str, policy: Policy, blob: str | None) -> str:
    if policy.redact_all_paths:
        ext = path.rsplit(".", 1)[-1] if "." in path.rsplit("/", 1)[-1] else ""
        return f"<blob {blob[:12] if blob else '?'}{(' .' + ext[:8]) if ext else ''}>"
    if policy.patterns.match_text(path):
        return f"<path redacted: confidential pattern; blob {blob[:12] if blob else '?'}>"
    return path


# ----------------------------------------------------------------------------------------------- content
def is_text(data: bytes) -> bool:
    if b"\x00" in data:
        return False
    try:
        data.decode("utf-8")
        return True
    except UnicodeDecodeError:
        return False


def printable_strings(data: bytes, minimum: int = 4) -> list[tuple[int, str]]:
    return [(m.start(), m.group().decode("ascii")) for m in re.finditer(rb"[\x20-\x7e]{%d,}" % minimum, data)]


def scan_text(where: str, text: str, policy: Policy, report: Report, line_offsets: bool = True,
              path: str | None = None) -> None:
    """`path` (repository-relative, text files only) enables the reviewed line exceptions of the pattern list."""
    norm_src = unicodedata.normalize("NFKC", text).casefold()
    m = LEGAL_MARKER_RE.search(norm_src)
    if m:
        line = norm_src.count("\n", 0, m.start()) + 1
        report.add("LEGAL", f"{where}:{line}", "internal-legal marker: legal material must stay out of the repository")
    toks, starts = [], []
    for m in TOKEN_RE.finditer(norm_src):
        toks.append(m.group())
        starts.append(m.start())
    # A pattern hash in committed text would publish it (README.md: nothing derived from the list is committed).
    for k, t in enumerate(toks):
        if len(t) == 64 and t in policy.patterns.hashes:
            line = norm_src.count("\n", 0, starts[k]) + 1
            report.add("PATTERN", f"{where}:{line}",
                       f"SHA-256 of confidential pattern {policy.patterns.hashes[t]} (hash not shown)")
    raw_lines = text.split("\n") if path is not None and policy.patterns.exceptions else []
    for i, label, digest, n in policy.patterns.match_tokens(toks):
        line = norm_src.count("\n", 0, starts[i]) + 1
        last = norm_src.count("\n", 0, starts[i + n - 1]) + 1
        # An exception covers one reviewed hit: that pattern, wholly on that exact line of that file.
        px = policy.patterns.excepted(digest, path, raw_lines[line - 1]) \
            if raw_lines and last == line and line <= len(raw_lines) else None
        if px:
            report.notes.append(f"{where}:{line}: pattern {label} not blocked: reviewed exception {px}")
            continue
        report.add("PATTERN", f"{where}:{line}", f"confidential pattern {label} (text not shown)")
    for m in HOME_PATH_RE.finditer(text):
        if m.group(1).lower() not in HOME_PATH_PLACEHOLDERS:
            line = text.count("\n", 0, m.start()) + 1
            report.add("HOMEPATH", f"{where}:{line}", "home-directory path (user name not shown)")
    for m in EMAIL_RE.finditer(text):
        if not policy.email_allowed(m.group()):
            line = text.count("\n", 0, m.start()) + 1
            report.add("EMAIL", f"{where}:{line}", "e-mail address not in email-allowlist.txt (address not shown)")


def scan_binary_strings(where: str, data: bytes, policy: Policy, report: Report) -> None:
    sub = Report()
    joined = "\n".join(s for _, s in printable_strings(data))
    scan_text(where + " (strings)", joined, policy, sub)
    report.findings.extend(sub.findings)


# ----------------------------------------------------------------------------------------------- images
IFD0_ALLOWED = {0x0112, 0x011A, 0x011B, 0x0128, 0x0213, 0x8769}
EXIFIFD_ALLOWED = {0x9000, 0x9101, 0xA000, 0xA001, 0xA002, 0xA003}
EXIF_NAMED = {0x8825: "GPS IFD", 0x927C: "MakerNote", 0x0201: "embedded thumbnail", 0x0202: "embedded thumbnail",
              0x010F: "Make", 0x0110: "Model", 0x013B: "Artist", 0x8298: "Copyright", 0x0132: "DateTime",
              0x0131: "Software", 0x010E: "ImageDescription", 0x9286: "UserComment", 0xA431: "BodySerialNumber",
              0xA430: "CameraOwnerName", 0x9003: "DateTimeOriginal", 0x02BC: "XMP in EXIF", 0x83BB: "IPTC in EXIF",
              0x8773: "ICC in EXIF", 0xA005: "Interoperability IFD"}


def check_exif(payload: bytes, problems: list[str]) -> None:
    tiff = payload[6:]
    if len(tiff) < 8 or tiff[:2] not in (b"II", b"MM"):
        problems.append("malformed EXIF")
        return
    end = "<" if tiff[:2] == b"II" else ">"

    def ifd(offset: int, allowed: set[int], name: str) -> int:
        if offset + 2 > len(tiff):
            problems.append(f"malformed EXIF {name}")
            return 0
        (count,) = struct.unpack(end + "H", tiff[offset:offset + 2])
        sub = 0
        for k in range(count):
            e = offset + 2 + 12 * k
            if e + 12 > len(tiff):
                problems.append(f"malformed EXIF {name}")
                return 0
            tag, _typ, _cnt, val = struct.unpack(end + "HHII", tiff[e:e + 12])
            if tag not in allowed:
                problems.append(f"EXIF {name} tag 0x{tag:04X} ({EXIF_NAMED.get(tag, 'not in allowlist')})")
            if tag == 0x8769:
                sub = val
        nxt_off = offset + 2 + 12 * count
        nxt = struct.unpack(end + "I", tiff[nxt_off:nxt_off + 4])[0] if nxt_off + 4 <= len(tiff) else 0
        if name == "IFD0" and nxt != 0:
            problems.append("EXIF IFD1 present (thumbnail image)")
        return sub

    (first,) = struct.unpack(end + "I", tiff[4:8])
    exif_ifd = ifd(first, IFD0_ALLOWED, "IFD0")
    if exif_ifd:
        ifd(exif_ifd, EXIFIFD_ALLOWED, "ExifIFD")


def check_jpeg(data: bytes) -> list[str]:
    problems: list[str] = []
    i, n = 2, len(data)
    frames = 0
    while i < n:
        if data[i] != 0xFF:
            problems.append(f"invalid marker at {i}")
            return problems
        while i < n and data[i] == 0xFF:
            i += 1
        if i >= n:
            break
        marker = data[i]
        i += 1
        if marker == 0xD9:  # EOI
            if i != n:
                problems.append(f"{n - i} bytes after the JPEG end marker")
            if frames == 0:
                problems.append("no frame")
            return problems
        if 0xD0 <= marker <= 0xD7 or marker == 0x01:
            continue
        if i + 2 > n:
            problems.append("truncated segment")
            return problems
        seglen = struct.unpack(">H", data[i:i + 2])[0]
        payload = data[i + 2:i + seglen]
        if 0xE0 <= marker <= 0xEF:
            app = marker - 0xE0
            if app == 0 and payload.startswith(b"JFIF\x00"):
                if len(payload) >= 14 and payload[12] * payload[13] > 0:
                    problems.append("JFIF thumbnail")
            elif app == 0 and payload.startswith(b"JFXX\x00"):
                problems.append("JFXX thumbnail extension")
            elif app == 1 and payload.startswith(b"Exif\x00"):
                check_exif(payload, problems)
            elif app == 1 and (b"ns.adobe.com/xap" in payload[:64] or b"ns.adobe.com/xmp" in payload[:64]):
                problems.append("XMP metadata")
            elif app == 2 and payload.startswith(b"ICC_PROFILE\x00"):
                pass  # colour profile allowed; its strings are scanned with the rest of the file
            elif app == 2 and payload.startswith(b"MPF\x00"):
                problems.append("MPF (multi-picture: extra images or gain map)")
            elif app == 14 and payload.startswith(b"Adobe"):
                pass
            else:
                problems.append(f"APP{app} segment {payload[:12]!r}")
        elif marker == 0xFE:
            problems.append("JPEG comment (COM) segment")
        elif marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
            frames += 1
            if frames > 1:
                problems.append("more than one frame")
        i += seglen
        if marker == 0xDA:  # SOS: skip entropy-coded data
            while i < n - 1:
                if data[i] == 0xFF and data[i + 1] != 0x00 and not (0xD0 <= data[i + 1] <= 0xD7):
                    break
                i += 1
    problems.append("no JPEG end marker")
    return problems


PNG_ALLOWED = {b"IHDR", b"PLTE", b"IDAT", b"IEND", b"tRNS", b"cHRM", b"gAMA", b"iCCP", b"sBIT", b"sRGB",
               b"bKGD", b"hIST", b"pHYs", b"sPLT", b"cICP", b"mDCv", b"cLLi"}


def check_png(data: bytes) -> list[str]:
    problems: list[str] = []
    i = 8
    while i + 8 <= len(data):
        length, ctype = struct.unpack(">I4s", data[i:i + 8])
        if ctype not in PNG_ALLOWED:
            problems.append(f"PNG chunk {ctype.decode('latin-1')!r} not allowed (text/EXIF/time/animation/unknown)")
        i += 12 + length
        if ctype == b"IEND":
            if i != len(data):
                problems.append(f"{len(data) - i} bytes after IEND")
            return problems
    problems.append("no IEND chunk")
    return problems


def image_kind(data: bytes) -> str | None:
    if data[:3] == b"\xff\xd8\xff":
        return "jpeg"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "png"
    if data[:6] in (b"GIF87a", b"GIF89a") or (data[:4] == b"RIFF" and data[8:12] == b"WEBP") \
            or data[:4] in (b"II*\x00", b"MM\x00*") or data[4:12] in (b"ftypheic", b"ftypheix", b"ftypmif1",
                                                                       b"ftypavif", b"ftyphevc") or data[:2] == b"BM":
        return "other"
    return None


# ----------------------------------------------------------------------------------------------- file check
def check_file(path: str, data: bytes, blob: str | None, register: dict[str, tuple[str, str]],
               policy: Policy, report: Report, mode_bits: str = "100644") -> None:
    shown = display_path(path, policy, blob)
    if mode_bits == "160000":
        report.add("SUBMODULE", shown, "submodules are not allowed")
        return
    # (g) legal/internal folders
    if LEGAL_PATH_RE.search(path):
        report.add("LEGAL", shown, "path is under a legal/internal folder (legal/, docs/legal/, management/, "
                                   "reports/legal*, security-local/); third-party licence texts go under licenses/")
    # (d) path and file name
    for label in policy.patterns.match_text(path):
        report.add("PATTERN", shown, f"confidential pattern {label} in path or file name")
    for m in HOME_PATH_RE.finditer(path):
        if m.group(1).lower() not in HOME_PATH_PLACEHOLDERS:
            report.add("HOMEPATH", shown, "home-directory path in file name")
    digest = sha256_hex(data)
    registered = register.get(path)
    # (c) size
    limit = SIZE_LIMIT_REGISTERED if registered else SIZE_LIMIT_UNREGISTERED
    if len(data) > limit:
        report.add("SIZE", shown, f"{len(data)} bytes > limit {limit} ({'registered' if registered else 'unregistered'})")
    # (b) registration
    text = is_text(data)
    name = path.rsplit("/", 1)[-1]
    ext = "." + name.rsplit(".", 1)[-1].lower() if "." in name[1:] else (name.lower() if name.startswith(".") else "")
    code_like = text and (ext in TEXT_EXTENSIONS or ext == "" or path in CONFIG_FILES
                          or (name == "Contents.json" and ".xcassets/" in path))
    if mode_bits == "120000":
        code_like = True
    if registered:
        if registered[0] != digest:
            report.add("CHECKSUM", shown, f"SHA-256 {digest[:12]}.. does not match register.tsv ({registered[1]})")
    elif not code_like:
        why = "binary file" if not text else f"non-code file type '{ext or name}'"
        report.add("UNREGISTERED", shown, f"{why} not in {REGISTER_REL} (needs an approved DATA_REGISTER row)")
    # (d) contents
    if text:
        # Reviewed line exceptions apply to the contents of regular text files only, never to a symlink target.
        scan_text(shown, data.decode("utf-8"), policy, report, path=None if mode_bits == "120000" else path)
    else:
        scan_binary_strings(shown, data, policy, report)
    # (e) image metadata
    kind = image_kind(data)
    if kind == "jpeg":
        for p in check_jpeg(data):
            report.add("METADATA", shown, p)
    elif kind == "png":
        for p in check_png(data):
            report.add("METADATA", shown, p)
    elif kind == "other":
        report.add("IMAGEFORMAT", shown, "only JPEG and PNG images can be admitted (metadata cannot be verified)")


# ----------------------------------------------------------------------------------------------- identity
def ident_email(ident: str) -> str:
    m = re.search(r"<([^>]*)>", ident)
    return (m.group(1) if m else "").strip().lower()


def check_identity(where: str, author_email: str, committer_email: str, policy: Policy, report: Report) -> None:
    if author_email.lower() not in policy.authors:
        report.add("IDENTITY", where, "author e-mail is not an allowed no-reply identity (address not shown)")
    if committer_email.lower() not in policy.committers:
        report.add("IDENTITY", where, "committer e-mail is not an allowed no-reply identity (address not shown)")


def check_names(where: str, names: list[str], policy: Policy, report: Report) -> None:
    for nm in names:
        for label in policy.patterns.match_text(nm):
            report.add("PATTERN", where, f"confidential pattern {label} in author/committer name")


# ----------------------------------------------------------------------------------------------- gitleaks
def find_gitleaks(root: Path) -> str:
    cand = (os.environ.get("FACEBLUR_GITLEAKS", "").strip()
            or git("config", "--get", "faceblur.gitleaks", check=False).decode("utf-8").strip()
            or shutil.which("gitleaks") or "")
    if not cand or not Path(cand).expanduser().is_file():
        raise GuardError("gitleaks not found (set FACEBLUR_GITLEAKS, `git config faceblur.gitleaks <file>`, or PATH)")
    cand = str(Path(cand).expanduser())
    ver = subprocess.run([cand, "version"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout.decode().strip()
    if ver.lstrip("v") != GITLEAKS_VERSION:
        raise GuardError(f"gitleaks version {ver!r} != pinned {GITLEAKS_VERSION}")
    return cand


def sanitise_gitleaks(out: str, policy: Policy) -> str:
    """gitleaks --verbose prints author names, e-mails and file paths: never let them reach (CI) logs."""
    lines = []
    for line in out.splitlines():
        key = line.strip().split(":", 1)[0]
        if key in ("Author", "Email"):
            line = line.split(":", 1)[0] + ":       <redacted by data-admission guard>"
        elif key in ("File", "Fingerprint", "Link") and (policy.redact_all_paths or policy.patterns.match_text(line)
                                                          or HOME_PATH_RE.search(line)):
            line = line.split(":", 1)[0] + ":  <redacted: confidential pattern or home path>"
        lines.append(line)
    return "\n".join(lines)


def run_gitleaks(root: Path, extra: list[str], report: Report, policy: Policy) -> None:
    exe = find_gitleaks(root)
    cmd = [exe, "git", "--redact", "--verbose", "--no-banner", "--no-color", "--ignore-gitleaks-allow",
           "-c", str(CONFIG_DIR / "gitleaks.toml"), "-i", str(CONFIG_DIR), *extra, str(root)]
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = proc.stdout.decode("utf-8", "replace")
    if proc.returncode == 1:
        report.add("SECRET", "gitleaks", "possible secret(s) found; gitleaks output (redacted) follows")
        report.findings.append(sanitise_gitleaks(out.strip(), policy))
    elif proc.returncode != 0:
        raise GuardError(f"gitleaks failed (exit {proc.returncode}): {sanitise_gitleaks(out.strip()[-500:], policy)}")
    else:
        report.notes.append("gitleaks " + GITLEAKS_VERSION + ": no leaks (" + " ".join(extra or ["git"]) + ")")


# ----------------------------------------------------------------------------------------------- modes
def staged_entries() -> list[tuple[str, str, str]]:
    names = git("diff", "--cached", "--name-only", "-z", "--diff-filter=ACMRT").split(b"\x00")
    names = [n.decode("utf-8") for n in names if n]
    if not names:
        return []
    out = git("ls-files", "-s", "-z", "--", *names).split(b"\x00")
    entries = []
    for rec in out:
        if not rec:
            continue
        meta, path = rec.decode("utf-8").split("\t", 1)
        mode_bits, blob, _stage = meta.split()
        entries.append((path, blob, mode_bits))
    return entries


def mode_pre_commit(root: Path, policy: Policy, report: Report) -> None:
    reader = BlobReader()
    register = parse_register(reader.read(":" + REGISTER_REL))
    for path, blob, mode_bits in staged_entries():
        check_file(path, reader.read(blob) or b"", blob, register, policy, report, mode_bits)
    reader.close()
    author = git("var", "GIT_AUTHOR_IDENT").decode().strip()
    committer = git("var", "GIT_COMMITTER_IDENT").decode().strip()
    check_identity("commit identity", ident_email(author), ident_email(committer), policy, report)
    check_names("commit identity", [author.split("<")[0], committer.split("<")[0]], policy, report)
    run_gitleaks(root, ["--staged"], report, policy)


def mode_commit_msg(msg_file: str, policy: Policy, report: Report) -> None:
    text = Path(msg_file).read_text(encoding="utf-8", errors="replace")
    text = "\n".join(l for l in text.splitlines() if not l.startswith("#"))
    scan_text("commit message", text, policy, report)
    author = git("var", "GIT_AUTHOR_IDENT").decode().strip()
    committer = git("var", "GIT_COMMITTER_IDENT").decode().strip()
    check_identity("commit identity", ident_email(author), ident_email(committer), policy, report)


def check_commits(commits: list[str], policy: Policy, report: Report) -> None:
    reader = BlobReader()
    for c in commits:
        meta = git("log", "-1", "--format=%ae%x00%ce%x00%an%x00%cn%x00%P%x00%B", c).decode("utf-8", "replace")
        ae, ce, an, cn, parents, body = (meta.split("\x00", 5) + [""] * 6)[:6]
        where = f"commit {c[:12]}"
        check_identity(where, ae, ce, policy, report)
        check_names(where, [an, cn], policy, report)
        scan_text(where + " message", body, policy, report)
        register = parse_register(reader.read(f"{c}:{REGISTER_REL}"))
        first_parent = parents.split()[0] if parents.split() else None
        args = ["diff-tree", "-r", "-z", "--no-commit-id", "--no-renames", "--diff-filter=ACMRT"]
        args += [first_parent, c] if first_parent else ["--root", c]
        raw = git(*args).split(b"\x00")
        k = 0
        while k < len(raw):
            head = raw[k].decode("utf-8")
            if head.startswith(":"):
                fields = head[1:].split()
                path = raw[k + 1].decode("utf-8")
                new_mode, new_blob = fields[1], fields[3]
                check_file(path, reader.read(new_blob) or b"", new_blob, register, policy, report, new_mode)
                k += 2
            else:
                k += 1
    reader.close()


def check_tree(rev: str, policy: Policy, report: Report) -> int:
    reader = BlobReader()
    register = parse_register(reader.read(f"{rev}:{REGISTER_REL}"))
    count = 0
    for rec in git("ls-tree", "-r", "-z", rev).split(b"\x00"):
        if not rec:
            continue
        meta, path = rec.decode("utf-8").split("\t", 1)
        mode_bits, _typ, blob = meta.split()
        check_file(path, reader.read(blob) or b"", blob, register, policy, report, mode_bits)
        count += 1
    for path in register:
        if not git("ls-tree", rev, "--", path).strip():
            report.add("REGISTER", path, "registered path not present in the tree (stale row)")
    reader.close()
    return count


def has_head() -> bool:
    return subprocess.run(["git", "rev-parse", "--verify", "-q", "HEAD"], stdout=subprocess.DEVNULL).returncode == 0


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    mode = argv[0]
    if mode == "hash":
        for t in argv[1:]:
            n = normalise(t)
            print(f"{sha256_hex(n.encode('utf-8'))} {len(n.split(' '))}")
        return 0
    try:
        root = repo_root()
        os.chdir(root)
        if mode not in ("pre-commit", "commit-msg", "pre-push", "ci", "audit", "tree"):
            print(f"unknown mode {mode!r}", file=sys.stderr)
            return 2
        # CI has no pattern list -> skip that check there and redact every path in CI output.
        policy = Policy(root, patterns_required=(mode != "ci"), redact_all_paths=(mode == "ci"))
        report = Report()
        report.notes.append("confidential-pattern check: " + policy.patterns.status)
        if mode == "pre-commit":
            mode_pre_commit(root, policy, report)
        elif mode == "commit-msg":
            mode_commit_msg(argv[1], policy, report)
        elif mode == "pre-push":
            zero = "0" * 40
            commits: list[str] = []
            for line in sys.stdin.read().splitlines():
                parts = line.split()
                if len(parts) != 4 or parts[1] == zero:
                    continue
                rng = [parts[1], "--not", "--remotes"] if parts[3] == zero else [f"{parts[3]}..{parts[1]}"]
                commits += [c for c in git("rev-list", *rng).decode().split() if c not in commits]
            check_commits(commits, policy, report)
            report.notes.append(f"pre-push: {len(commits)} commit(s) checked")
            if commits:
                run_gitleaks(root, [f"--log-opts={' '.join(sorted(set(commits)))} --no-walk"], report, policy)
        elif mode in ("ci", "audit"):
            rng = "--all"
            if len(argv) >= 3 and argv[1] == "--range":
                rng = argv[2]
            if not has_head():
                raise GuardError("no commits to check")
            n = check_tree("HEAD", policy, report)
            commits = git("rev-list", *(rng.split())).decode().split()
            check_commits(commits, policy, report)
            report.notes.append(f"{mode}: tree at HEAD ({n} files) + {len(commits)} commit(s) [{rng}]")
            if mode == "audit":
                # Every blob reachable from any ref, with the path it was first seen at. Registration is
                # checked per commit above; here every blob is scanned for patterns, paths, e-mails, metadata.
                objs = git("rev-list", "--all", "--objects")
                typed = git("cat-file", "--batch-check=%(objecttype) %(objectname) %(rest)", input_bytes=objs)
                reader = BlobReader()
                blobs = 0
                for line in typed.decode("utf-8").splitlines():
                    parts = line.split(" ", 2)
                    if len(parts) == 3 and parts[0] == "blob":
                        data = reader.read(parts[1]) or b""
                        check_file(parts[2], data, parts[1], {parts[2]: (sha256_hex(data), "DR-000")},
                                   policy, report)
                        blobs += 1
                reader.close()
                report.notes.append(f"audit: {blobs} reachable blob(s) scanned for patterns/paths/e-mails/metadata")
            run_gitleaks(root, [f"--log-opts={rng}"], report, policy)
        elif mode == "tree":
            rev = argv[2] if len(argv) >= 3 and argv[1] == "--rev" else "HEAD"
            n = check_tree(rev, policy, report)
            report.notes.append(f"tree {rev}: {n} files")
        else:
            print(f"unknown mode {mode!r}", file=sys.stderr)
            return 2
        if policy.patterns.exceptions:
            report.notes.append(f"reviewed pattern exceptions applied: {len(policy.patterns.exceptions_used)} of "
                                f"{len(policy.patterns.exceptions)}")
            unused = sorted(set(policy.patterns.exceptions.values()) - policy.patterns.exceptions_used)
            if mode in ("tree", "audit") and unused:
                # Not a block: an entry for a line that is not (yet) in the checked tree. Remove dead entries.
                report.notes.append("reviewed pattern exceptions NOT used in this " + mode + " run: " + ", ".join(unused))
    except GuardError as e:
        print(f"data-admission guard ERROR (treated as BLOCK): {e}", file=sys.stderr)
        return 2
    for note in report.notes:
        print(f"data-admission: {note}")
    # de-duplicate while keeping order
    seen, uniq = set(), []
    for f in report.findings:
        if f not in seen:
            seen.add(f)
            uniq.append(f)
    if uniq:
        print(f"data-admission: BLOCKED ({mode}), {sum(1 for f in uniq if f.startswith('BLOCK'))} finding(s):", file=sys.stderr)
        for f in uniq:
            print("  " + f, file=sys.stderr)
        print("See config/data-admission/README.md. Do not bypass with --no-verify.", file=sys.stderr)
        return 1
    print(f"data-admission: PASS ({mode})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
