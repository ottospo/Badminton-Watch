package com.ottospo.tripcompanion;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class GpxMapTrailTest {
    @Test
    public void preservesTrackColorsAndPauseSegments() throws Exception {
        GpxMapTrail trail = parse("<gpx><wpt lat=\"40.1\" lon=\"-105.1\"><type>TrailTracker Photo</type></wpt>"
                + "<wpt lat=\"40.2\" lon=\"-105.2\"><type>Other</type></wpt><trk><extensions><TrackExtension>"
                + "<DisplayColor>#5AC8FA</DisplayColor></TrackExtension></extensions>"
                + "<trkseg><trkpt lat=\"40\" lon=\"-105\"/></trkseg>"
                + "<trkseg><trkpt lat=\"41\" lon=\"-104\"/></trkseg></trk>"
                + "<trk><trkseg><trkpt lat=\"42\" lon=\"-103\"/></trkseg></trk></gpx>");
        assertEquals(3, trail.segments.size());
        assertEquals(0xff5ac8fa, trail.segments.get(0).color);
        assertEquals(0xff5ac8fa, trail.segments.get(1).color);
        assertEquals(0xffe53935, trail.segments.get(2).color);
        assertEquals(41.0, trail.segments.get(1).points.get(0).latitude, 0.0);
        assertEquals(1, trail.photoMarkers.size());
        assertEquals(40.1, trail.photoMarkers.get(0).latitude, 0.0);
        assertEquals(-105.1, trail.photoMarkers.get(0).longitude, 0.0);
    }

    @Test
    public void rejectsEmptyInvalidAndDeclaredEntities() throws Exception {
        expectFailure("<gpx/>");
        expectFailure("<gpx><trk><trkseg><trkpt lat=\"NaN\" lon=\"0\"/></trkseg></trk></gpx>");
        expectFailure("<!DOCTYPE gpx [<!ENTITY x SYSTEM \"file:///test\">]>"
                + "<gpx><trk><trkseg><trkpt lat=\"0\" lon=\"0\"/></trkseg></trk>&x;</gpx>");
    }

    private static GpxMapTrail parse(String xml) throws IOException {
        return GpxMapTrail.read(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static void expectFailure(String xml) throws IOException {
        try {
            parse(xml);
            fail("Invalid GPX must be rejected");
        } catch (IOException expected) {
            // Expected.
        }
    }
}
