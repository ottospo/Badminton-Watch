package com.ottospo.tripcompanion;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

public class TrailShareProvider extends ContentProvider {
    static final String MIME_TYPE = "application/gpx+xml";

    static Uri uriFor(Context context, File file, boolean shared) {
        return new Uri.Builder().scheme("content")
                .authority(context.getPackageName() + ".trails")
                .appendPath(shared ? "shared" : "private")
                .appendPath(file.getName()).build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 2 || !segments.get(1).startsWith("Trail_")
                || !segments.get(1).endsWith(".gpx")
                || (!"shared".equals(segments.get(0)) && !"private".equals(segments.get(0)))) {
            throw new FileNotFoundException("Invalid trail URI");
        }
        try {
            File documents = "shared".equals(segments.get(0))
                    ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    : getContext().getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            File directory = GpxTrailStore.trailsDirectory(documents).getCanonicalFile();
            File file = new File(directory, segments.get(1)).getCanonicalFile();
            if (!directory.equals(file.getParentFile()) || !file.isFile()) {
                throw new FileNotFoundException("Trail not found");
            }
            return file;
        } catch (IOException e) {
            FileNotFoundException error = new FileNotFoundException("Cannot access trail");
            error.initCause(e);
            throw error;
        }
    }

    @Override
    public String getType(Uri uri) {
        return MIME_TYPE;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only provider");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file;
        try {
            file = resolve(uri);
        } catch (FileNotFoundException e) {
            throw new IllegalArgumentException("Invalid trail URI", e);
        }
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; ++i) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = file.getName();
            if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
        }
        cursor.addRow(values);
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }
}
