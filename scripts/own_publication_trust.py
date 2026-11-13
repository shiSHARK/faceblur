#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Throw-away dependency-verification entries for the library's OWN local publication (local merge gate, consumer device test and CI).

Why: the two consumer builds under verification/ resolve io.github.shishark:faceblur* from the freshly built local
Maven directory (build/local-maven). The artifacts of a release version are rebuilt on every run, so no checksum
can be committed in their verification-metadata.xml, and a group trust would also cover a remote copy. This script
computes the SHA-256 of exactly the files the build just wrote and adds exact <component>/<artifact> entries for them
to the verification-metadata.xml of the consumer build, in the CURRENT checkout only. `restore` puts the committed
file back. Nothing here may be committed; the committed metadata files are never edited.

Scope (hard limits, enforced below):
  - only group io.github.shishark, only the given release version (strict X.Y.Z, no SNAPSHOT, no ranges);
  - no <trusted-artifacts>, no group or module trust lines, only per-file <sha256> values;
  - an existing component entry for the same group/name/version in the metadata is an error (never overridden);
  - checksum and signature side files (.md5 .sha1 .sha256 .sha512 .asc) are not artifacts and are not listed.

Usage:
  own_publication_trust.py add --maven-dir build/local-maven --version 0.1.0 <verification-metadata.xml>...
  own_publication_trust.py restore <verification-metadata.xml>...
Every coordinate and hash is printed with the prefix "own-publication-trust:" (the gate copies these lines into its
log). Exit codes: 0 ok, 1 refused or failed, 2 usage error.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import sys

GROUP = "io.github.shishark"
BACKUP_SUFFIX = ".gate-original"
SIDE_FILES = (".md5", ".sha1", ".sha256", ".sha512", ".asc")
VERSION_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
NAME_RE = re.compile(r"^[A-Za-z0-9._-]+$")


def say(msg):
    print("own-publication-trust: " + msg)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def collect(maven_dir, version):
    """{module: [(file name, sha256)]} for build/local-maven/io/github/shishark/<module>/<version>/."""
    base = os.path.join(maven_dir, *GROUP.split("."))
    if not os.path.isdir(base):
        raise SystemExit("no %s publication under %s (publish first)" % (GROUP, maven_dir))
    found = {}
    for module in sorted(os.listdir(base)):
        vdir = os.path.join(base, module, version)
        if not os.path.isdir(vdir):
            say("ignored (no version %s): %s:%s" % (version, GROUP, module))
            continue
        if not NAME_RE.match(module):
            raise SystemExit("unexpected module directory name: %r" % module)
        files = []
        for name in sorted(os.listdir(vdir)):
            path = os.path.join(vdir, name)
            if not os.path.isfile(path) or name.endswith(SIDE_FILES) or name.startswith("maven-metadata"):
                continue
            if not name.startswith(module + "-" + version) or not NAME_RE.match(name):
                raise SystemExit("unexpected file in %s: %r" % (vdir, name))
            files.append((name, sha256(path)))
        if not files:
            raise SystemExit("no artifact files in %s" % vdir)
        # Gradle verifies a file under the `name` that the module metadata gives it, which can differ from the file
        # name in the repository (an Android AAR is published as faceblur-android-<v>.aar and named faceblur.aar in the
        # .module file). The same bytes of this run's file are listed under that name too; nothing else is added.
        hashes = dict(files)
        for name in [n for n, _ in files if n.endswith(".module")]:
            with open(os.path.join(vdir, name), encoding="utf-8") as f:
                meta = json.load(f)
            for variant in meta.get("variants", []):
                for ref in variant.get("files", []):
                    alias, url = ref.get("name"), ref.get("url")
                    if not alias or not url or "/" in url or "/" in alias or not NAME_RE.match(alias):
                        raise SystemExit("unexpected file reference in %s: %r" % (name, ref))
                    if url not in hashes:
                        raise SystemExit("%s references %s, which the build did not write" % (name, url))
                    if alias not in hashes:
                        hashes[alias] = hashes[url]
                        files.append((alias, hashes[url]))
        found[module] = files
    if not found:
        raise SystemExit("no %s module has version %s under %s" % (GROUP, version, maven_dir))
    return found


def add(args):
    if not VERSION_RE.match(args.version):
        raise SystemExit("version %r is not a strict release version X.Y.Z (SNAPSHOT, ranges and wildcards are refused)" % args.version)
    found = collect(args.maven_dir, args.version)
    for meta in args.metadata:
        if not os.path.isfile(meta):
            raise SystemExit("no such verification metadata: " + meta)
        if os.path.exists(meta + BACKUP_SUFFIX):
            raise SystemExit("%s%s exists: a previous run was not restored" % (meta, BACKUP_SUFFIX))
        text = open(meta, encoding="utf-8").read()
        if text.count("</components>") != 1:
            raise SystemExit("%s: expected exactly one </components>" % meta)
        for module in found:
            if re.search(r'<component\s+group="%s"\s+name="%s"\s+version="%s"' % (re.escape(GROUP), re.escape(module), re.escape(args.version)), text):
                raise SystemExit("%s already has a component entry for %s:%s:%s (never overridden)" % (meta, GROUP, module, args.version))
        block = ""
        for module, files in found.items():
            block += '      <component group="%s" name="%s" version="%s">\n' % (GROUP, module, args.version)
            for name, digest in files:
                block += '         <artifact name="%s">\n' % name
                block += '            <sha256 value="%s" origin="Own local publication of this run (throw-away, not committed)" />\n' % digest
                block += "         </artifact>\n"
            block += "      </component>\n"
        shutil.copyfile(meta, meta + BACKUP_SUFFIX)
        with open(meta, "w", encoding="utf-8") as f:
            f.write(text.replace("   </components>", block + "   </components>", 1))
        say("%s: %d component entries added" % (meta, len(found)))
    for module, files in found.items():
        for name, digest in files:
            say("%s:%s:%s  %s  sha256 %s" % (GROUP, module, args.version, name, digest))


def restore(args):
    for meta in args.metadata:
        backup = meta + BACKUP_SUFFIX
        if os.path.exists(backup):
            os.replace(backup, meta)
            say("%s restored from the committed copy; throw-away copy deleted" % meta)
        else:
            say("%s: nothing to restore" % meta)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("add")
    a.add_argument("--maven-dir", required=True)
    a.add_argument("--version", required=True)
    a.add_argument("metadata", nargs="+")
    r = sub.add_parser("restore")
    r.add_argument("metadata", nargs="+")
    args = ap.parse_args()
    try:
        (add if args.cmd == "add" else restore)(args)
    except SystemExit as e:
        if isinstance(e.code, str):
            say("REFUSED - " + e.code)
            return 1
        raise
    return 0


if __name__ == "__main__":
    sys.exit(main())
