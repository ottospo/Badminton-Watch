package com.ottospo.tripcompanion;

import android.location.Location;

import java.io.File;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Run with javac/java using src/test/stubs, Geo.java, TripTracker.java and GpxTrailStore.java. */
public final class GpxTrailStoreTest {
    public static void main(String[] args) throws Exception {
        File documents = Files.createTempDirectory("gpx-trail-test").toFile();
        File directory = new File(documents, "Trails");
        long stoppedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .parse("2026-11-19 10:30").getTime();
        try {
            TripTracker tracker = TripTracker.getInstance();
            tracker.start();
            expectFailure(() -> GpxTrailStore.save(documents, tracker.copyTrail(), stoppedAt));
            expectFailure(() -> GpxTrailStore.list(null));
            expectFailure(() -> GpxTrailStore.availableName(stoppedAt, name -> {
                throw new IOException("Cannot check filename");
            }));

            Location first = new Location("test");
            first.setAltitude(123.5);
            tracker.onFix(first);
            if (tracker.markPhoto() != TripTracker.PhotoMarkerResult.MARKED) {
                throw new AssertionError("Expected a photo marker");
            }
            tracker.pause();
            tracker.resume();
            Location second = new Location("test");
            second.setLatitude(40.00001);
            tracker.onFix(second);
            tracker.stop();
            TripTracker.TrailSnapshot snapshot = tracker.copyTrail();
            File saved = GpxTrailStore.save(documents, snapshot, stoppedAt);
            File savedAgain = GpxTrailStore.save(documents, snapshot, stoppedAt);
            if (!saved.getName().equals("Trail_2026-11-19_1030.gpx")
                    || !savedAgain.getName().equals("Trail_2026-11-19_1030_2.gpx")
                    || GpxTrailStore.list(documents).length != 2) {
                throw new AssertionError("Saves must use the local stop time and avoid name collisions");
            }
            SavedTrail savedChoice = new SavedTrail(saved.getName(), saved.getPath(), "test:first", saved);
            SavedTrail nextChoice = new SavedTrail(savedAgain.getName(), savedAgain.getPath(), "test:second", savedAgain);
            tracker.addSavedTrailForChoice(savedChoice);
            tracker.addSavedTrailForChoice(nextChoice);
            tracker.start();
            if (savedChoice != tracker.savedTrailAwaitingChoice()) {
                throw new AssertionError("Starting a trip must not discard a pending save choice");
            }
            tracker.acknowledgeSavedTrail(nextChoice);
            if (savedChoice != tracker.savedTrailAwaitingChoice()) {
                throw new AssertionError("Choices must be handled in save order");
            }

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document gpx = factory.newDocumentBuilder().parse(saved);
            String namespace = "http://www.topografix.com/GPX/1/1";
            NodeList segments = gpx.getElementsByTagNameNS(namespace, "trkseg");
            NodeList points = gpx.getElementsByTagNameNS(namespace, "trkpt");
            NodeList waypoints = gpx.getElementsByTagNameNS(namespace, "wpt");
            if (waypoints.getLength() != 1
                    || !"40.0".equals(((Element) waypoints.item(0)).getAttribute("lat"))
                    || !"-105.0".equals(((Element) waypoints.item(0)).getAttribute("lon"))
                    || !"TrailTracker Photo".equals(((Element) waypoints.item(0))
                            .getElementsByTagNameNS(namespace, "type").item(0).getTextContent())) {
                throw new AssertionError("Photo markers must be saved as GPX waypoints");
            }
            if (segments.getLength() != 2 || points.getLength() != 2) {
                throw new AssertionError("Pause must create a separate GPX segment");
            }
            Element point = (Element) points.item(0);
            if (!"40.0".equals(point.getAttribute("lat"))
                    || !"-105.0".equals(point.getAttribute("lon"))
                    || !"123.5".equals(point.getElementsByTagNameNS(namespace, "ele").item(0).getTextContent())
                    || !"2023-11-14T22:13:20.000Z".equals(
                            point.getElementsByTagNameNS(namespace, "time").item(0).getTextContent())
                    || ((Element) points.item(1)).getElementsByTagNameNS(namespace, "ele").getLength() != 0) {
                throw new AssertionError("GPX must retain coordinates, altitude and UTC fix time");
            }
            File notDirectory = new File(documents, "not-a-directory");
            if (!notDirectory.createNewFile()) throw new AssertionError("Could not create test file");
            expectFailure(() -> GpxTrailStore.save(notDirectory, snapshot, stoppedAt));
            if (!notDirectory.delete()) throw new AssertionError("Could not clean test file");
            GpxTrailStore.delete(saved);
            expectFailure(() -> GpxTrailStore.delete(saved));
            tracker.acknowledgeSavedTrail(savedChoice);
            if (nextChoice != tracker.savedTrailAwaitingChoice()
                    || GpxTrailStore.list(documents).length != 1) {
                throw new AssertionError("Deleting one trail must preserve the next saved choice");
            }
            tracker.acknowledgeSavedTrail(nextChoice);
            if (tracker.savedTrailAwaitingChoice() != null || !savedAgain.isFile()) {
                throw new AssertionError("Keeping a trail must leave its file in place");
            }
        } finally {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (!file.delete()) throw new AssertionError("Could not clean test file");
                }
                if (!directory.delete()) throw new AssertionError("Could not clean test directory");
            }
            if (!documents.delete()) throw new AssertionError("Could not clean test documents");
        }
        testTransportColors();
        System.out.println("GPX trail store tests passed");
    }

    private static void testTransportColors() throws Exception {
        TripTracker tracker = TripTracker.getInstance();
        tracker.setTransportMode(TripTracker.TransportMode.WALK);
        tracker.start();
        addFix(tracker, 40.0);
        tracker.setTransportMode(TripTracker.TransportMode.BIKE);
        addFix(tracker, 40.00001);
        tracker.pause();
        tracker.resume();
        tracker.setTransportMode(TripTracker.TransportMode.BOAT);
        addFix(tracker, 40.00002);
        addFix(tracker, 40.00003);
        tracker.pause();
        tracker.resume();
        addFix(tracker, 40.00004);
        tracker.stop();
        StringWriter output = new StringWriter();
        try (BufferedWriter writer = new BufferedWriter(output)) {
            GpxTrailStore.write(writer, tracker.copyTrail());
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document gpx = factory.newDocumentBuilder().parse(new InputSource(new StringReader(output.toString())));
        String ns = "http://www.topografix.com/GPX/1/1";
        String colorNs = "urn:trailtracker:gpx:color:1";
        NodeList tracks = gpx.getElementsByTagNameNS(ns, "trk");
        if (tracks.getLength() != 3) throw new AssertionError("Each mode change must create a track");
        String[] colors = {"#5AC8FA", "#35C759", "#FF6B9E"};
        for (int i = 0; i < tracks.getLength(); ++i) {
            Element track = (Element) tracks.item(i);
            NodeList displayColors = track.getElementsByTagNameNS(colorNs, "DisplayColor");
            if (displayColors.getLength() != 1
                    || !colors[i].equals(displayColors.item(0).getTextContent())) {
                throw new AssertionError("Each track must retain its app color");
            }
        }
        Element bike = (Element) tracks.item(1);
        NodeList bikePoints = bike.getElementsByTagNameNS(ns, "trkpt");
        Element boat = (Element) tracks.item(2);
        NodeList boatPoints = boat.getElementsByTagNameNS(ns, "trkpt");
        if (bikePoints.getLength() != 2
                || !"40.0".equals(((Element) bikePoints.item(0)).getAttribute("lat"))
                || boatPoints.getLength() != 3
                || !"40.00002".equals(((Element) boatPoints.item(0)).getAttribute("lat"))
                || boat.getElementsByTagNameNS(ns, "trkseg").getLength() != 2) {
            throw new AssertionError("Mode changes must connect unless paused; pauses must break tracks");
        }
    }

    private static void addFix(TripTracker tracker, double latitude) {
        Location location = new Location("test");
        location.setLatitude(latitude);
        tracker.onFix(location);
    }

    private interface IoAction { void run() throws IOException; }

    private static void expectFailure(IoAction action) throws IOException {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Expected an IOException");
    }
}
