// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Stands in for the system pickers in the on-phone test: the document provider a picker would hand the demo a URI of.
 * It lives in the test APK (its own package, its own process and private storage) and serves two folders:
 * <ul>
 * <li>{@code in/<name>}: the registered fixtures that scripts/phone-device-tests.sh placed in
 * {@code files/faceblur-fixtures/}; read-only;</li>
 * <li>{@code out/<name>}: the export, one file the test prepares empty and the demo's save writes, like a new document
 * that the system "save as" screen creates.</li>
 * </ul>
 * Calls: {@link #PREPARE} creates the empty export file, {@link #LIST} names the files of both folders (so the test
 * can show that nothing else was left), {@link #REMOVE} deletes the export. It is written in Java because the test
 * APK carries no Kotlin runtime: the provider runs in the test package's process, apart from the app's.
 */
public final class FixtureProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.shishark.faceblur.sample.debug.test.fixtures";
    public static final String INPUT_DIR = "in";
    public static final String OUTPUT_DIR = "out";
    public static final String PREPARE = "prepare";
    public static final String REMOVE = "remove";
    public static final String LIST = "list";
    private static final String FIXTURE_FOLDER = "faceblur-fixtures";
    private static final String EXPORT_FOLDER = "faceblur-export";
    private static final List<String> FOLDERS = Arrays.asList(INPUT_DIR, OUTPUT_DIR);

    /** The {@code content:} URI of {@code name} in {@code folder} ({@link #INPUT_DIR} or {@link #OUTPUT_DIR}). */
    public static Uri uri(String folder, String name) {
        return Uri.parse("content://" + AUTHORITY + "/" + folder + "/" + name);
    }

    /** The URI the calls go to. */
    public static Uri base() {
        return Uri.parse("content://" + AUTHORITY);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        switch (method) {
            case PREPARE: {
                File dir = exports();
                dir.mkdirs();
                File file = new File(dir, plain(arg));
                file.delete();
                try {
                    if (!file.createNewFile()) throw new IllegalStateException("the export file was not created");
                } catch (IOException e) {
                    throw new IllegalStateException("the export file was not created");
                }
                break;
            }
            case REMOVE:
                new File(exports(), plain(arg)).delete();
                break;
            case LIST:
                result.putStringArray(INPUT_DIR, names(inputs()));
                result.putStringArray(OUTPUT_DIR, names(exports()));
                break;
            default:
                throw new IllegalArgumentException("unknown call");
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String[] target = split(uri);
        File file = file(target);
        if (!file.isFile()) throw new FileNotFoundException("no such fixture");
        if (INPUT_DIR.equals(target[0]) && !"r".equals(mode)) throw new SecurityException("the fixtures are read-only");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String[] target = split(uri);
        File file = file(target);
        MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        if (file.isFile()) cursor.addRow(new Object[] {target[1], file.length()});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        String[] target = split(uri);
        return OUTPUT_DIR.equals(target[0]) && file(target).delete() ? 1 : 0;
    }

    private File inputs() {
        return new File(getContext().getFilesDir(), FIXTURE_FOLDER);
    }

    private File exports() {
        return new File(getContext().getFilesDir(), EXPORT_FOLDER);
    }

    private File file(String[] target) {
        return new File(INPUT_DIR.equals(target[0]) ? inputs() : exports(), target[1]);
    }

    private static String[] split(Uri uri) {
        List<String> parts = uri.getPathSegments();
        if (parts.size() != 2 || !FOLDERS.contains(parts.get(0))) throw new IllegalArgumentException("unknown path");
        return new String[] {parts.get(0), plain(parts.get(1))};
    }

    private static String plain(String name) {
        if (name == null || name.isEmpty() || name.contains("..")) throw new IllegalArgumentException("bad name");
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '.' && c != '_' && c != '-') {
                throw new IllegalArgumentException("not a plain file name");
            }
        }
        return name;
    }

    private static String[] names(File dir) {
        String[] names = dir.list();
        if (names == null) return new String[0];
        Arrays.sort(names);
        return names;
    }
}
