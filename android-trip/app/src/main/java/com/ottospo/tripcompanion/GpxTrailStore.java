package com.ottospo.tripcompanion;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class GpxTrailStore {
    private GpxTrailStore() {}

    static File trailsDirectory(File documents) throws IOException {
        if (documents == null) throw new IOException("External documents storage unavailable");
        return new File(documents, "Trails");
    }

    static synchronized File save(File documents, TripTracker.TrailSnapshot trail,
                                  long stoppedAt) throws IOException {
        if (trail.points.length == 0) throw new IOException("Trail has no GPS points");
        File directory = trailsDirectory(documents);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Cannot create Trails directory");
        }
        String name = availableName(stoppedAt, candidate -> new File(directory, candidate).exists());
        File result = new File(directory, name);
        File temporary = File.createTempFile("trail_", ".tmp", directory);
        boolean saved = false;
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary);
                 BufferedWriter writer = new BufferedWriter(
                         new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
                write(writer, trail);
                writer.flush();
                stream.getFD().sync();
            }
            if (!temporary.renameTo(result)) throw new IOException("Cannot finish GPX file");
            saved = true;
            return result;
        } finally {
            if (!saved && temporary.exists() && !temporary.delete()) {
                android.util.Log.w("GpxTrailStore", "Cannot remove incomplete GPX file: " + temporary);
            }
        }
    }

    static File[] list(File documents) throws IOException {
        File directory = trailsDirectory(documents);
        if (!directory.exists()) return new File[0];
        File[] files = directory.listFiles((dir, name) ->
                name.startsWith("Trail_") && name.endsWith(".gpx"));
        if (files == null) throw new IOException("Cannot list saved trails");
        Arrays.sort(files, Collections.reverseOrder());
        return files;
    }

    static void delete(File file) throws IOException {
        if (!file.delete()) throw new IOException("Cannot delete saved trail: " + file);
    }

    static String baseName(long stoppedAt) {
        return "Trail_" + new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US)
                .format(new Date(stoppedAt));
    }

    interface NameExists {
        boolean check(String name) throws IOException;
    }

    static String availableName(long stoppedAt, NameExists exists) throws IOException {
        String base = baseName(stoppedAt);
        String name = base + ".gpx";
        for (int suffix = 2; exists.check(name); ++suffix) {
            name = base + "_" + suffix + ".gpx";
        }
        return name;
    }

    static void write(BufferedWriter writer, TripTracker.TrailSnapshot trail) throws IOException {
        SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        time.setTimeZone(TimeZone.getTimeZone("UTC"));
        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        writer.write("<gpx version=\"1.1\" creator=\"TrailTracker\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        for (TripTracker.PhotoMarker marker : trail.photoMarkers) {
            writer.write("<wpt lat=\"" + Double.toString(marker.lat)
                    + "\" lon=\"" + Double.toString(marker.lng)
                    + "\"><name>Photo</name><sym>Camera</sym><type>TrailTracker Photo</type></wpt>\n");
        }
        TripTracker.TrailPoint previous = null;
        for (TripTracker.TrailPoint point : trail.points) {
            if (previous == null || point.transportMode != previous.transportMode) {
                if (previous != null) writer.write("</trkseg>\n</trk>\n");
                writer.write("<trk>\n<extensions><TrackExtension xmlns=\"urn:trailtracker:gpx:color:1\">"
                        + "<DisplayColor>" + String.format(Locale.US, "#%06X",
                        point.transportMode.color & 0xFFFFFF)
                        + "</DisplayColor></TrackExtension></extensions>\n<trkseg>\n");
                if (previous != null && !point.breakBefore) writePoint(writer, previous, time);
            } else if (point.breakBefore) {
                writer.write("</trkseg>\n<trkseg>\n");
            }
            writePoint(writer, point, time);
            previous = point;
        }
        if (previous != null) writer.write("</trkseg>\n</trk>\n");
        writer.write("</gpx>\n");
    }

    private static void writePoint(BufferedWriter writer, TripTracker.TrailPoint point,
                                   SimpleDateFormat time) throws IOException {
        writer.write("<trkpt lat=\"" + Double.toString(point.lat)
                + "\" lon=\"" + Double.toString(point.lng) + "\">");
        if (point.hasAltitude && Double.isFinite(point.altitudeM)) {
            writer.write("<ele>" + Double.toString(point.altitudeM) + "</ele>");
        }
        if (point.timeMillis > 0) {
            writer.write("<time>" + time.format(new Date(point.timeMillis)) + "</time>");
        }
        writer.write("</trkpt>\n");
    }
}
