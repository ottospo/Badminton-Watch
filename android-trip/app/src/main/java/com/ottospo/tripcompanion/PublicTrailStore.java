package com.ottospo.tripcompanion;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

final class PublicTrailStore {
    private static final String RELATIVE_PATH = Environment.DIRECTORY_DOCUMENTS + "/Trails/";

    private PublicTrailStore() {}

    static SavedTrail[] list(Context context) throws IOException {
        ArrayList<SavedTrail> trails = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 29) {
            File documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            for (File file : GpxTrailStore.list(documents)) trails.add(fromFile(context, file, true));
        } else {
            Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            String[] columns = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME};
            String selection = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                    + MediaStore.MediaColumns.OWNER_PACKAGE_NAME + "=? AND "
                    + MediaStore.MediaColumns.IS_PENDING + "=0";
            try (Cursor cursor = context.getContentResolver().query(collection, columns, selection,
                    new String[]{RELATIVE_PATH, context.getPackageName()}, null)) {
                if (cursor == null) throw new IOException("Cannot list saved GPX files");
                while (cursor.moveToNext()) {
                    String name = cursor.getString(1);
                    if (name != null && name.startsWith("Trail_") && name.endsWith(".gpx")) {
                        Uri uri = ContentUris.withAppendedId(collection, cursor.getLong(0));
                        trails.add(new SavedTrail(name, RELATIVE_PATH + name, uri.toString(), null));
                    }
                }
            }
        }
        for (File file : GpxTrailStore.list(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS))) {
            trails.add(fromFile(context, file, false));
        }
        Collections.addAll(trails, FolderTrailStore.list(context));
        Collections.sort(trails, (a, b) -> b.name.compareTo(a.name));
        return trails.toArray(new SavedTrail[trails.size()]);
    }

    static void delete(Context context, SavedTrail trail) throws IOException {
        if (trail.file != null) {
            GpxTrailStore.delete(trail.file);
        } else {
            Uri uri = Uri.parse(trail.uri);
            ContentResolver resolver = context.getContentResolver();
            boolean deleted = DocumentsContract.isTreeUri(uri)
                    ? DocumentsContract.deleteDocument(resolver, uri)
                    : resolver.delete(uri, null, null) == 1;
            if (!deleted) throw new IOException("Cannot delete saved GPX file: " + trail.path);
        }
    }

    private static SavedTrail fromFile(Context context, File file, boolean shared) {
        return new SavedTrail(file.getName(), file.getAbsolutePath(),
                TrailShareProvider.uriFor(context, file, shared).toString(), file);
    }
}
