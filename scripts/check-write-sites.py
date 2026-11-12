#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Write sites of the library: only the masked output and its temporary file (PRIV-005).

The library writes nothing to disk except the masked output (a temporary file of the masked output next to the
destination is allowed; never unmasked pixels or original bytes, no caches, no files of the inference runtime). The
SAST rule faceblur-file-io-review makes every file-I/O line of the library sources need a reviewed entry in
scripts/sast/allowlist.txt; this gate classifies those entries (scripts/sast/write-sites.txt) and fails when
  1. a file-I/O allow-list entry has no row in the register, or a row has no allow-list entry (both directions);
  2. an entry's line cannot be found in its file by its hash (the allow-list would not match it either);
  3. a line that can write (WRITE below) is classified as a kind that does not write;
  4. an open whose flags come from its callers (POSSIBLE_WRITE) is classified as a non-writing kind other than
     read-open, or a read-open row does not name `test: <class>::<method>` that exists in the library's test sources;
  5. a writing kind (masked-output, temp-file, cleanup) is in a file other than the file-mode writer files
     (WRITER_FILES: the Android writer files and the iOS path writer IosPathWriter.kt);
  6. in the library's main sources, a file-call mutation (`calls.create|write|link|rename|unlink(`) is made outside
     PathWriteRules.kt, or a class other than OsDescriptors implements FileCalls (the descriptor calls that the SAST
     rule sees only at their single implementation);
  7. a classified line, or any code line of the library's main sources, names an app storage directory or the
     temporary directory (STORAGE: cacheDir, filesDir, noBackupFilesDir, codeCacheDir, dataDir, the external
     directories, getDir, getDatabasePath, java.io.tmpdir, createTempFile/createTempDirectory). The library needs none
     of them: its only temporary file is created next to the target through FileCalls.create (PathWriteRules.kt);
  8. in the library's main sources, the descriptor layer is named outside the files that use it today (DESCRIPTOR_FILES:
     OsDescriptors, FileCalls, DescriptorCalls), OsDescriptors is called directly for a mutation
     (`OsDescriptors.create|write|fsync|link|rename|unlink`), a value of type FileCalls or DescriptorCalls has another
     name than `calls` (rule 6 sees mutations only through that name), or a write open flag (O_CREAT, O_WRONLY, O_RDWR,
     O_TRUNC, O_APPEND) is named outside the path writers (WRITE_FLAG_FILES: PathWriteRules.kt, IosPathWriter.kt).
     OsDescriptors.open itself refuses those flags at run time (PathWriteRules.requireReadOnly, OsDescriptorsTest);
  9. iOS, the same rules for the iOS path writer (security review 055): in the library's main sources, a writer-call
     mutation (`calls.createExclusive|put|sync|hardLink|replace|drop|syncDirectory(`) is made outside IosPathWriter.kt,
     a class other than IosPosixCalls implements IosWriteCalls, IosWriteCalls or IosPosixCalls is named outside
     IosPathWriter.kt, a value of type IosWriteCalls has another name than `calls`, IosPosixCalls is called directly
     for a mutation, a POSIX/Darwin file mutation is imported by name (`import platform.<lib>.unlink`, which would hide
     it from rule 3 as a bare call), or a qualified one (`platform.posix.rename(`) is made anywhere but inside the
     IosPosixCalls object of IosPathWriter.kt. Rule 7 also knows the iOS temporary and app directories
     (NSTemporaryDirectory, NSSearchPathForDirectoriesInDomains, the NS...Directory constants, TMPDIR, ...).
Known limits (code review keeps them): line rules see no type; rule 9 finds the IosPosixCalls object by its
declaration line and its closing `}` in column 0; a mutation through an alias of `calls` without a type
annotation, or through reflection, is not seen here (the SAST rules flag reflection). Changing the kinds, WRITE,
STORAGE, WRITER_FILES, WRITE_FLAG_FILES, DESCRIPTOR_FILES, the IOS_ names or the rules here needs a security review.

Usage: check-write-sites.py [repository root, default .]
Exit codes: 0 clean, 1 a violation (named), 2 usage or read error.
"""
import glob
import hashlib
import os
import re
import sys

ALLOW = "scripts/sast/allowlist.txt"
REGISTER = "scripts/sast/write-sites.txt"
HEADER = ("# SPDX-License-Identifier: Apache-2.0", "# faceblur file-I/O write sites, format 1")
FILE_RULES = ("faceblur-file-io-review", "faceblur-native-file-io-review", "faceblur-swift-file-io-review")
WRITE_KINDS = ("masked-output", "temp-file", "cleanup")
READ_KINDS = ("read", "read-open", "stat", "descriptor", "path-name", "memory")
ANDROID = "faceblur/src/androidMain/kotlin/io/github/shishark/faceblur/android/"
IOS = "faceblur/src/iosMain/kotlin/io/github/shishark/faceblur/ios/"
IOS_WRITER = IOS + "IosPathWriter.kt"
WRITER_FILES = (ANDROID + "AndroidModelFileReader.kt", ANDROID + "ContentUriWriter.kt", ANDROID + "PathWriteRules.kt",
                IOS_WRITER)
MUTATION_FILE = ANDROID + "PathWriteRules.kt"
WRITE_FLAG_FILES = (MUTATION_FILE, IOS_WRITER)
# POSIX / Darwin calls that change the file system (rule 3 through WRITE, rule 9).
NATIVE_MUTATIONS = (r"creat|write|pwrite|writev|link|linkat|symlink|symlinkat|rename|renameat|renamex_np|renameatx_np"
                    r"|unlink|unlinkat|remove|fsync|fdatasync|ftruncate|truncate|mkdir|rmdir|mkstemp|mkdtemp|mkfifo"
                    r"|chmod|fchmod|chown|fchown|clonefile|clonefileat|fclonefileat|copyfile|fcopyfile|exchangedata"
                    r"|fopen|fdopen|fwrite|setxattr|fsetxattr|removexattr|fremovexattr")
# ImageIO / CoreGraphics / CoreFoundation calls that write a file by URL or name (rule 3 through WRITE).
CF_FILE_WRITES = (r"CGImageDestinationCreateWithURL|CGDataConsumerCreateWithURL|CFWriteStreamCreateWithFile"
                  r"|CFURLWriteDataAndPropertiesToResource|CFURLDestroyResource")
# A line that can change the file system (create, write, truncate, publish, remove, change attributes).
WRITE = re.compile(
    r"\bOs\s*\.\s*(write|pwrite|writev|ftruncate|truncate|rename|link|symlink|unlink|remove|rmdir|mkdir|mkfifo|creat"
    r"|fsync|fdatasync|posix_fallocate|sendfile|chmod|fchmod|chown|fchown|lchown|setxattr|removexattr)\s*\("
    r"|\bO_(CREAT|WRONLY|RDWR|TRUNC|APPEND)\b"
    r"|open(Typed)?(Asset)?FileDescriptor\s*\([^)]*\"[^\"]*[wa+]"
    r"|\bMODE_(WRITE_ONLY|READ_WRITE|CREATE|TRUNCATE|APPEND)\b|\bPROT_WRITE\b|\bMAP_SHARED\b|\bMapMode\s*\.\s*READ_WRITE\b"
    r"|\.(outputStream|bufferedWriter|printWriter|writer|sink|appendingSink|copyTo|copyRecursively)\s*\("
    r"|openOutputStream|AutoCloseOutputStream|FileOutputStream|FileWriter|RandomAccessFile|openFileOutput"
    r"|\.(writeBytes|writeText|appendBytes|appendText|createNewFile|mkdirs?|renameTo|delete|deleteRecursively)\s*\("
    r"|createTempFile|createTempDirectory|\bFiles\s*\.\s*(write|copy|move|create|delete|newOutputStream|newBufferedWriter)"
    r"|\bdeleteFile\s*\(|\bdeleteDatabase\s*\(|getSharedPreferences|PreferenceManager|openOrCreateDatabase|SQLiteDatabase"
    r"|\bFileChannel\b|\bMappedByteBuffer\b|\bMemoryFile\s*\("
    r"|\bposix\.(creat|rename|unlink|mkstemp|ftruncate|fsync)\b|\b(fwrite|mkstemp|mkdtemp|unlink|ftruncate|truncate)\s*\("
    r"|\bplatform\s*\.\s*\w+\s*\.\s*(" + NATIVE_MUTATIONS + r")\s*\("
    r"|\b(pwrite|writev|linkat|symlink|symlinkat|renameat|renamex_np|renameatx_np|unlinkat|clonefile|clonefileat"
    r"|fclonefileat|copyfile|fcopyfile|exchangedata|fsetxattr)\s*\(|\b(" + CF_FILE_WRITES + r")\b"
    r"|NSFileManager|NSFileHandle|createFileAtPath|writeToFile|writeToURL|\bFileManager\b|\bFileHandle\b"
    r"|\.write\s*\(\s*to\s*:|\bcreateFile\s*\(|NSTemporaryDirectory|temporaryDirectory"
)
# An open whose flags or mode are not on the line: it can write unless its callers pass read-only flags.
POSSIBLE_WRITE = re.compile(
    r"\bOs\s*\.\s*open\s*\(|\bopen(Asset)?FileDescriptor\s*\((?![^)]*,\s*\"r\"\s*\))|\bposix\.open\b"
    r"|\b(fopen|fdopen|open|openat)\s*\(|\bmmap\s*\("
)
# An app storage directory or the temporary directory (rule 7); the library writes into neither.
STORAGE = re.compile(
    r"\b(get)?(Cache|CodeCache|Files|NoBackupFiles|Data|ExternalCache|ExternalFiles|ExternalMedia|Obb)Dirs?\b"
    r"|\b(cache|codeCache|files|noBackupFiles|data|externalCache|externalFiles|externalMedia|obb)Dirs?\b"
    r"|\bgetExternal\w*(Dir|Directory|Dirs|Directories)\b|\bgetDir\s*\(|\bgetDatabasePath\b|java\.io\.tmpdir"
    r"|\bcreateTemp(File|Directory)\b"
    r"|\bNSTemporaryDirectory\b|\bNSHomeDirectory\b|\bNSSearchPathForDirectoriesInDomains\b|\bNS\w*Directory\b"
    r"|_CS_DARWIN_USER_(TEMP|CACHE)_DIR|\bTMPDIR\b|\bSystemTemporaryDirectory\b"
    r"|\bcontainerURLForSecurityApplicationGroup"
    r"|\bURLForDirectory\b|\btemporaryDirectory\b"
)
# The descriptor layer (rule 8): each name only in the files that use it today.
DESCRIPTOR_FILES = {
    "OsDescriptors": (ANDROID + "AndroidModelFileReader.kt", ANDROID + "AndroidImageCodec.kt",
                      ANDROID + "AndroidTargetWriter.kt"),
    "FileCalls": (ANDROID + "FileCalls.kt", ANDROID + "AndroidModelFileReader.kt", ANDROID + "AndroidImageCodec.kt",
                  ANDROID + "AndroidTargetWriter.kt", ANDROID + "PathSourceRules.kt", ANDROID + "PathWriteRules.kt"),
    "DescriptorCalls": (ANDROID + "FileCalls.kt", ANDROID + "ModelFileRules.kt"),
}
DESCRIPTOR_NAME = re.compile(r"\b(OsDescriptors|FileCalls|DescriptorCalls)\b")
DIRECT_MUTATION = re.compile(r"\bOsDescriptors\s*\.\s*(create|write|fsync|link|rename|unlink)\b")
TYPED_VALUE = re.compile(r"\b(\w+)\s*:\s*(FileCalls|DescriptorCalls)\s*<")
WRITE_FLAG = re.compile(r"\bO_(CREAT|WRONLY|RDWR|TRUNC|APPEND)\b")
TEST = re.compile(r"\btest:\s*([A-Za-z0-9_.]+)::([A-Za-z0-9_]+)")
MUTATION = re.compile(r"\bcalls\s*\.\s*(create|write|link|rename|unlink)\s*\(")
DECLARATION = re.compile(r"\b(class|object)\s+(\w+)(.*)$")
COMMENT = re.compile(r"^\s*(//|/\*|\*)")
# Rule 9 (iOS path writer).
IOS_MUTATION = re.compile(r"\bcalls\s*\.\s*(createExclusive|put|sync|hardLink|replace|drop|syncDirectory)\s*\(")
IOS_SEAM_NAME = re.compile(r"\b(IosWriteCalls|IosPosixCalls)\b")
IOS_TYPED_VALUE = re.compile(r"\b(\w+)\s*:\s*IosWriteCalls\b")
IOS_DIRECT_MUTATION = re.compile(
    r"\bIosPosixCalls\s*\.\s*(createExclusive|put|sync|hardLink|replace|drop|syncDirectory)\b")
NATIVE_IMPORT = re.compile(r"^\s*import\s+platform\s*\.\s*\w+\s*\.\s*(" + NATIVE_MUTATIONS + r")\b")
NATIVE_QUALIFIED = re.compile(r"\bplatform\s*\.\s*\w+\s*\.\s*(" + NATIVE_MUTATIONS + r")\s*\(")
IOS_CALLS_START = re.compile(r"^(internal\s+|private\s+)?object\s+IosPosixCalls\b")


def usage_error(msg: str) -> None:
    print(f"check-write-sites: ERROR - {msg}", file=sys.stderr)
    sys.exit(2)


def read_lines(path: str) -> list:
    try:
        with open(path, encoding="utf-8") as fh:
            return fh.read().splitlines()
    except OSError as e:
        usage_error(f"{path} unreadable ({type(e).__name__})")
        return []


def allow_entries() -> set:
    out = set()
    for n, ln in enumerate(read_lines(ALLOW), 1):
        if not ln.strip() or ln.lstrip().startswith("#"):
            continue
        parts = ln.split("\t")
        if len(parts) != 4:
            usage_error(f"{ALLOW}:{n}: expected four TAB-separated fields")
        if parts[0] in FILE_RULES:
            out.add((parts[0], parts[1], parts[2]))
    return out


def register_rows() -> dict:
    lines = read_lines(REGISTER)
    if tuple(lines[:2]) != HEADER:
        usage_error(f"{REGISTER}: the first two lines must be the format header")
    rows = {}
    for n, ln in enumerate(lines[2:], 3):
        if not ln.strip() or ln.startswith("#"):
            continue
        parts = ln.split("\t")
        if len(parts) != 5 or len(parts[2]) != 64 or not parts[4].strip():
            usage_error(f"{REGISTER}:{n}: expected rule, path, sha256, kind, note")
        if parts[3] not in WRITE_KINDS + READ_KINDS:
            usage_error(f"{REGISTER}:{n}: unknown kind {parts[3]!r}")
        key = (parts[0], parts[1], parts[2])
        if key in rows:
            usage_error(f"{REGISTER}:{n}: duplicate row")
        rows[key] = (parts[3], parts[4], n)
    return rows


def implementer(line: str, seam: str = r"FileCalls\s*<"):
    """The name of a class or object declared on [line] whose supertypes name [seam] (default FileCalls), else None."""
    m = DECLARATION.search(line)
    if not m:
        return None
    rest = m.group(3)
    while True:  # drop the (balanced) constructor parameter lists, then read the supertype list after ':'
        shorter = re.sub(r"\([^()]*\)", "", rest)
        if shorter == rest:
            break
        rest = shorter
    supertypes = rest.split(":", 1)[1] if ":" in rest else ""
    return m.group(2) if re.search(r"\b" + seam, supertypes.split("{", 1)[0]) else None


def test_exists(cls: str, method: str) -> bool:
    simple = cls.rsplit(".", 1)[-1]
    package = cls.rsplit(".", 1)[0] if "." in cls else ""
    for f in glob.glob("faceblur/src/*Test/**/*.kt", recursive=True):
        text = "\n".join(read_lines(f))
        if re.search(rf"\bclass\s+{re.escape(simple)}\b", text) and re.search(rf"\bfun\s+{re.escape(method)}\s*\(", text) \
                and (not package or re.search(rf"^package\s+{re.escape(package)}\s*$", text, re.M)):
            return True
    return False


def posix_calls_lines(lines: list) -> set:
    """The line numbers of the IosPosixCalls object of IosPathWriter.kt: its declaration up to its `}` in column 0."""
    out, start = set(), None
    for nr, ln in enumerate(lines, 1):
        if start is None and IOS_CALLS_START.match(ln):
            start = nr
        if start is not None:
            out.add(nr)
            if nr > start and ln.rstrip() == "}":
                break
    return out


def ios_rules(f: str, nr: int, ln: str, inside: set) -> list:
    """Rule 9 on one code line [ln] (number [nr]) of the main source [f]; [inside]: the IosPosixCalls lines."""
    bad = []
    if IOS_MUTATION.search(ln) and f != IOS_WRITER:
        bad.append("a writer-call mutation outside IosPathWriter.kt")
    name = implementer(ln, r"IosWriteCalls\b")
    if name and name != "IosPosixCalls":
        bad.append(f"{name} implements IosWriteCalls (only IosPosixCalls may)")
    for m in IOS_SEAM_NAME.finditer(ln):
        if f != IOS_WRITER:
            bad.append(f"names {m.group(1)} outside IosPathWriter.kt")
    for m in IOS_TYPED_VALUE.finditer(ln):
        before = ln[:m.start(1)].split()
        if m.group(1) != "calls" and not (before and before[-1] in ("class", "object", "interface")):
            bad.append(f"an IosWriteCalls value named {m.group(1)!r} (must be named 'calls')")
    if IOS_DIRECT_MUTATION.search(ln):
        bad.append("a direct IosPosixCalls mutation (only IosPathWriter, through calls)")
    if NATIVE_IMPORT.search(ln):
        bad.append("imports a POSIX file mutation by name (call it qualified, inside IosPosixCalls only)")
    if NATIVE_QUALIFIED.search(ln) and (f != IOS_WRITER or nr not in inside):
        bad.append("a POSIX file mutation outside the IosPosixCalls object of IosPathWriter.kt")
    return bad


def main() -> int:
    if len(sys.argv) > 2:
        usage_error("usage: check-write-sites.py [repository root]")
    os.chdir(sys.argv[1] if len(sys.argv) == 2 else ".")
    allowed = allow_entries()
    rows = register_rows()
    bad = []
    for key in sorted(allowed - set(rows)):
        bad.append(f"{key[1]}: allow-list entry {key[2][:12]} ({key[0]}) has no row in {REGISTER} (classify it)")
    for key in sorted(set(rows) - allowed):
        bad.append(f"{REGISTER}:{rows[key][2]}: no allow-list entry for {key[1]} {key[2][:12]} (stale row)")
    texts = {}
    for key in sorted(set(rows) & allowed):
        rule, path, digest = key
        kind, note, n = rows[key]
        if path not in texts:
            texts[path] = read_lines(path) if os.path.isfile(path) else None
        if texts[path] is None:
            bad.append(f"{REGISTER}:{n}: {path} does not exist")
            continue
        found = [t.strip() for t in texts[path] if hashlib.sha256(t.strip().encode("utf-8")).hexdigest() == digest]
        if not found:
            bad.append(f"{REGISTER}:{n}: no line of {path} has the hash {digest[:12]}")
            continue
        text = found[0]
        if WRITE.search(text) and kind not in WRITE_KINDS:
            bad.append(f"{REGISTER}:{n}: {path}: a line that can write is classified {kind!r}")
        elif POSSIBLE_WRITE.search(text) and kind not in WRITE_KINDS and kind != "read-open":
            bad.append(f"{REGISTER}:{n}: {path}: an open with flags from its callers must be read-open or a write kind")
        if kind == "read-open":
            m = TEST.search(note)
            if not m:
                bad.append(f"{REGISTER}:{n}: a read-open row must name 'test: <class>::<method>'")
            elif not test_exists(m.group(1), m.group(2)):
                bad.append(f"{REGISTER}:{n}: the named test {m.group(1)}::{m.group(2)} does not exist")
        if STORAGE.search(text):
            bad.append(f"{REGISTER}:{n}: {path}: a classified line names an app storage or temporary directory")
        if kind in WRITE_KINDS and path not in WRITER_FILES:
            bad.append(f"{REGISTER}:{n}: {path}: a {kind} site outside the file-mode writer files")
    sources = sorted(glob.glob("faceblur/src/*Main/**/*.kt", recursive=True))
    if not sources:
        usage_error("no library main sources under faceblur/src")
    for f in sources:
        lines = read_lines(f)
        inside = posix_calls_lines(lines) if f == IOS_WRITER else set()
        for nr, ln in enumerate(lines, 1):
            if COMMENT.match(ln):
                continue
            if MUTATION.search(ln) and f != MUTATION_FILE:
                bad.append(f"{f}:{nr}: a file-call mutation outside {os.path.basename(MUTATION_FILE)}")
            name = implementer(ln)
            if name and name != "OsDescriptors":
                bad.append(f"{f}:{nr}: {name} implements FileCalls (only OsDescriptors may)")
            if STORAGE.search(ln):
                bad.append(f"{f}:{nr}: names an app storage or temporary directory")
            for m in DESCRIPTOR_NAME.finditer(ln):
                if f not in DESCRIPTOR_FILES[m.group(1)]:
                    bad.append(f"{f}:{nr}: names {m.group(1)} outside the files that use it today")
            if DIRECT_MUTATION.search(ln):
                bad.append(f"{f}:{nr}: a direct OsDescriptors mutation (only PathWriteRules.kt, through calls)")
            for m in TYPED_VALUE.finditer(ln):
                before = ln[:m.start(1)].split()
                if m.group(1) != "calls" and not (before and before[-1] in ("class", "object", "interface")):
                    bad.append(f"{f}:{nr}: a {m.group(2)} value named {m.group(1)!r} (must be named 'calls')")
            if WRITE_FLAG.search(ln) and f not in WRITE_FLAG_FILES:
                bad.append(f"{f}:{nr}: a write open flag outside the path writers "
                           f"(PathWriteRules.kt, IosPathWriter.kt)")
            bad.extend(f"{f}:{nr}: {b}" for b in ios_rules(f, nr, ln, inside))
    for b in bad:
        print(f"check-write-sites: {b}", file=sys.stderr)
    if bad:
        print(f"check-write-sites: FAIL - {len(bad)} finding(s)", file=sys.stderr)
        return 1
    writes = sum(1 for k in rows if rows[k][0] in WRITE_KINDS)
    print(f"check-write-sites: PASS - {len(rows)} file-I/O sites classified, {writes} write sites, all in the "
          f"file-mode writer files; file-call mutations only in {os.path.basename(MUTATION_FILE)} and "
          f"{os.path.basename(IOS_WRITER)}; no storage or temporary directory named; the descriptor layer and the "
          f"iOS writer calls only in their files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
