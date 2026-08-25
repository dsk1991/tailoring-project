package com.modernmarwar.tailoringstaff;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public class CaptureFileProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    static Uri uriFor(File file, String authority) {
        return new Uri.Builder().scheme("content").authority(authority).appendPath(file.getName()).build();
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("[A-Za-z0-9_.-]+")) throw new FileNotFoundException();
        File directory = new File(getContext().getCacheDir(), "captures");
        File file = new File(directory, name);
        try {
            if (!file.getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator)) {
                throw new FileNotFoundException();
            }
        } catch (Exception error) { throw new FileNotFoundException(); }
        return file;
    }

    @Override public String getType(Uri uri) { return "image/jpeg"; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolve(uri);
        int flags = mode.contains("w") ?
                ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_READ_WRITE :
                ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(file, flags);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = resolve(uri);
            MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            cursor.addRow(new Object[]{file.getName(), file.length()});
            return cursor;
        } catch (Exception error) { return null; }
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        try { return resolve(uri).delete() ? 1 : 0; } catch (Exception error) { return 0; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
