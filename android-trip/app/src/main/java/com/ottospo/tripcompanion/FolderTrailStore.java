package com.ottospo.tripcompanion;

import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;

final class FolderTrailStore {
    private static final String AUTHORITY = "com.android.externalstorage.documents";
    private static final String FOLDER_ID = "primary:Trails";
    private static final String PREFERENCES = "trail_folder";
    private static final String URI_KEY = "uri";
    private static final String TAG = "FolderTrailStore";

    private FolderTrailStore() {}

    static boolean isExpectedFolder(Uri uri) {
        return uri != null && "content".equals(uri.getScheme())
                && AUTHORITY.equals(uri.getAuthority()) && DocumentsContract.isTreeUri(uri)
                && FOLDER_ID.equals(DocumentsContract.getTreeDocumentId(uri));
    }

    static void rememberFolder(Context context, Uri uri) throws IOException {
        if (!isExpectedFolder(uri)) throw new IOException("Select Main Storage/Trails");
        if (!context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(URI_KEY, uri.toString()).commit()) {
            throw new IOException("Cannot remember the selected trail folder");
        }
    }

    static Uri selectedFolder(Context context) throws IOException {
        String value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(URI_KEY, null);
        if (value == null) return null;
        Uri uri = Uri.parse(value);
        if (!isExpectedFolder(uri)) throw new IOException("Invalid saved trail folder");
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (uri.equals(permission.getUri())
                    && permission.isReadPermission() && permission.isWritePermission()) return uri;
        }
        throw new IOException("Access to the trail folder has been revoked");
    }

    static synchronized SavedTrail save(Context context, TripTracker.TrailSnapshot trail, long stoppedAt)
            throws IOException {
        if (trail.points.length == 0) throw new IOException("Trail has no GPS points");
        Uri tree = selectedFolder(context);
        if (tree == null) throw new IOException("Trail folder has not been selected");
        ContentResolver resolver = context.getContentResolver();
        HashSet<String> names = new HashSet<>();
        for (SavedTrail existing : list(context, tree)) names.add(existing.name);
        String name = GpxTrailStore.availableName(stoppedAt, names::contains);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
        Uri temporary = DocumentsContract.createDocument(resolver, parent, "application/octet-stream",
                ".TrailTracker_" + UUID.randomUUID() + ".tmp");
        if (temporary == null) throw new IOException("Cannot create GPX file in Trails");
        Uri created = temporary;
        boolean saved = false;
        try {
            ParcelFileDescriptor descriptor = resolver.openFileDescriptor(temporary, "w");
            if (descriptor == null) throw new IOException("Cannot open GPX file for writing");
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new ParcelFileDescriptor.AutoCloseOutputStream(descriptor), StandardCharsets.UTF_8))) {
                GpxTrailStore.write(writer, trail);
                writer.flush();
                descriptor.getFileDescriptor().sync();
            }
            Uri renamed = DocumentsContract.renameDocument(resolver, temporary, name);
            if (renamed == null) throw new IOException("Cannot finish GPX file");
            created = renamed;
            String savedName;
            try (Cursor cursor = resolver.query(created,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) throw new IOException("Cannot read GPX filename");
                savedName = cursor.getString(0);
                if (savedName == null || savedName.isEmpty()) throw new IOException("Missing GPX filename");
            }
            saved = true;
            return new SavedTrail(savedName, "Main Storage/Trails/" + savedName,
                    created.toString(), null);
        } finally {
            if (!saved) {
                try {
                    if (!DocumentsContract.deleteDocument(resolver, created)) {
                        Log.e(TAG, "Cannot remove incomplete GPX file: " + created);
                    }
                } catch (IOException | SecurityException e) {
                    Log.e(TAG, "Cannot remove incomplete GPX file: " + created, e);
                }
            }
        }
    }

    static SavedTrail[] list(Context context) throws IOException {
        Uri tree = selectedFolder(context);
        return tree == null ? new SavedTrail[0] : list(context, tree);
    }

    private static SavedTrail[] list(Context context, Uri tree) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        ArrayList<SavedTrail> trails = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(children, columns, null, null, null)) {
            if (cursor == null) throw new IOException("Cannot list saved trails");
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                if (id != null && name != null && name.startsWith("Trail_") && name.endsWith(".gpx")) {
                    Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    trails.add(new SavedTrail(name, "Main Storage/Trails/" + name,
                            document.toString(), null));
                }
            }
        }
        return trails.toArray(new SavedTrail[trails.size()]);
    }
}
