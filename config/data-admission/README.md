<!-- SPDX-License-Identifier: Apache-2.0 -->
# Data-admission guard

Every commit to this repository is treated as public. This guard blocks commits and pushes that would add data
that has not been admitted: secrets, unregistered data files, oversized files, confidential names or local
details, image metadata, and commits from a non-approved identity. It runs as a git hook on the maintainer's
machine and as a CI job. It uses only the Python 3 standard library (3.9+) and a pinned gitleaks.

## Files

| File | Purpose |
|---|---|
| `check.sh` | Entry point (`sh config/data-admission/check.sh <mode>`); runs `python3 -I guard.py` |
| `guard.py` | The checks (stdlib only) |
| `register.tsv` | Admitted non-code/binary files: `path<TAB>sha256<TAB>DR-nnn` (one row per file, mirrored from an approved data-register row) |
| `allowed-identities.txt` | Commit author/committer e-mail addresses that are allowed |
| `email-allowlist.txt` | E-mail addresses that may appear in files and commit messages (regular expressions) |
| `gitleaks.toml` | gitleaks configuration (default rules of the pinned version) |

## Checks

| ID | Check | Blocks when |
|---|---|---|
| a | Secrets | gitleaks 8.30.1 reports a finding (`--redact`; inline `gitleaks:allow` comments are ignored; a `.gitleaksignore` is read only from this directory) |
| b | Registration | a binary file, or a text file whose type is not code/configuration (for example `.json`, `.csv`, `.svg`, images, models), is not in `register.tsv`, or its SHA-256 differs from the row |
| c | Size | an unregistered file is larger than 1 MiB, or any file is larger than 10 MiB |
| d | Confidential data | a normalised token sequence of a path, file name, file content, commit message or author name matches the maintainer's confidential pattern list (local hooks and audit only, see below); a home-directory path (`/Users/<name>`, `/home/<name>`, `C:\Users\<name>`) appears; an e-mail address not in `email-allowlist.txt` appears |
| e | Image metadata | a JPEG has EXIF tags outside the allowlist (orientation, resolution, colour space, pixel dimensions), a GPS IFD, a thumbnail (EXIF IFD1, JFIF/JFXX), XMP, IPTC/Photoshop, MPF (extra images, gain maps), comments, unknown APP segments, more than one frame or bytes after the end marker; a PNG has text, EXIF, time, animation or unknown chunks or bytes after `IEND`; any other image format (GIF, WebP, HEIF/AVIF, TIFF, BMP) |
| f | Identity | the commit author or committer e-mail is not listed in `allowed-identities.txt` |
| g | Legal material | a file (text, or printable strings of a binary) or a commit message contains the internal-legal marker (see below), or a path lies under `legal/`, `docs/legal/`, `management/`, `reports/legal*` or `security-local/` at any depth (case-insensitive). Runs in every mode, including `ci` and `pre-push` |

The output never prints the text that matched a confidential pattern, a matched e-mail address or a user name;
paths that match a pattern are replaced by the blob ID. In `ci` mode every file path is replaced by its blob ID
(find the file with `git ls-tree -r HEAD | grep <blob>`).

Code and configuration types that need no registration (they are still scanned): Kotlin, Java, Swift,
Objective-C/C headers, Python, shell, Gradle, TOML, properties, ProGuard, XML, YAML, Markdown, plain text, ABI
dumps (`.api`), Xcode project/scheme/workspace files, plists, privacy manifests, `Package.resolved`,
`.gitignore`/`.gitattributes`/`.editorconfig`, extension-less UTF-8 text files (for example `LICENSE`, `gradlew`)
and `Contents.json` inside `.xcassets`. Everything else needs a register row. The Gradle wrapper JAR is binary
and needs a row too (with the checksum Gradle publishes for that version).

### Legal material (check g)

Internal legal documents (briefs, analyses, risk ratings, correspondence with counsel) never enter the
repository, its history, commit messages or CI logs. They carry a header made of the words `INTERNAL`, `LEGAL`
and `DO NOT PUBLISH`, joined by hyphens. The guard finds that marker in any letter case, with any number of
spaces, hyphen or dash variants, underscores, dots, colons, slashes or pipes between the words (or none). It
does not react to the ordinary words "legal" or "licence" in normal text. It also blocks every path under
`legal/`, `docs/legal/`, `management/`, `reports/legal*` and `security-local/` at any depth.
Allowed legal files: `LICENSE`, `NOTICE`, third-party licence texts under `licenses/`, SPDX headers, the
contribution policy (DCO) and an approved README licensing section.

### Confidential pattern list (kept outside the repository)

Nothing derived from the confidential patterns is committed: no plain text and no hashes. The list is a
plain-text file that only the maintainer has, one pattern per line: `ID<TAB>tier<TAB>pattern`. An entry that
should not exist as plain text even there is written as `ID<TAB>tier<TAB>sha256:<hex><TAB><token count>`, with the
SHA-256 and the token count of its normalised form as printed by `guard.py hash TEXT`; it matches exactly like the
plain entry would. A malformed hashed entry stops the guard (exit 2). The guard finds the list
through, in this order:
1. the environment variable `FACEBLUR_GUARD_PATTERNS` (path to the file), or
2. the repository-local git config key `faceblur.patternsFile` (`git config faceblur.patternsFile <file>`; stored
   in `.git/config`, never committed).

The pattern list must not be stored inside this or any other git work tree: the guard refuses such a file
(exit 2), and it blocks any committed text that contains the SHA-256 of a listed pattern.

Reviewed false positives (a public text that happens to contain a listed token sequence) are handled by an
exception list that is kept outside every repository as well (the guard refuses a list inside a git work tree,
exit 2). Each entry exempts one pattern on one exact line text of one file:
`PX-nnnn<TAB>sha256:<pattern hex><TAB><repository-relative path><TAB>sha256:<hex of the exact line>[<TAB>note]`.
The line hash is the SHA-256 of the line's UTF-8 bytes without its line terminator (LF, or CRLF). Every line of
that file whose bytes are identical to the reviewed line is exempt (repeating a public line adds no information);
any change to the line makes the pattern block again. An exception applies only to the contents of a regular text
file at exactly that path: never to paths, file names, symlink targets, commit messages, author names or the
strings of binary files, never to a match that continues onto another line, and never to another pattern on the
same line. Applied exceptions are listed in the output by ID; `tree` and `audit` also list the entries that were not
used, so that dead entries can be removed. The guard finds the list through `FACEBLUR_GUARD_PATTERN_EXCEPTIONS`,
else the git config key `faceblur.patternExceptionsFile`, else a file `pattern-exceptions.tsv` next to the pattern
list (optional). A malformed entry (including a path with an empty, `.` or `..` segment), a duplicate ID or entry,
an entry for a pattern that is no longer in the list, or a list that cannot be read as UTF-8 text stops the guard
(exit 2). `test_guard.py` in this directory tests these rules with a pattern list generated at run time (no
confidential data); `scripts/test-gates.sh` runs it.

All local modes (`pre-commit`, `commit-msg`, `pre-push`, `tree`, `audit`) need the list and fail closed (exit 2)
when it is not configured or not readable. `ci` mode skips only this check (CI has no list) and runs every other
check. All commits are made on the maintainer's machine, where the hooks run; the pre-publication audit runs the
pattern check over the full history again.

Matching uses normalisation rule v1: Unicode NFKC, then `str.casefold()`; tokens are maximal runs of Unicode
letters and digits (`[^\W_]+`), every other character separates tokens; tokens are joined with one space. Every
token n-gram of the scanned text (paths, file names, contents, commit messages, author names) whose length occurs
in the list is compared, so `Some_File-Name.JPG`, `some file name jpg` and `SOME/FILE/NAME.jpg` all match the
same pattern. Findings show the pattern ID only, never the pattern.

## Invocation

Exit codes: `0` pass, `1` blocked (findings on stderr), `2` guard error (treat as blocked; fail closed).
Never bypass a block with `--no-verify`; fix the content, or register the file through the data register.

### Git hooks (`core.hooksPath=.githooks`)

One-time setup per clone on the maintainer's machine (local config, not committed):

```sh
git config core.hooksPath .githooks
git config faceblur.patternsFile <path to the confidential pattern list>
git config faceblur.gitleaks <path to gitleaks 8.30.1>     # unless gitleaks 8.30.1 is on PATH
```

```sh
# .githooks/pre-commit
#!/bin/sh
exec sh "$(git rev-parse --show-toplevel)/config/data-admission/check.sh" pre-commit

# .githooks/commit-msg
#!/bin/sh
exec sh "$(git rev-parse --show-toplevel)/config/data-admission/check.sh" commit-msg "$1"

# .githooks/pre-push   (git passes the refs on stdin; the guard reads them)
#!/bin/sh
exec sh "$(git rev-parse --show-toplevel)/config/data-admission/check.sh" pre-push
```

`pre-commit` checks the staged content (read from the index, not the working tree), the author and committer
identity git will use, and runs `gitleaks git --staged`. `commit-msg` checks the message and the identity again.
`pre-push` checks every commit that is about to be pushed (identity, message, every added or modified file) and
runs gitleaks over those commits; it also catches an author set with `git commit --author=...`.

gitleaks is found through `FACEBLUR_GITLEAKS`, then the repository-local git config key `faceblur.gitleaks`,
then `PATH`. Its version must be exactly 8.30.1.

### CI (GitHub Actions, Linux runner)

```yaml
  data-admission:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@<pinned full commit SHA>
        with:
          fetch-depth: 0          # full history: every commit is checked
      - name: Install gitleaks 8.30.1 (checksum-verified)
        run: |
          curl -sSfL -o /tmp/gl.tgz https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_linux_x64.tar.gz
          echo "551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb  /tmp/gl.tgz" | sha256sum -c -
          tar -xzf /tmp/gl.tgz -C /tmp gitleaks
          echo "FACEBLUR_GITLEAKS=/tmp/gitleaks" >> "$GITHUB_ENV"
      - name: Data admission (tree + full history)
        run: sh config/data-admission/check.sh ci --all
```

`ci --all` skips the confidential-pattern check (no list in CI) and checks every tracked file at `HEAD` (registration, size, patterns, paths, e-mails, metadata), then
every commit in the history (identity, message, every added or modified file against the register of that
commit), then runs gitleaks over the history. `ci --range <base>..<head>` limits the commit part to a range.
`audit` (maintainer's machine, needs the pattern list) additionally scans every blob reachable from any ref
(pre-publication audit). `tree --rev <rev>` checks
one tree only.

## Registering a file

1. Add a row to the internal data register and get both approvals (licence/personal data and
   confidentiality/secrets; nobody approves their own row).
2. Add `path<TAB>sha256<TAB>DR-nnn` to `register.tsv` in the same commit as the file
   (`shasum -a 256 <file>` on macOS, `sha256sum <file>` on Linux).
3. Images must pass check (e) even when registered: strip metadata before committing.

## Limits (what this guard does not do)

- It cannot recognise faces, personal data or confidential content that is not on the pattern list; registration
  and the two approvals are the control for data files.
- The confidential-pattern check runs only where the list exists (the maintainer's machine: hooks and audit).
  CI does not check confidential names; a commit created elsewhere is checked for names only by the pre-push hook
  (if it passes through this machine) or by the pre-publication audit.
- gitleaks finds known secret formats and high-entropy strings; it is not exhaustive.
- Content inside compressed or encoded payloads (ZIP, base64 inside binaries) is not unpacked.
- A hook can be skipped locally (`--no-verify`); the CI job is the backstop for pushed commits.
