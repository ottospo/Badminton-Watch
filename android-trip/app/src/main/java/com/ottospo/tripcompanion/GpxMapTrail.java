package com.ottospo.tripcompanion;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

final class GpxMapTrail {
    static final class Point {
        final double latitude;
        final double longitude;

        Point(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    static final class Segment {
        final int color;
        final List<Point> points = new ArrayList<>();

        Segment(int color) {
            this.color = color;
        }
    }

    final List<Segment> segments = new ArrayList<>();
    final List<Point> photoMarkers = new ArrayList<>();
    private static final int DEFAULT_COLOR = 0xffe53935;

    static GpxMapTrail read(InputStream input) throws IOException {
        try {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) {
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            }
            parser.setInput(input, null);
            GpxMapTrail result = new GpxMapTrail();
            Segment segment = null;
            Point waypoint = null;
            boolean photoWaypoint = false;
            int color = DEFAULT_COLOR;
            boolean inTrack = false;
            boolean rootSeen = false;
            for (int event = parser.nextToken(); event != XmlPullParser.END_DOCUMENT;
                 event = parser.nextToken()) {
                if (event == XmlPullParser.DOCDECL || event == XmlPullParser.ENTITY_REF) {
                    throw new IOException("GPX document declarations and entities are not supported");
                }
                if (event == XmlPullParser.START_TAG) {
                    String name = parser.getName();
                    if (!rootSeen) {
                        if (!"gpx".equals(name)) throw new IOException("Not a GPX file");
                        rootSeen = true;
                    } else if ("wpt".equals(name)) {
                        waypoint = readPoint(parser);
                        photoWaypoint = false;
                    } else if (waypoint != null && "type".equals(name)) {
                        photoWaypoint = "TrailTracker Photo".equals(parser.nextText().trim());
                    } else if ("trk".equals(name)) {
                        inTrack = true;
                        color = DEFAULT_COLOR;
                    } else if (inTrack && "DisplayColor".equals(name) && segment == null) {
                        String value = parser.nextText().trim();
                        if (value.matches("#[0-9a-fA-F]{6}")) {
                            color = 0xff000000 | Integer.parseInt(value.substring(1), 16);
                        }
                    } else if (inTrack && "trkseg".equals(name)) {
                        segment = new Segment(color);
                        result.segments.add(segment);
                    } else if (segment != null && "trkpt".equals(name)) {
                        segment.points.add(readPoint(parser));
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    if ("wpt".equals(parser.getName())) {
                        if (waypoint != null && photoWaypoint) result.photoMarkers.add(waypoint);
                        waypoint = null;
                    }
                    if ("trkseg".equals(parser.getName())) segment = null;
                    if ("trk".equals(parser.getName())) inTrack = false;
                }
            }
            boolean hasPoints = false;
            for (Segment item : result.segments) hasPoints |= !item.points.isEmpty();
            if (!rootSeen || !hasPoints) throw new IOException("GPX file has no track points");
            return result;
        } catch (XmlPullParserException | NumberFormatException e) {
            throw new IOException("Cannot read GPX trail", e);
        }
    }

    private static Point readPoint(XmlPullParser parser) throws IOException {
        String lat = parser.getAttributeValue(null, "lat");
        String lon = parser.getAttributeValue(null, "lon");
        if (lat == null || lon == null) throw new IOException("GPX point has no coordinates");
        double latitude = Double.parseDouble(lat);
        double longitude = Double.parseDouble(lon);
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -85.05112878 || latitude > 85.05112878
                || longitude < -180 || longitude > 180) {
            throw new IOException("GPX point is outside the supported map area");
        }
        return new Point(latitude, longitude);
    }
}
