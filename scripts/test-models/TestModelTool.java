// SPDX-License-Identifier: Apache-2.0
//
// Test-model tool for the build (never part of the library or of a published artifact). It runs on the JVM test
// classpath of :faceblur, so it uses the ONE test-source ONNX writer (DerivedModels) and the library's own
// pre-parse walker (OnnxPreParse); it never reads a file through the library.
//
//   derive  Rebuilds the derived test models G0, G2, G3 (DerivedModels.Variant) and G1 (NMS in the graph,
//           DerivedModels.buildG1 with its default thresholds) from the reference fixture and checks each against its
//           pin (size + SHA-256). The source is checked against its own pin first and refused on any mismatch. Output
//           files are written only after every check passed; a failed run leaves no derived file behind.
//   fuzz    Crash-freedom fuzzing of the pre-parse walker: every mutated model must either parse or be refused with
//           FaceMaskException.ModelRejected; any other throwable, or a call that does not return within the time
//           limit, fails the run. The seed is printed first; round r uses its own generator derived from (seed, r),
//           so one failing round can be replayed alone (--start-round r --max-rounds 1).
//
// Output never contains model bytes or absolute paths (only file names, sizes, hashes of derived files, seeds).
// Usage (through Gradle: :faceblur:deriveTestModels, :faceblur:fuzzOnnxPreParse; see faceblur/build.gradle.kts):
//   derive --source <file> --sha256 <hex> --size <n> --out <dir> --pin G0=<hex>:<size> ... [--mutant <name>]
//          (a pin is required for every Variant and for G1; the builder mutants apply to the Variants only)
//   fuzz --seed <long|random> --seconds <n> [--max-rounds <n>] [--start-round <n>] [--plant-crash-round <n>]
//        [--call-timeout-ms <n>] <base model file>...
// Exit codes: 0 ok, 1 check failed / crash found, 2 usage or I/O error.

import io.github.shishark.faceblur.FaceMaskException;
import io.github.shishark.faceblur.internal.model.OnnxPreParse;
import io.github.shishark.faceblur.testmodel.DerivedModels;
import io.github.shishark.faceblur.testmodel.Onnx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class TestModelTool {
    private TestModelTool() {
    }

    /** A check that failed (exit 1); the message names what, never a path or model bytes. */
    static final class CheckFailed extends Exception {
        private static final long serialVersionUID = 1L;

        CheckFailed(String message) {
            super(message);
        }
    }

    /** Wrong arguments or unreadable input (exit 2). */
    static final class UsageError extends Exception {
        private static final long serialVersionUID = 1L;

        UsageError(String message) {
            super(message);
        }
    }

    public static void main(String[] args) {
        int code;
        try {
            if (args.length == 0) throw new UsageError("missing mode (derive | fuzz)");
            String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
            switch (args[0]) {
                case "derive" -> derive(rest);
                case "fuzz" -> fuzz(rest);
                default -> throw new UsageError("unknown mode " + args[0]);
            }
            code = 0;
        } catch (CheckFailed e) {
            System.out.println("test-model-tool: FAIL - " + e.getMessage());
            code = 1;
        } catch (UsageError e) {
            System.out.println("test-model-tool: ERROR - " + e.getMessage());
            code = 2;
        }
        System.out.flush();
        // System.exit also ends a fuzz worker thread that is still stuck in a call.
        System.exit(code);
    }

    // ------------------------------------------------------------------------------------------------- arguments --

    private static Map<String, List<String>> options(String[] args, List<String> positional) throws UsageError {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                if (i + 1 >= args.length) throw new UsageError("option " + a + " needs a value");
                out.computeIfAbsent(a, k -> new ArrayList<>()).add(args[++i]);
            } else {
                positional.add(a);
            }
        }
        return out;
    }

    private static String one(Map<String, List<String>> o, String name) throws UsageError {
        List<String> v = o.get(name);
        if (v == null || v.size() != 1) throw new UsageError("option " + name + " must be given exactly once");
        return v.get(0);
    }

    private static String optional(Map<String, List<String>> o, String name, String fallback) throws UsageError {
        List<String> v = o.get(name);
        if (v == null) return fallback;
        if (v.size() != 1) throw new UsageError("option " + name + " given more than once");
        return v.get(0);
    }

    private static long number(String text, String what) throws UsageError {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            throw new UsageError(what + " is not a number");
        }
    }

    private static byte[] read(Path p) throws UsageError {
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new UsageError("cannot read " + p.getFileName() + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void checkPin(byte[] bytes, String sha, long size, String what) throws CheckFailed {
        if (bytes.length != size) {
            throw new CheckFailed(what + ": size " + bytes.length + " bytes, pinned " + size);
        }
        String actual = sha256(bytes);
        if (!actual.equalsIgnoreCase(sha.trim())) {
            throw new CheckFailed(what + ": SHA-256 mismatch, got " + actual + ", pinned " + sha.trim().toLowerCase(Locale.ROOT));
        }
    }

    // ---------------------------------------------------------------------------------------------------- derive --

    /** Name (and file name stem) of the derived model with NonMaxSuppression in the graph. */
    static final String G1 = "G1";

    /** A Variant through the shared builder (with the requested mutant), or G1 exactly as the parity test pins it. */
    private static byte[] buildDerived(byte[] source, String name, DerivedModels.Mutant mutant) {
        if (!name.equals(G1)) {
            return DerivedModels.INSTANCE.build(source, DerivedModels.Variant.valueOf(name), mutant, 640);
        }
        // buildG1 with its default thresholds at 640 px; the builder mutants apply to the Variants only.
        return DerivedModels.INSTANCE.buildG1(
            source, DerivedModels.G1_SCORE_THRESHOLD, DerivedModels.G1_IOU_THRESHOLD, 640);
    }

    private static void derive(String[] args) throws CheckFailed, UsageError {
        List<String> positional = new ArrayList<>();
        Map<String, List<String>> o = options(args, positional);
        if (!positional.isEmpty()) throw new UsageError("derive takes no positional arguments");
        Path sourceFile = Path.of(one(o, "--source"));
        Path out = Path.of(one(o, "--out"));
        String mutantName = optional(o, "--mutant", "NONE");
        DerivedModels.Mutant mutant;
        try {
            mutant = DerivedModels.Mutant.valueOf(mutantName);
        } catch (IllegalArgumentException e) {
            throw new UsageError("unknown builder mutant " + mutantName);
        }
        // Every derived model: the Variants, then G1 (not a Variant: its graph adds NonMaxSuppression).
        List<String> models = new ArrayList<>();
        for (DerivedModels.Variant v : DerivedModels.Variant.values()) models.add(v.name());
        models.add(G1);
        Map<String, String[]> pins = new LinkedHashMap<>();
        for (String pin : o.getOrDefault("--pin", List.of())) {
            String[] kv = pin.split("[=:]");
            if (kv.length != 3) throw new UsageError("--pin must be <variant>=<sha256>:<size>");
            if (!models.contains(kv[0])) throw new UsageError("unknown variant " + kv[0]);
            if (pins.put(kv[0], new String[] {kv[1], kv[2]}) != null) {
                throw new UsageError("variant " + kv[0] + " pinned twice");
            }
        }
        for (String m : models) {
            if (!pins.containsKey(m)) {
                throw new UsageError("no pin for derived model " + m + " (gradle/yunet.properties)");
            }
        }

        // The source is refused unless it matches its own pin, even if the fetch step was skipped.
        byte[] source = read(sourceFile);
        checkPin(source, one(o, "--sha256"), number(one(o, "--size"), "--size"), "reference fixture " + sourceFile.getFileName());

        // Build and check everything first; write only when all derived models match their pins.
        Map<String, byte[]> built = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : pins.entrySet()) {
            String v = e.getKey();
            boolean g1 = v.equals(G1);
            byte[] b = buildDerived(source, v, mutant);
            if (!java.util.Arrays.equals(b, buildDerived(source, v, mutant))) {
                throw new CheckFailed("derived model " + v + " is not deterministic");
            }
            boolean mutated = !g1 && mutant != DerivedModels.Mutant.NONE;
            String what = "derived model " + v + (mutated ? " (builder mutant " + mutant + ")" : "");
            checkPin(b, e.getValue()[0], number(e.getValue()[1], "pinned size of " + v), what);
            built.put(v, b);
        }
        try {
            // Stale files (also files nobody should have put there) never survive a run.
            if (Files.isDirectory(out)) {
                try (var s = Files.list(out)) {
                    for (Path p : (Iterable<Path>) s::iterator) Files.delete(p);
                }
            }
            Files.createDirectories(out);
            for (Map.Entry<String, byte[]> e : built.entrySet()) {
                Path tmp = out.resolve(e.getKey() + ".onnx.part");
                Files.write(tmp, e.getValue());
                Files.move(tmp, out.resolve(e.getKey() + ".onnx"), StandardCopyOption.ATOMIC_MOVE);
                System.out.println("test-model-tool: derived " + e.getKey() + ".onnx size " + e.getValue().length
                    + " sha256 " + sha256(e.getValue()) + " matches its pin");
            }
        } catch (IOException e) {
            throw new UsageError("cannot write the derived models (" + e.getClass().getSimpleName() + ")");
        }
        System.out.println("test-model-tool: PASS - source and " + built.size() + " derived models match their pins");
    }

    // ------------------------------------------------------------------------------------------------------ fuzz --

    /** Thrown by the planted control in place of a walker result: it must be reported like a real crash. */
    static final class PlantedCrash extends RuntimeException {
        private static final long serialVersionUID = 1L;

        PlantedCrash() {
            super("planted crash (negative control)");
        }
    }

    private static long mix(long seed, long round) {
        long z = seed + 0x9E3779B97F4A7C15L * (round + 1);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 1 to 8 structural and byte-level mutations of [base]. */
    static byte[] mutate(byte[] base, SplittableRandom r) {
        byte[] m = base.clone();
        int n = 1 + r.nextInt(8);
        for (int i = 0; i < n && m.length > 0; i++) {
            switch (r.nextInt(9)) {
                case 0 -> m[r.nextInt(m.length)] = (byte) r.nextInt(256);
                case 1 -> m[r.nextInt(m.length)] = (byte) 0x80; // continuation bit: long varints
                case 2 -> m[r.nextInt(m.length)] ^= (byte) (1 << r.nextInt(3)); // wire-type bits of a tag
                case 3 -> m[r.nextInt(m.length)] = (byte) 0xFF;
                case 4 -> m = java.util.Arrays.copyOf(m, r.nextInt(m.length)); // truncation
                case 5 -> { // a run of random bytes
                    int at = r.nextInt(m.length);
                    int len = Math.min(m.length - at, 1 + r.nextInt(16));
                    for (int k = 0; k < len; k++) m[at + k] = (byte) r.nextInt(256);
                }
                case 6 -> { // duplicate a slice (repeated / nested fields)
                    int from = r.nextInt(m.length);
                    int len = Math.min(m.length - from, 1 + r.nextInt(64));
                    int at = r.nextInt(m.length + 1);
                    byte[] g = new byte[m.length + len];
                    System.arraycopy(m, 0, g, 0, at);
                    System.arraycopy(m, from, g, at, len);
                    System.arraycopy(m, at, g, at + len, m.length - at);
                    m = g;
                }
                case 7 -> { // delete a slice
                    int at = r.nextInt(m.length);
                    int len = Math.min(m.length - at, 1 + r.nextInt(64));
                    byte[] g = new byte[m.length - len];
                    System.arraycopy(m, 0, g, 0, at);
                    System.arraycopy(m, at + len, g, at, m.length - at - len);
                    m = g;
                }
                default -> { // a length-like varint byte nudged up or down
                    int at = r.nextInt(m.length);
                    m[at] = (byte) (m[at] + (r.nextBoolean() ? 1 : -1));
                }
            }
        }
        return m;
    }

    private static void fuzz(String[] args) throws CheckFailed, UsageError {
        List<String> files = new ArrayList<>();
        Map<String, List<String>> o = options(args, files);
        String seedText = one(o, "--seed");
        long seed = "random".equals(seedText) ? new SecureRandom().nextLong() : number(seedText, "--seed");
        long seconds = number(optional(o, "--seconds", "60"), "--seconds");
        long maxRounds = number(optional(o, "--max-rounds", Long.toString(Long.MAX_VALUE)), "--max-rounds");
        long startRound = number(optional(o, "--start-round", "0"), "--start-round");
        long plantRound = number(optional(o, "--plant-crash-round", "-1"), "--plant-crash-round");
        long callTimeout = number(optional(o, "--call-timeout-ms", "10000"), "--call-timeout-ms");
        if (seconds < 1 || maxRounds < 1 || startRound < 0 || callTimeout < 1) throw new UsageError("bounds must be positive");

        List<String> names = new ArrayList<>();
        List<byte[]> bases = new ArrayList<>();
        for (String f : files) {
            Path p = Path.of(f);
            names.add(p.getFileName().toString());
            bases.add(read(p));
        }
        // The synthetic YuNet-layout model of the test-source writer: small, so many more rounds per second.
        names.add("synthetic-yunet-layout");
        bases.add(Onnx.INSTANCE.yunetModel(640, 640, Collections.emptyList()));
        // Every base must parse unmutated, otherwise the run would only exercise the first refusal.
        for (int i = 0; i < bases.size(); i++) {
            try {
                OnnxPreParse.INSTANCE.parse(bases.get(i));
            } catch (RuntimeException e) {
                throw new CheckFailed("base " + names.get(i) + " does not parse unmutated (" + e.getClass().getSimpleName() + ")");
            }
        }
        System.out.println("test-model-tool: fuzz seed " + seed + " (replay: --seed " + seed + "), start round " + startRound
            + ", " + seconds + " s, bases " + String.join(", ", names));
        System.out.flush();

        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fuzz-walker");
            t.setDaemon(true);
            return t;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        long round = startRound;
        long done = 0;
        long refused = 0;
        try {
            while (done < maxRounds && System.nanoTime() < deadline) {
                final long thisRound = round;
                int baseIndex = (int) (round % bases.size());
                byte[] input = mutate(bases.get(baseIndex), new SplittableRandom(mix(seed, round)));
                Future<Boolean> call = worker.submit(() -> {
                    if (thisRound == plantRound) throw new PlantedCrash();
                    try {
                        OnnxPreParse.INSTANCE.parse(input);
                        return Boolean.FALSE;
                    } catch (FaceMaskException.ModelRejected e) {
                        return Boolean.TRUE;
                    }
                });
                try {
                    if (call.get(callTimeout, TimeUnit.MILLISECONDS)) refused++;
                } catch (TimeoutException e) {
                    throw new CheckFailed("seed " + seed + " round " + round + " (base " + names.get(baseIndex)
                        + "): the walker did not return within " + callTimeout + " ms");
                } catch (ExecutionException e) {
                    Throwable c = e.getCause();
                    StringBuilder where = new StringBuilder();
                    for (StackTraceElement el : c.getStackTrace()) {
                        if (where.length() > 0) where.append(" < ");
                        where.append(el.getClassName().replaceAll("^.*\\.", "")).append('.').append(el.getMethodName())
                            .append(':').append(el.getLineNumber());
                        if (where.length() > 300) break;
                    }
                    throw new CheckFailed("seed " + seed + " round " + round + " (base " + names.get(baseIndex) + "): "
                        + c.getClass().getName() + " at " + where + " (replay: --seed " + seed + " --start-round " + round
                        + " --max-rounds 1)");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new UsageError("interrupted");
                }
                round++;
                done++;
            }
        } finally {
            worker.shutdownNow();
        }
        if (plantRound >= 0) {
            throw new CheckFailed("the planted crash at round " + plantRound + " was not reached (" + done + " rounds)");
        }
        System.out.println("test-model-tool: PASS - fuzz seed " + seed + ": " + done + " rounds, " + refused
            + " refused with ModelRejected, " + (done - refused) + " parsed, no crash, no hang");
    }
}
