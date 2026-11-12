#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Planted-variant corpus for the SAST gate (ST-009 AC9/AC10; code and security review findings).

Every probe is one source file in a throw-away tree. A "bad" probe must produce at least one finding of the
expected rule; a "clean" probe must produce none. The corpus contains the code-reviewer's 14 bypass probes and the
security-engineer's 23-case corpus (as described in their reports), several variants per rule, Swift probes for
faceblur-swift, plus allow-list controls (an exact reviewed line passes, a changed line fails, a network finding can
never be allowed). Runs scripts/run-sast.sh unchanged.

Usage: OPENGREP=<binary> python3 -I scripts/test-sast-probes.py     Exit codes: 0 all as expected, 1 otherwise.
"""
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
NET = "faceblur-no-network-import|faceblur-no-network-use|faceblur-no-native-socket-call"
LOG = "faceblur-no-logging"
PROC = "faceblur-no-process-or-dynamic-code"
FILE = "faceblur-file-io-review|faceblur-native-file-io-review"
SNET = "faceblur-swift-no-network"
SLOG = "faceblur-swift-no-logging"
SPROC = "faceblur-swift-process-or-dynamic-code"
SFILE = "faceblur-swift-file-io-review"
CLEAN = ""
U = "https://example.invalid"

# (id, source set or "swift", expected rule alternatives or CLEAN, code)
PROBES = [
    # --- code-reviewer bypass probes (14 cases)
    ("cr01_baseline", "common", CLEAN, "fun ok() = 1"),
    ("cr02_wildcard_java_net", "common", NET, f'import java.net.*\nfun n() = URL("{U}").openStream()'),
    ("cr03_uri_tourl", "common", NET, f'import java.net.URI\nfun n() = URI("{U}").toURL().openConnection()'),
    ("cr04_qualified_url", "common", NET, f'fun n() = java.net.URL("{U}").openStream()'),
    ("cr05_posix_wildcard", "ios", NET, "import platform.posix.*\nfun n() = socket(2, 1, 0)"),
    ("cr06_foundation_wildcard", "ios", NET, "import platform.Foundation.*\nfun n() = NSURLSession.sharedSession"),
    ("cr07_android_log", "android", LOG, 'fun n(p: String) { android.util.Log.d("t", p) }'),
    ("cr08_kotlin_io_println", "common", LOG, "fun n(p: String) { kotlin.io.println(p) }"),
    ("cr09_jul_qualified", "common", LOG, 'fun n(p: String) { java.util.logging.Logger.getLogger("x").info(p) }'),
    ("cr10_stdout", "common", LOG, "fun n(p: String) { System.out.println(p) }"),
    ("cr11_nslog_wildcard", "ios", LOG, "import platform.Foundation.*\nfun n(p: String) { NSLog(p) }"),
    ("cr12_ktor_wildcard", "common", NET, "import io.ktor.client.*\nfun n() = HttpClient()"),
    ("cr13_inet_import", "common", NET, 'import java.net.InetAddress.getByName\nfun n() = getByName("example.invalid")'),
    ("cr14_comment_only", "common", CLEAN, "// java.net.URL is not used here\nfun ok() = 1"),
    # --- security review corpus (the cases described in the review; further variants below)
    ("se01_alias_log", "android", LOG, 'import android.util.Log as L\nfun n(p: String) { L.d("t", p) }'),
    ("se02_import_socket", "jvm", NET, 'import java.net.Socket\nfun n() = Socket("example.invalid", 80)'),
    ("se03_ns_data_url", "ios", NET,
     f'import platform.Foundation.*\nfun n() = NSData.dataWithContentsOfURL(NSURL(string = "{U}"))'),
    ("se04_java_http_client", "jvm", NET, "fun n() = java.net.http.HttpClient.newHttpClient()"),
    ("se05_webview", "android", NET, f'fun n(w: android.webkit.WebView) = w.loadUrl("{U}")'),
    ("se06_download_manager", "android", NET, "fun n(c: android.content.Context) = c.getSystemService(android.app.DownloadManager::class.java)"),
    ("se07_backtick_import", "common", NET, f'import java.`net`.URL\nfun n() = URL("{U}")'),
    ("se08_process_builder", "jvm", PROC, 'fun n() = ProcessBuilder("ls").start()'),
    ("se09_runtime_exec", "jvm", PROC, 'fun n() = Runtime.getRuntime().exec("ls")'),
    ("se10_class_forname", "jvm", PROC, 'fun n() = Class.forName("x.Y").getDeclaredConstructor().newInstance()'),
    ("se11_load_library", "android", PROC, 'fun n() = System.loadLibrary("x")'),
    ("se12_alias_println", "common", LOG, 'import kotlin.io.println as say\nfun n(p: String) = say(p)'),
    ("se13_stdout_variable", "jvm", LOG, "fun n(p: String) { val out = System.out\n    out.println(p) }"),
    ("se14_dump_stack", "jvm", LOG, "fun n() = Thread.dumpStack()"),
    ("se15_file_write", "jvm", FILE, 'fun n(b: ByteArray) = java.io.File("x").writeBytes(b)'),
    ("se16_content_resolver_out", "android", FILE,
     "fun n(r: android.content.ContentResolver, u: android.net.Uri) = r.openOutputStream(u)"),
    ("se17_ns_file_manager", "ios", FILE,
     'import platform.Foundation.*\nfun n() = NSFileManager.defaultManager.createFileAtPath("x", null, null)'),
    # --- more variants per rule
    ("nv01_javax_ssl", "jvm", NET, "import javax.net.ssl.SSLSocketFactory\nfun n() = SSLSocketFactory.getDefault()"),
    ("nv02_okhttp", "android", NET, "import okhttp3.OkHttpClient\nfun n() = OkHttpClient()"),
    ("nv03_posix_explicit", "ios", NET, "import platform.posix.connect\nfun n() = 0"),
    ("nv04_unqualified_getaddrinfo", "ios", NET, "fun n() = getaddrinfo(null, null, null, null)"),
    ("nv05_nw_connection", "ios", NET, "fun n() = nw_connection_create(null, null)"),
    ("nv06_android_net_connectivity", "android", NET, "import android.net.ConnectivityManager\nfun n() = 0"),
    ("nv07_qualified_ktor", "common", NET, "fun n() = io.ktor.client.HttpClient()"),
    ("nv08_platform_network", "ios", NET, "import platform.Network.nw_path_monitor_create\nfun n() = 0"),
    ("lv01_println", "common", LOG, 'fun n(p: String) = println("value $p")'),
    ("lv02_print", "common", LOG, "fun n(p: String) = print(p)"),
    ("lv03_log_import", "android", LOG, 'import android.util.Log\nfun n(p: String) = Log.i("t", p)'),
    ("lv04_print_stack", "common", LOG, "fun n(e: Exception) = e.printStackTrace()"),
    ("lv05_stderr", "jvm", LOG, "fun n(p: String) = System.err.println(p)"),
    ("lv06_slf4j", "jvm", LOG, 'import org.slf4j.LoggerFactory\nval l = LoggerFactory.getLogger("x")'),
    ("lv07_os_log", "ios", LOG, "import platform.darwin.os_log_create\nfun n() = 0"),
    ("lv08_posix_printf", "ios", LOG, 'fun n() = platform.posix.printf("%s", "x")'),
    ("lv09_nslog_qualified", "ios", LOG, 'fun n(p: String) = platform.Foundation.NSLog(p)'),
    ("pv01_reflection", "jvm", PROC, 'fun n(c: Class<*>) = c.getDeclaredMethod("x").setAccessible(true)'),
    ("pv02_popen", "ios", PROC, 'fun n() = popen("ls", "r")'),
    ("pv03_system_load", "jvm", PROC, 'fun n() = System.load("/x.so")'),
    ("fv01_file_output_stream", "jvm", FILE, 'import java.io.FileOutputStream\nfun n() = FileOutputStream("x")'),
    ("fv02_temp_file", "jvm", FILE, 'fun n() = kotlin.io.path.createTempFile("x")'),
    ("fv03_nio_files", "jvm", FILE, "import java.nio.file.Files\nfun n(p: java.nio.file.Path) = Files.delete(p)"),
    ("fv04_posix_open", "ios", FILE, 'fun n() = open("x", 0)'),
    ("fv05_cache_dir", "android", FILE, "fun n(c: android.content.Context) = c.cacheDir"),
    ("fv06_write_text", "jvm", FILE, 'fun n(f: Any) = (f as java.io.File).writeText("x")'),
    # --- native file rule extended by security review 055 (iOS file mode): link, Darwin copies, by-URL ImageIO / CF
    ("fv07_posix_link", "ios", FILE, 'fun n(a: String, b: String) = platform.posix.link(a, b)'),
    ("fv08_darwin_clonefile", "ios", FILE, 'fun n(a: String, b: String) = platform.darwin.clonefile(a, b, 0u)'),
    ("fv09_copyfile", "ios", FILE, 'fun n(a: String, b: String) = copyfile(a, b, null, 0u)'),
    ("fv10_image_source_url", "ios", FILE, "fun n(u: platform.CoreFoundation.CFURLRef?) = CGImageSourceCreateWithURL(u, null)"),
    ("fv11_image_destination_url", "ios", FILE,
     "fun n(u: platform.CoreFoundation.CFURLRef?) = CGImageDestinationCreateWithURL(u, null, 1u, null)"),
    ("fv12_cf_write_stream", "ios", FILE, "fun n(u: platform.CoreFoundation.CFURLRef?) = CFWriteStreamCreateWithFile(null, u)"),
    ("ok13_ios_link_words", "ios", CLEAN, "fun hardLink(a: Int) = a\nfun n(x: Int) = hardLink(x) + linkCount(x)\nfun linkCount(x: Int) = x"),
    # --- android.system.Os, ParcelFileDescriptor, shared memory, java.lang.Runtime references (security review of the
    #     Android runner, 2026-11-03: Os file calls were not matched by the file rule)
    ("ao01_os_socket", "android", NET, "fun n() = android.system.Os.socket(2, 1, 0)"),
    ("ao02_os_getaddrinfo", "android", NET, 'import android.system.Os\nfun n() = Os.android_getaddrinfo("example.invalid", null, 0)'),
    ("ao03_os_alias_import", "android", NET, 'import android.system.Os as P\nfun n() = P.open("x", 0, 0)'),
    ("ao04_os_member_import", "android", NET, 'import android.system.Os.open\nfun n() = open("x", 0, 0)'),
    ("ao05_os_method_reference", "android", NET, "import android.system.Os\nval f = Os::read"),
    ("ao06_libcore", "android", NET, "fun n() = libcore.io.Libcore.os.getpid()"),
    ("ao07_os_open", "android", FILE, 'import android.system.Os\nfun n() = Os.open("x", 0, 0)'),
    ("ao08_os_read_qualified", "android", FILE, "fun n(fd: FD, b: ByteArray) = android.system.Os.read(fd, b, 0, 1)"),
    ("ao09_os_symlink", "android", FILE, 'import android.system.Os\nfun n() = Os.symlink("a", "b")'),
    ("ao10_os_fstat_backtick", "android", FILE, "fun n(fd: FD) = android.system.`Os`.fstat(fd)"),
    ("ao11_pfd_open", "android", FILE, "fun n(f: Any) = android.os.ParcelFileDescriptor.open(f, 0)"),
    ("ao12_shared_memory", "android", FILE, "fun n() = android.os.SharedMemory.create(null, 16)"),
    ("ao13_file_channel", "jvm", FILE, "fun n(c: java.nio.channels.FileChannel) = c.size()"),
    ("ao14_os_setenv", "android", PROC, 'import android.system.Os\nfun n() = Os.setenv("A", "1", true)'),
    ("ao15_os_kill", "android", PROC, "fun n(p: Int) = android.system.Os.kill(p, 9)"),
    ("ao16_runtime_returned", "android", PROC, "internal fun rt(): Runtime = holder.value"),
    ("ao17_runtime_stored", "android", PROC, "private val r: java.lang.Runtime = holder.value"),
    ("ao18_runtime_method_reference", "jvm", PROC, "val f = Runtime::getRuntime"),
    ("ok05_os_constants", "android", CLEAN, "import android.system.OsConstants\nfun n(m: Int) = OsConstants.S_ISREG(m)"),
    # --- evasion probes (typealias and alias imports of Os, Runtime and System; generic Runtime type
    #     arguments; Context storage APIs that passed the file rule, PRIV-005)
    ("rv01_typealias_os_socket", "android", NET, "typealias P = android.system.Os\nfun n() = P.socket(2, 1, 0)"),
    ("rv02_typealias_os_open", "android", NET, 'typealias Q = android.system.Os\nfun n() = Q.open("x", 0, 0)'),
    ("rv03_runtime_alias_import", "android", PROC, "import java.lang.Runtime as R\nfun n() = R.getRuntime().halt(0)"),
    ("rv04_typealias_runtime", "android", PROC, "typealias RT = java.lang.Runtime\nfun n() = RT.getRuntime().halt(0)"),
    ("rv05_system_alias_import", "android", PROC, 'import java.lang.System as S\nfun n() = S.loadLibrary("x")'),
    ("rv06_typealias_imported_os", "android", NET, "import android.system.Os\ntypealias P = Os\nfun n() = P.socket(2, 1, 0)"),
    ("rv07_typealias_os_backticks", "android", NET,
     "internal typealias P = android.`system`.`Os`\nfun n() = P.socket(2, 1, 0)"),
    ("rv08_typealias_system", "jvm", PROC, 'typealias S = System\nfun n() = S.loadLibrary("x")'),
    ("rv09_lazy_runtime", "android", PROC, "fun n(l: Lazy<Runtime>) = l.value.halt(0)"),
    ("rv10_runtime_type_argument", "jvm", PROC, "fun n(p: Pair<Int, Runtime>) = p.second.halt(0)"),
    ("rv11_get_cache_dir", "android", FILE, "fun n(c: android.content.Context) = c.getCacheDir()"),
    ("rv12_open_file_output", "android", FILE,
     'fun n(c: android.content.Context, b: ByteArray) = c.openFileOutput("t.jpg", 0).use { it.write(b) }'),
    ("rv13_get_dir", "android", FILE, 'fun n(c: android.content.Context) = c.getDir("t", 0)'),
    ("rv14_pfd_auto_close", "android", FILE,
     "fun n(p: android.os.ParcelFileDescriptor, b: ByteArray) =\n    android.os.ParcelFileDescriptor.AutoCloseOutputStream(p).use { it.write(b) }"),
    ("rv15_shared_preferences", "android", FILE,
     'fun n(c: android.content.Context) = c.getSharedPreferences("p", 0).edit().putString("k", "v").apply()'),
    ("rv16_open_file_input", "android", FILE, 'fun n(c: android.content.Context) = c.openFileInput("t.jpg")'),
    ("rv17_database", "android", FILE, 'fun n(c: android.content.Context) = c.openOrCreateDatabase("d", 0, null)'),
    ("rv18_no_backup_dir", "android", FILE, "fun n(c: android.content.Context) = c.noBackupFilesDir"),
    ("rv19_data_dir", "android", FILE, "fun n(c: android.content.Context) = c.dataDir"),
    ("rv20_get_code_cache_dir", "android", FILE, "fun n(c: android.content.Context) = c.getCodeCacheDir()"),
    # --- Kotlin/Java write calls on a File or stream that was made on another (reviewed) line
    ("wc01_output_stream", "android", FILE, "fun n(f: Any, b: ByteArray) = (f as F).outputStream().use { it.write(b) }"),
    ("wc02_buffered_writer", "jvm", FILE, 'fun n(f: F) = f.bufferedWriter().use { it.write("x") }'),
    ("wc03_print_writer", "jvm", FILE, 'fun n(f: F) = f.printWriter().use { it.print("x") }'),
    ("wc04_writer", "jvm", FILE, 'fun n(f: F) = f.writer(Charsets.UTF_8).use { it.write("x") }'),
    ("wc05_copy_to", "android", FILE, "fun n(f: F, g: F) = f.copyTo(g, overwrite = true)"),
    ("wc06_copy_recursively", "jvm", FILE, "fun n(f: F, g: F) = f.copyRecursively(g)"),
    ("wc07_sink", "common", FILE, "fun n(p: P) = p.sink().close()"),
    ("ok13_file_mode_writer", "common", CLEAN, "fun n() = FileModeRun.writer()"),
    # --- clean controls (must not be flagged)
    ("ok06_os_getenv", "android", CLEAN, 'import android.system.Os\nfun n() = Os.getenv("X")'),
    ("ok07_runtime_names", "common", CLEAN,
     'class E : RuntimeException("x")\nfun n(r: FaceBlurRuntime, m: ModelRuntime) = r'),
    ("ok08_other_os_words", "common", CLEAN, "fun n(macOs: Reader, os: Reader) = macOs.read() + os.read()"),
    ("ok09_typealias_kotlin", "android", CLEAN, "internal actual typealias HiddenFromJava = kotlin.jvm.JvmSynthetic"),
    ("ok10_typealias_os_like_names", "common", CLEAN, "typealias OsVersion = String\ntypealias Box = OsBox"),
    ("ok11_runtime_words_in_types", "common", CLEAN,
     "fun n(a: List<RuntimeException>, b: Map<String, ModelRuntime>, runtimeMs: Long) = a.size"),
    ("ok12_context_other_calls", "android", CLEAN,
     "fun n(c: android.content.Context, metadataDir: String) = c.packageName + c.getString(1) + metadataDir"),
    ("ok01_android_uri", "android", CLEAN, "import android.net.Uri\nfun n(u: Uri) = u.scheme"),
    ("ok02_kdoc_mentions", "common", CLEAN, "/**\n * Never call println or java.net.URL here.\n */\nfun ok(a: Int) = a + 1"),
    ("ok03_maths", "common", CLEAN, "fun area(w: Int, h: Int): Long = w.toLong() * h\nfun fileCount(n: Int) = n"),
    ("ok04_ios_cgimage", "ios", CLEAN, "import platform.CoreGraphics.CGImageRef\nfun n(i: CGImageRef?) = i != null"),
    # --- Swift (faceblur-swift Sources/)
    ("sw01_urlsession", "swift", SNET, f'import Foundation\nfunc n() {{ _ = URLSession.shared.dataTask(with: URL(string: "{U}")!) }}'),
    ("sw02_import_network", "swift", SNET, "import Network\nfunc n() {}"),
    ("sw03_nwconnection", "swift", SNET, 'func n() { _ = NWConnection(host: "x", port: 80, using: .tcp) }'),
    ("sw04_print", "swift", SLOG, 'func n(p: String) { print(p) }'),
    ("sw05_oslog", "swift", SLOG, "import os\nfunc n() {}"),
    ("sw06_nslog", "swift", SLOG, 'func n(p: String) { NSLog("%@", p) }'),
    ("sw07_logger", "swift", SLOG, 'func n() { _ = Logger(subsystem: "x", category: "y") }'),
    ("sw08_process", "swift", SPROC, "func n() { _ = Process() }"),
    ("sw09_dlopen", "swift", SPROC, 'func n() { _ = dlopen("x", 0) }'),
    ("sw10_filemanager", "swift", SFILE, 'func n() { _ = FileManager.default.createFile(atPath: "x", contents: nil) }'),
    ("sw11_data_write", "swift", SFILE, 'func n(d: Data, u: URL) throws { try d.write(to: u) }'),
    ("sw12_contents_of_url", "swift", SFILE, f'func n() throws {{ _ = try Data(contentsOf: URL(string: "{U}")!) }}'),
    ("sw13_clean", "swift", CLEAN, "/// Masks faces.\npublic struct Box { public let x: Int }"),
]

SET_DIRS = {"common": "commonMain", "jvm": "jvmMain", "android": "androidMain", "ios": "iosMain"}


def place(root: str, pid: str, where: str, code: str) -> str:
    if where == "swift":
        rel = f"Sources/FaceBlur/{pid}.swift"
        text = "// SPDX-License-Identifier: Apache-2.0\n" + code + "\n"
    else:
        rel = f"faceblur/src/{SET_DIRS[where]}/kotlin/x/{pid}.kt"
        text = "// SPDX-License-Identifier: Apache-2.0\npackage x\n\n" + code + "\n"
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)
    return rel


def run_sast(root: str, roots: list) -> tuple:
    res = subprocess.run(["sh", os.path.join(REPO, "scripts", "run-sast.sh"), *roots], cwd=root,
                         capture_output=True, text=True)
    with open(os.path.join(root, "build/reports/sast/opengrep.json"), encoding="utf-8") as fh:
        data = json.load(fh)
    return res.returncode, data, res.stdout + res.stderr


def main() -> int:
    if not os.environ.get("OPENGREP"):
        print("test-sast-probes: NOT RUN - set OPENGREP to the opengrep binary")
        return 1
    fails = 0
    with tempfile.TemporaryDirectory() as tmp:
        root = os.path.join(tmp, "repo")
        os.makedirs(os.path.join(root, "scripts"))
        shutil.copytree(os.path.join(HERE, "sast"), os.path.join(root, "scripts", "sast"))
        files = {pid: (place(root, pid, where, code), expect) for pid, where, expect, code in PROBES}
        code, data, out = run_sast(root, ["faceblur/src", "Sources"])
        if code != 1:
            print(f"test-sast-probes: FAIL - corpus run exit {code}, expected 1\n{out[-800:]}")
            return 1
        hits: dict = {}
        for r in data["results"]:
            hits.setdefault(r["path"], set()).add(r["check_id"].rsplit(".", 1)[-1])
        scanned = set(data["paths"]["scanned"])
        for pid, (rel, expect) in files.items():
            got = hits.get(rel, set())
            if rel not in scanned:
                ok, why = False, "not scanned"
            elif expect == CLEAN:
                ok, why = not got, f"unexpected {sorted(got)}"
            else:
                ok, why = bool(got & set(expect.split("|"))), f"got {sorted(got) or 'nothing'}, expected {expect}"
            if not ok:
                fails += 1
                print(f"test-sast-probes: {pid} FAIL - {why}")
        n_bad = sum(1 for _, _, e, _ in PROBES if e != CLEAN)
        print(f"test-sast-probes: corpus {len(PROBES)} probes ({n_bad} violations, {len(PROBES) - n_bad} clean), "
              f"{len(PROBES) - fails} as expected")

        # Allow-list controls: exact reviewed line passes; a changed line and a network finding never do.
        shutil.rmtree(os.path.join(root, "faceblur"))
        shutil.rmtree(os.path.join(root, "Sources"))
        rel = place(root, "al01", "jvm", 'fun n(b: ByteArray) = java.io.File("out.jpg").writeBytes(b)')
        line = 'fun n(b: ByteArray) = java.io.File("out.jpg").writeBytes(b)'
        key = hashlib.sha256(line.encode()).hexdigest()
        allow = os.path.join(root, "scripts", "sast", "allowlist.txt")
        with open(allow, "a", encoding="utf-8") as fh:
            fh.write(f"faceblur-file-io-review\t{rel}\t{key}\tTEST-REVIEW\n")
        checks = []
        code, _, out = run_sast(root, ["faceblur/src"])
        checks.append(("allow-exact-line-passes", code == 0, out))
        place(root, "al01", "jvm", 'fun n(b: ByteArray) = java.io.File("other.jpg").writeBytes(b)')
        code, _, out = run_sast(root, ["faceblur/src"])
        checks.append(("allow-changed-line-fails", code == 1, out))
        with open(allow, "a", encoding="utf-8") as fh:
            fh.write(f"faceblur-no-network-use\t{rel}\t{key}\tTEST-REVIEW\n")
        code, _, out = run_sast(root, ["faceblur/src"])
        checks.append(("network-rule-cannot-be-allowed", code == 2, out))
        # Scan coverage: a root with sources that the rules do not cover must not pass silently.
        with open(allow, "w", encoding="utf-8") as fh:
            fh.write("# empty\n")
        shutil.rmtree(os.path.join(root, "faceblur"))
        place(root, "cv01", "common", "fun ok() = 1")
        rules = os.path.join(root, "scripts", "sast", "rules.yml")
        with open(rules, encoding="utf-8") as fh:
            text = fh.read()
        with open(rules, "w", encoding="utf-8") as fh:
            fh.write(text.replace("/faceblur/src/", "/elsewhere/src/"))
        code, _, out = run_sast(root, ["faceblur/src"])
        checks.append(("zero-scanned-fails", code == 2, out))
        for name, ok, out in checks:
            if not ok:
                fails += 1
                print(f"test-sast-probes: {name} FAIL\n{out[-600:]}")
            else:
                print(f"test-sast-probes: {name} PASS")
    if fails:
        print(f"test-sast-probes: FAIL - {fails} unexpected result(s)")
        return 1
    print("test-sast-probes: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
