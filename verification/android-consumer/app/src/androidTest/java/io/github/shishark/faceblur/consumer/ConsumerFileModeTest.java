// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.shishark.faceblur.DetectionFormat;
import io.github.shishark.faceblur.Detection;
import io.github.shishark.faceblur.DetectorModel;
import io.github.shishark.faceblur.DetectorProfile;
import io.github.shishark.faceblur.FaceMasker;
import io.github.shishark.faceblur.FaceMaskerAndroid;
import io.github.shishark.faceblur.FileOptions;
import io.github.shishark.faceblur.FileResult;
import io.github.shishark.faceblur.Mask;
import io.github.shishark.faceblur.MaskPlan;
import io.github.shishark.faceblur.ModelSource;
import io.github.shishark.faceblur.OutputTarget;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * File mode of the PUBLISHED Android library, from a Java app that resolved it from the repository-local Maven
 * directory (never as a project dependency). One run on a real input: a JPEG made from an image of the maintainer's
 * local fixtures, carrying canary metadata (EXIF, XMP, a comment), masked with the reference model on the device.
 *
 * Checks: the output is a JPEG whose only APPn segment is the library's EXIF with Orientation = 1 and nothing else, with
 * no comment segment, none of the canary strings, nothing after the end of image; inside every mask the output differs
 * strongly from the same call with no masks and is flat; away from the masks the two outputs agree; no other file is
 * left next to the outputs. The fixtures are placed by scripts/consumer-device-test.sh in the app's private storage (the
 * model and the one image only); a missing fixture fails the test.
 */
@RunWith(AndroidJUnit4.class)
public class ConsumerFileModeTest {
    private static final String MODEL = "face_detection_yunet_2023mar.onnx";
    private static final String MODEL_SHA256 = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4";
    private static final String FACE = "face-0003542-s0625-x0000-y0000.png";
    private static final String[] CANARIES = {"FBCANARY-EXIF-DESCRIPTION", "FBCANARY-XMP", "FBCANARY-COMMENT"};

    /** Smallest mean channel difference between the masked and the unmasked output inside a mask core. */
    private static final double SEPARATION = 32.0;
    /** Largest standard deviation of the luma inside a masked core (a flat fill, JPEG noise allowed). */
    private static final double FLAT_SD = 10.0;
    /** Smallest standard deviation of the luma inside a core of the unmasked output (the face is there to be removed). */
    private static final double TEXTURE_SD = 20.0;
    /** Largest mean channel difference away from the masks: same input, same decode, same encoder. */
    private static final double OUTSIDE_TOLERANCE = 1.0;

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    private static byte[] segment(int marker, byte[] payload) {
        int length = payload.length + 2;
        return concat(new byte[] {(byte) 0xFF, (byte) marker, (byte) (length >> 8), (byte) length}, payload);
    }

    /** A TIFF (big-endian) with IFD0 = Orientation 1 and ImageDescription = the canary. */
    private static byte[] exifWithCanary() {
        byte[] text = ascii(CANARIES[0] + "\0");
        int textAt = 8 + 2 + 12 + 12 + 4;
        byte[] tiff = concat(
                ascii("MM"), new byte[] {0, 42, 0, 0, 0, 8},
                new byte[] {0, 2},
                new byte[] {0x01, 0x0E, 0, 2, 0, 0, 0, (byte) text.length, 0, 0, 0, (byte) textAt}, // ImageDescription
                new byte[] {0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 1, 0, 0}, // Orientation = 1
                new byte[] {0, 0, 0, 0},
                text);
        return segment(0xE1, concat(ascii("Exif\0\0"), tiff));
    }

    private static boolean contains(byte[] haystack, String needle) {
        byte[] n = ascii(needle);
        outer:
        for (int i = 0; i + n.length <= haystack.length; i++) {
            for (int j = 0; j < n.length; j++) if (haystack[i + j] != n[j]) continue outer;
            return true;
        }
        return false;
    }

    /** The registered face, cropped to 640 x 480, as a JPEG with the canary segments right after SOI. */
    private static byte[] inputJpeg(File face) throws IOException {
        Bitmap full = BitmapFactory.decodeFile(face.getPath());
        Bitmap crop = Bitmap.createBitmap(full, 0, 0, 640, 480);
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        assertTrue(crop.compress(Bitmap.CompressFormat.JPEG, 92, plain));
        byte[] jpeg = plain.toByteArray();
        byte[] xmp = segment(0xE1, ascii("http://ns.adobe.com/xap/1.0/\0<x:xmpmeta>" + CANARIES[1] + "</x:xmpmeta>"));
        byte[] comment = segment(0xFE, ascii(CANARIES[2]));
        return concat(Arrays.copyOf(jpeg, 2), exifWithCanary(), xmp, comment, Arrays.copyOfRange(jpeg, 2, jpeg.length));
    }

    private static int u8(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    /** Asserts the privacy of an output file; written for this test, sharing no code with the library. */
    private static void assertPrivateJpeg(String label, byte[] b) {
        assertTrue(label + ": SOI", b.length > 4 && u8(b, 0) == 0xFF && u8(b, 1) == 0xD8);
        int p = 2;
        int app1 = 0;
        byte[] exif = null;
        while (true) {
            assertTrue(label + ": marker at " + p, p + 4 <= b.length && u8(b, p) == 0xFF);
            int code = u8(b, p + 1);
            int length = (u8(b, p + 2) << 8) | u8(b, p + 3);
            assertTrue(label + ": segment inside the file", p + 2 + length <= b.length);
            assertFalse(label + ": no comment or APPn other than EXIF", code == 0xFE || (code >= 0xE2 && code <= 0xEF));
            if (code == 0xE1) {
                app1++;
                exif = Arrays.copyOfRange(b, p + 4, p + 2 + length);
            }
            p += 2 + length;
            if (code == 0xDA) break;
        }
        boolean eoi = false;
        while (p + 1 < b.length) {
            if (u8(b, p) == 0xFF) {
                int next = u8(b, p + 1);
                if (next == 0xD9) {
                    eoi = p + 2 == b.length;
                    break;
                }
                assertTrue(label + ": only stuffing or restart markers in the scan", next == 0 || (next >= 0xD0 && next <= 0xD7));
                p += 2;
            } else {
                p++;
            }
        }
        assertTrue(label + ": the file ends at the end of image", eoi);
        assertEquals(label + ": exactly one APP1", 1, app1);
        // "Exif\0\0", TIFF header, one IFD0 entry (Orientation, SHORT, 1 value, 1), no next IFD: 6 + 8 + 2 + 12 + 4 bytes.
        assertEquals(label + ": EXIF holds nothing but the orientation", 6 + 8 + 2 + 12 + 4, exif.length);
        assertTrue(label + ": EXIF header", Arrays.equals(Arrays.copyOf(exif, 6), ascii("Exif\0\0")));
        boolean big = exif[6] == 'M';
        int t = 6;
        int entries = big ? (u8(exif, t + 8) << 8) | u8(exif, t + 9) : (u8(exif, t + 9) << 8) | u8(exif, t + 8);
        int tag = big ? (u8(exif, t + 10) << 8) | u8(exif, t + 11) : (u8(exif, t + 11) << 8) | u8(exif, t + 10);
        int value = big ? (u8(exif, t + 18) << 8) | u8(exif, t + 19) : (u8(exif, t + 19) << 8) | u8(exif, t + 18);
        assertEquals(label + ": one entry", 1, entries);
        assertEquals(label + ": Orientation tag", 0x0112, tag);
        assertEquals(label + ": Orientation = 1", 1, value);
        for (String canary : CANARIES) assertFalse(label + ": no " + canary, contains(b, canary));
        assertFalse(label + ": no XMP", contains(b, "http://ns.adobe.com") || contains(b, "<x:xmpmeta"));
    }

    /** Control: the privacy scan must fail on [bytes], so that its passes mean something. */
    private static void expectRejected(String what, byte[] bytes) {
        boolean rejected = false;
        try {
            assertPrivateJpeg("planted", bytes);
        } catch (AssertionError expected) {
            rejected = true;
        }
        assertTrue("the scan must reject " + what, rejected);
    }

    private static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inMutable = true;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        assertTrue("decodable", bitmap != null);
        return bitmap;
    }

    /** {mean absolute difference (largest channel), luma sd of a, luma sd of b} over the box. */
    private static double[] compare(Bitmap a, Bitmap b, int left, int top, int right, int bottom) {
        int w = right - left;
        int h = bottom - top;
        int[] pa = new int[w * h];
        int[] pb = new int[w * h];
        a.getPixels(pa, 0, w, left, top, w, h);
        b.getPixels(pb, 0, w, left, top, w, h);
        long[] sum = new long[3];
        double[] la = new double[w * h];
        double[] lb = new double[w * h];
        for (int i = 0; i < pa.length; i++) {
            sum[0] += Math.abs(Color.red(pa[i]) - Color.red(pb[i]));
            sum[1] += Math.abs(Color.green(pa[i]) - Color.green(pb[i]));
            sum[2] += Math.abs(Color.blue(pa[i]) - Color.blue(pb[i]));
            la[i] = 0.299 * Color.red(pa[i]) + 0.587 * Color.green(pa[i]) + 0.114 * Color.blue(pa[i]);
            lb[i] = 0.299 * Color.red(pb[i]) + 0.587 * Color.green(pb[i]) + 0.114 * Color.blue(pb[i]);
        }
        double mean = Math.max(sum[0], Math.max(sum[1], sum[2])) / (double) pa.length;
        return new double[] {mean, sd(la), sd(lb)};
    }

    private static double sd(double[] v) {
        double mean = 0;
        for (double x : v) mean += x;
        mean /= v.length;
        double s = 0;
        for (double x : v) s += (x - mean) * (x - mean);
        return Math.sqrt(s / v.length);
    }

    @Test
    public void fileModeFromThePublishedAarMasksAndStripsMetadata() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File fixtures = new File(context.getFilesDir(), "faceblur-fixtures");
        File model = new File(fixtures, MODEL);
        File face = new File(fixtures, FACE);
        assertTrue("fixture missing: " + MODEL, model.isFile());
        assertTrue("fixture missing: " + FACE, face.isFile());

        File dir = new File(context.getFilesDir(), "faceblur-scratch/consumer");
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) assertTrue(f.delete());
        assertTrue(dir.isDirectory() || dir.mkdirs());
        File input = new File(dir, "input.jpg");
        byte[] source = inputJpeg(face);
        Files.write(input.toPath(), source);
        for (String canary : CANARIES) assertTrue("control: the input holds " + canary, contains(source, canary));

        DetectorProfile profile = new DetectorProfile(new DetectionFormat.YuNetLayout(), 640, 640, 0.5f, MODEL_SHA256);
        DetectorModel detector = DetectorModel.load(ModelSource.fromFile(model.getPath()), profile);
        File masked = new File(dir, "masked.jpg");
        File unmasked = new File(dir, "unmasked.jpg");
        List<Mask> masks;
        try (FaceMasker masker = new FaceMasker(detector)) {
            Uri uri = Uri.fromFile(input);
            FileResult result = FaceMaskerAndroid.maskFile(masker, context, uri, new OutputTarget.NewFile(masked.getPath()));
            assertTrue("a face was found", result.getFaces().size() >= 1);
            assertEquals(640, result.getOutputWidth());
            assertEquals(480, result.getOutputHeight());
            masks = new ArrayList<>(result.getMasks());
            // The same call with no masks: the control for the pixel checks.
            Detection detection = FaceMaskerAndroid.detectFile(masker, context, uri);
            MaskPlan none = detection.toMaskPlan().withMasks(Collections.<Mask>emptyList());
            FaceMaskerAndroid.maskFile(
                    masker, context, uri, new OutputTarget.NewFile(unmasked.getPath()), new FileOptions(), none);
        }

        byte[] maskedBytes = Files.readAllBytes(masked.toPath());
        byte[] plainBytes = Files.readAllBytes(unmasked.toPath());
        assertPrivateJpeg("masked output", maskedBytes);
        assertPrivateJpeg("unmasked output", plainBytes);
        // Controls: the same scan fails on the input with its metadata and on planted defects in the output.
        expectRejected("the input with its canary metadata", source);
        expectRejected("a comment segment", concat(Arrays.copyOf(maskedBytes, 2), segment(0xFE, ascii("x")),
                Arrays.copyOfRange(maskedBytes, 2, maskedBytes.length)));
        expectRejected("an XMP packet", concat(Arrays.copyOf(maskedBytes, 2),
                segment(0xE1, ascii("http://ns.adobe.com/xap/1.0/\0<x:xmpmeta/>")),
                Arrays.copyOfRange(maskedBytes, 2, maskedBytes.length)));
        expectRejected("a byte after the end of image", concat(maskedBytes, new byte[] {0}));

        Bitmap out = decode(maskedBytes);
        Bitmap plain = decode(plainBytes);
        assertEquals(640, out.getWidth());
        assertEquals(480, out.getHeight());
        for (Mask m : masks) {
            int left = Math.max(0, (int) Math.ceil(m.getCenterX() - m.getRadiusX() / 2));
            int top = Math.max(0, (int) Math.ceil(m.getCenterY() - m.getRadiusY() / 2));
            int right = Math.min(out.getWidth(), (int) Math.floor(m.getCenterX() + m.getRadiusX() / 2));
            int bottom = Math.min(out.getHeight(), (int) Math.floor(m.getCenterY() + m.getRadiusY() / 2));
            assertTrue("mask core large enough", (right - left) * (bottom - top) >= 16);
            double[] c = compare(out, plain, left, top, right, bottom);
            android.util.Log.i("FaceBlurConsumerTest", String.format(
                    "mask core %dx%d: separation %.1f, luma sd masked %.1f, unmasked %.1f",
                    right - left, bottom - top, c[0], c[1], c[2]));
            assertTrue("control: the unmasked core shows the face (sd " + c[2] + ")", c[2] >= TEXTURE_SD);
            assertTrue("the masked core differs from the unmasked output (" + c[0] + ")", c[0] >= SEPARATION);
            assertTrue("the masked core is flat (sd " + c[1] + ")", c[1] <= FLAT_SD);
        }
        // A 40 x 40 box in the bottom-right corner, which no mask may touch for this picture.
        for (Mask m : masks) {
            boolean touches = m.getCenterX() + m.getRadiusX() > 592 && m.getCenterX() - m.getRadiusX() < 632
                    && m.getCenterY() + m.getRadiusY() > 432 && m.getCenterY() - m.getRadiusY() < 472;
            assertFalse("the corner box is outside every mask", touches);
        }
        double[] corner = compare(out, plain, 592, 432, 632, 472);
        assertTrue("away from the masks the outputs agree (" + corner[0] + ")", corner[0] <= OUTSIDE_TOLERANCE);

        String[] left = dir.list();
        Arrays.sort(left);
        assertEquals("no copy of the source or temporary file is left",
                Arrays.asList("input.jpg", "masked.jpg", "unmasked.jpg"), Arrays.asList(left));
    }
}
