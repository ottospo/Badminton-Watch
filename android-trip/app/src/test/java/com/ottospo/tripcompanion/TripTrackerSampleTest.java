package com.ottospo.tripcompanion;

import android.location.Location;

/** Run with javac/java using src/test/stubs, Geo.java, and TripTracker.java. */
public final class TripTrackerSampleTest {
    public static void main(String[] args) {
        TripTracker tracker = TripTracker.getInstance();
        if (tracker.transportMode() != TripTracker.TransportMode.WALK) {
            throw new AssertionError("Walking must be the initial mode");
        }
        if (!tracker.isSample() || tracker.isTracking() || !tracker.hasStart()
                || tracker.trailCount() < 2 || tracker.distanceM() <= 0
                || tracker.pathLengthM() <= tracker.distanceM()) {
            throw new AssertionError("Expected a visible, inactive sample trip");
        }
        TripTracker.TrailSnapshot sampleSnapshot = tracker.copyTrail();
        if (sampleSnapshot.photoMarkers.length != 0
                || tracker.copyTrail() != sampleSnapshot) {
            throw new AssertionError("Sample trail must not be used for photo locations");
        }
        TripTracker.TrailPoint[] sample = sampleSnapshot.points;
        int turn = 0;
        while (turn < sample.length && sample[turn].transportMode == TripTracker.TransportMode.WALK) {
            ++turn;
        }
        if (turn < 2 || turn == sample.length) {
            throw new AssertionError("Sample must begin with a walking leg and include a return leg");
        }
        for (int i = turn; i < sample.length; ++i) {
            if (sample[i].transportMode != TripTracker.TransportMode.MOTORBIKE || sample[i].breakBefore) {
                throw new AssertionError("Sample return leg must be a continuous motorbike trail");
            }
        }
        double outwardDistance = Geo.distanceMeters(sample[0].lat, sample[0].lng,
                sample[turn - 1].lat, sample[turn - 1].lng);
        if (tracker.distanceM() < outwardDistance * 0.4
                || tracker.distanceM() > outwardDistance * 0.6) {
            throw new AssertionError("Sample return leg must end roughly halfway back");
        }
        tracker.start();
        if (tracker.isSample() || !tracker.isTracking() || tracker.hasStart()
                || tracker.trailCount() != 0 || tracker.pathLengthM() != 0
                || tracker.copyTrail() == sampleSnapshot) {
            throw new AssertionError("Starting a real trip must clear the sample");
        }
        if (tracker.markPhoto() != TripTracker.PhotoMarkerResult.NO_LOCATION) {
            throw new AssertionError("A photo before the first fix cannot be marked");
        }
        tracker.pause();
        tracker.pause();
        if (!tracker.isPaused() || tracker.copyTrail().photoMarkers.length != 0) {
            throw new AssertionError("Pausing before the first fix must not create a marker");
        }
        tracker.onFix(new Location("test"));
        if (tracker.trailCount() != 0) throw new AssertionError("Paused fixes must be ignored");
        tracker.resume();
        for (int i = 0; i < TripTracker.START_SETTLE_FIXES; ++i) {
            tracker.onFix(new Location("test"));
        }
        if (!tracker.hasStart() || tracker.trailCount() != TripTracker.START_SETTLE_FIXES) {
            throw new AssertionError("Real fixes must replace the sample");
        }
        tracker.setTransportMode(TripTracker.TransportMode.BIKE);
        Location pauseLocation = new Location("test");
        pauseLocation.setLatitude(40.00001);
        pauseLocation.setLongitude(-105.00001);
        tracker.onFix(pauseLocation);
        TripTracker.TrailPoint[] points = tracker.copyTrail().points;
        if (points[points.length - 2].transportMode != TripTracker.TransportMode.WALK
                || points[points.length - 1].transportMode != TripTracker.TransportMode.BIKE) {
            throw new AssertionError("Mode changes must not recolor earlier trail points");
        }
        TripTracker.TrailSnapshot beforePause = tracker.copyTrail();
        tracker.pause();
        TripTracker.TrailSnapshot pausedSnapshot = tracker.copyTrail();
        TripTracker.TrailPoint latest = points[points.length - 1];
        if (pausedSnapshot != beforePause || pausedSnapshot.photoMarkers.length != 0) {
            throw new AssertionError("Pause alone must not create a photo marker");
        }
        if (tracker.markPhoto() != TripTracker.PhotoMarkerResult.MARKED) {
            throw new AssertionError("Photo must mark the last accepted position even while paused");
        }
        TripTracker.TrailSnapshot photoSnapshot = tracker.copyTrail();
        TripTracker.PhotoMarker photoMarker = photoSnapshot.photoMarkers[0];
        if (photoSnapshot == pausedSnapshot || photoMarker.tripId != photoSnapshot.tripId
                || photoMarker.lat != latest.lat || photoMarker.lng != latest.lng
                || photoMarker.lat != 40.00001 || photoMarker.lng != -105.00001) {
            throw new AssertionError("Photo marker must be visible at the latest point");
        }
        tracker.setTransportMode(TripTracker.TransportMode.BOAT);
        tracker.onFix(new Location("test"));
        if (tracker.trailCount() != TripTracker.START_SETTLE_FIXES + 1) {
            throw new AssertionError("Paused trips must not record fixes");
        }
        tracker.resume();
        tracker.pause();
        if (tracker.copyTrail().photoMarkers.length != 1) {
            throw new AssertionError("Repeated pauses must not create markers");
        }
        tracker.resume();
        tracker.onFix(new Location("test"));
        points = tracker.copyTrail().points;
        if (!points[points.length - 1].breakBefore
                || points[points.length - 1].transportMode != TripTracker.TransportMode.BOAT) {
            throw new AssertionError("Resumed trail must break and use the selected mode");
        }
        if (tracker.markPhoto() != TripTracker.PhotoMarkerResult.MARKED
                || tracker.copyTrail().photoMarkers.length != 2) {
            throw new AssertionError("A later photo must retain another marker");
        }
        tracker.stop();
        if (tracker.markPhoto() != TripTracker.PhotoMarkerResult.IGNORED) {
            throw new AssertionError("A stopped trip must not accept photo markers");
        }
        tracker.onFix(new Location("test"));
        if (tracker.isSample() || tracker.isTracking() || !tracker.hasStart()
                || tracker.trailCount() != TripTracker.START_SETTLE_FIXES + 2) {
            throw new AssertionError("Stopping must preserve the real trail and reject further fixes");
        }
        tracker.start();
        if (tracker.copyTrail().photoMarkers.length != 0) {
            throw new AssertionError("A new trip must clear prior photo markers");
        }
        System.out.println("TripTracker sample tests passed");
    }
}
