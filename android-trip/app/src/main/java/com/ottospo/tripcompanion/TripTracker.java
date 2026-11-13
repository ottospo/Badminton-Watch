package com.ottospo.tripcompanion;

import android.location.Location;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayList;

/**
 * GPS trip tracker: a port of the T-Watch-S3 firmware's trip companion
 * (src/trip_companion.cpp) plus its breadcrumb trail recording
 * (src/breadcrumb.cpp).
 *
 * START captures the current position as the trip's starting point; from
 * then on the tracker reports the straight-line distance and bearing back to
 * it, the course over ground, the altitude, the total path length, the
 * elapsed trip time, and the raw-fix breadcrumb trail.
 *
 * Adaptations from the watch:
 *  - The watch gates the start-point lock on satellite count and HDOP;
 *    Android's Location API exposes neither, so the lock requires the
 *    reported accuracy radius to be within START_MAX_ACCURACY_M instead.
 *  - The watch's genuine-fix check (bogus all-zero NMEA position) has no
 *    Android equivalent: LocationManager only delivers real fixes.
 *  - The trail records while the trip is tracking (one START/STOP) instead
 *    of having its own toggle; the trail survives STOP for display, exactly
 *    like the watch.
 *
 * Every accepted coordinate remains in memory until a new trip or process
 * restart. Pausing preserves the trip, excludes rest time, and breaks the
 * trail at the first accepted fix after resuming.
 *
 * A fix whose step from the last accepted fix exceeds what the trip's
 * average speed allows for the elapsed time is a bogus spike and is
 * discarded completely, so one bad fix cannot distort the start point, the
 * distance, the path length, or the trail (firmware parity).
 *
 * Everything runs on the phone's GPS alone: no network, no map tiles.
 */
public class TripTracker {

    private static final String TAG = "TripTracker";

    // Position smoothing (firmware parity): a stationary GPS reports a
    // position that wanders by tens of meters. While settling, average every
    // fix; after the start locks, only move the smoothed position when a fix
    // lands beyond the deadband (real movement).
    private static final float SMOOTH_MOVING = 0.4f;    // smoothing weight while moving
    private static final double MOVE_DIST_M = 4.0;      // min displacement to count as moving
    public static final int START_SETTLE_FIXES = 10;    // fixes averaged before START locks
    // Replaces the watch's START_MIN_SATS / START_MAX_HDOP trust check.
    public static final float START_MAX_ACCURACY_M = 15.0f;
    // GPS course over ground is only meaningful while moving.
    private static final float COURSE_MIN_SPEED_MPS = 0.5f;
    private static final double MIN_PATH_STEP_M = 1.0;  // ignore sub-meter steps
    // Plausibility gate (firmware parity): the differential distance between
    // a new fix and the last accepted fix must never exceed what the trip's
    // average speed allows for the elapsed time; such a fix is a bogus spike
    // and is discarded completely. The floor covers moving off after a long
    // stop, when the average has decayed toward zero. If the "spike" position
    // persists it is real (re-acquisition after a long signal gap), so after
    // MAX_DISCARD_STREAK consecutive discards the fix is accepted.
    private static final double STEP_FLOOR_M = 20.0;    // always allow steps up to this
    private static final double SPEED_MARGIN = 3.0;     // allow bursts up to 3x avg speed
    private static final int MAX_DISCARD_STREAK = 10;   // consecutive discards before re-lock

    private static final TripTracker INSTANCE = new TripTracker();

    public static TripTracker getInstance() {
        return INSTANCE;
    }

    private boolean tracking = false;
    private boolean paused = false;
    private boolean awaitingFreshFix = false;
    private boolean rebaseAfterPause = false;
    private long pausedAt = 0;
    private long freshFixAfterNanos = 0;
    private String error = null;
    private boolean hasStartPoint = false;
    private double startLat = 0.0;
    private double startLng = 0.0;
    private double lastDistanceM = 0.0;
    private double lastBearingDeg = 0.0;
    private double lastCourseDeg = 0.0;
    private boolean courseValid = false;
    private double pathLengthM = 0.0;
    private double altitudeM = 0.0;
    private boolean altitudeValid = false;
    private double prevSmoothLat = 0.0;
    private double prevSmoothLng = 0.0;
    private boolean hasPrevSmooth = false;
    private long startedAt = 0;             // elapsedRealtime() when the start locked
    private long elapsedMs = 0;
    private long trackingStartedAt = 0;
    private long lastFixAt = 0;
    private double smoothLat = 0.0;
    private double smoothLng = 0.0;
    private int smoothCount = 0;
    private int rawFixCount = 0;            // every accepted fix, moving or not
    private double lastAcceptedLat = 0.0;   // last fix that passed the plausibility gate
    private double lastAcceptedLng = 0.0;
    private long lastAcceptedAt = 0;
    private boolean hasAccepted = false;
    private int discardStreak = 0;          // consecutive fixes rejected by the gate
    private float lastAccuracyM = 0.0f;
    private boolean hasAccuracy = false;
    private long tripId = 0;

    public static final class TrailPoint {
        public final double lat;
        public final double lng;
        public final boolean breakBefore;

        private TrailPoint(double lat, double lng, boolean breakBefore) {
            this.lat = lat;
            this.lng = lng;
            this.breakBefore = breakBefore;
        }
    }

    private final ArrayList<TrailPoint> trail = new ArrayList<>();

    private TripTracker() {
    }

    /** Start a new trip: resets all state and begins recording fixes. */
    public synchronized void start() {
        ++tripId;
        tracking = true;
        paused = false;
        awaitingFreshFix = true;
        rebaseAfterPause = false;
        freshFixAfterNanos = SystemClock.elapsedRealtimeNanos();
        error = null;
        hasStartPoint = false;
        courseValid = false;
        elapsedMs = 0;
        pathLengthM = 0.0;
        altitudeValid = false;
        hasPrevSmooth = false;
        smoothLat = 0.0;
        smoothLng = 0.0;
        smoothCount = 0;
        rawFixCount = 0;
        hasAccepted = false;
        discardStreak = 0;
        hasAccuracy = false;
        trackingStartedAt = SystemClock.elapsedRealtime();
        trail.clear();
    }

    /** Stop tracking; the last readouts and the trail stay available. */
    public synchronized void stop() {
        tick();
        tracking = false;
        paused = false;
        awaitingFreshFix = false;
    }

    public synchronized void pause() {
        if (!tracking || paused) return;
        pausedAt = SystemClock.elapsedRealtime();
        if (hasStartPoint) elapsedMs = pausedAt - startedAt;
        paused = true;
        courseValid = false;
    }

    public synchronized void resume() {
        if (!tracking || !paused) return;
        long now = SystemClock.elapsedRealtime();
        long rest = now - pausedAt;
        trackingStartedAt += rest;
        if (hasStartPoint) startedAt += rest;
        if (hasAccepted) lastAcceptedAt += rest;
        hasPrevSmooth = false;
        rebaseAfterPause = hasAccepted;
        freshFixAfterNanos = SystemClock.elapsedRealtimeNanos();
        awaitingFreshFix = true;
        paused = false;
    }

    public synchronized void fail(String message) {
        stop();
        error = message;
    }

    /** Feed a fresh GPS fix (called by TrackingService). */
    public synchronized void onFix(Location loc) {
        if (!tracking || paused || loc.getElapsedRealtimeNanos() < freshFixAfterNanos) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        double lat = loc.getLatitude();
        double lng = loc.getLongitude();
        lastFixAt = now;                    // the GPS is alive, even if the fix is bogus
        awaitingFreshFix = false;

        // Plausibility gate: a differential distance greater than the average
        // speed allows for the elapsed time can never be real movement, so
        // the fix is discarded completely — it must not touch the smoothed
        // position, the start point, the path length, or the trail.
        if (hasAccepted) {
            double step = Geo.distanceMeters(lat, lng, lastAcceptedLat, lastAcceptedLng);
            double dt = (now - lastAcceptedAt) / 1000.0;
            double avgSpeed = hasStartPoint && now > startedAt
                    ? pathLengthM / ((now - startedAt) / 1000.0) : 0.0;
            double allowed = Math.max(avgSpeed * dt * SPEED_MARGIN, STEP_FLOOR_M);
            if (step > allowed) {
                if (discardStreak < MAX_DISCARD_STREAK) {
                    ++discardStreak;
                    Log.d(TAG, "Fix discarded: " + Math.round(step) + " m in "
                            + dt + " s exceeds allowed " + Math.round(allowed) + " m");
                    return;                 // keep every result unchanged
                }
                Log.d(TAG, "Sustained offset: accepting fix as re-acquisition");
            }
        }
        try {
            trail.add(new TrailPoint(lat, lng, rebaseAfterPause));
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "Cannot retain GPS coordinate; stopping trip", e);
            fail("Trip stopped: memory full");
            return;
        }
        discardStreak = 0;
        hasAccepted = true;
        lastAcceptedLat = lat;
        lastAcceptedLng = lng;
        lastAcceptedAt = now;
        ++rawFixCount;
        hasAccuracy = loc.hasAccuracy();
        lastAccuracyM = hasAccuracy ? loc.getAccuracy() : 0.0f;

        if (rebaseAfterPause && hasStartPoint) {
            smoothLat = lat;
            smoothLng = lng;
        } else if (!hasStartPoint) {
            if (smoothCount == 0) {
                smoothLat = lat;
                smoothLng = lng;
            } else {
                double w = 1.0 / (smoothCount + 1);     // true running average
                smoothLat += (lat - smoothLat) * w;
                smoothLng += (lng - smoothLng) * w;
            }
            ++smoothCount;
        } else {
            double moved = Geo.distanceMeters(lat, lng, smoothLat, smoothLng);
            if (moved >= MOVE_DIST_M) {
                smoothLat += (lat - smoothLat) * SMOOTH_MOVING;
                smoothLng += (lng - smoothLng) * SMOOTH_MOVING;
            }
            // else: within the deadband — hold position, ignore noise.
        }

        // Lock the start point only once enough fixes have arrived AND the
        // fix is trustworthy (accuracy radius within the ceiling), so an
        // early inaccurate fix on a weak signal doesn't become "home".
        if (!hasStartPoint && rawFixCount >= START_SETTLE_FIXES
                && hasAccuracy && lastAccuracyM <= START_MAX_ACCURACY_M) {
            startLat = smoothLat;
            startLng = smoothLng;
            hasStartPoint = true;
            startedAt = now;
            elapsedMs = 0;
        }

        if (hasStartPoint) {
            lastDistanceM = Geo.distanceMeters(smoothLat, smoothLng, startLat, startLng);
            lastBearingDeg = Geo.bearingDeg(smoothLat, smoothLng, startLat, startLng);
            // Accumulate path length from the smoothed track; ignore sub-meter
            // steps so stationary jitter does not inflate the total.
            if (hasPrevSmooth) {
                double step = Geo.distanceMeters(smoothLat, smoothLng,
                        prevSmoothLat, prevSmoothLng);
                if (step >= MIN_PATH_STEP_M) {
                    pathLengthM += step;
                    prevSmoothLat = smoothLat;
                    prevSmoothLng = smoothLng;
                }
            } else {
                prevSmoothLat = smoothLat;
                prevSmoothLng = smoothLng;
                hasPrevSmooth = true;
            }
        }

        if (loc.hasAltitude()) {
            altitudeM = loc.getAltitude();
            altitudeValid = true;
        }
        if (loc.hasBearing() && loc.hasSpeed() && loc.getSpeed() >= COURSE_MIN_SPEED_MPS) {
            lastCourseDeg = loc.getBearing();
            courseValid = true;
        } else {
            courseValid = false;    // standing still: course is noise
        }

        rebaseAfterPause = false;
    }

    /** Advance the elapsed-time readout; call from the UI refresh timer. */
    public synchronized void tick() {
        if (tracking && !paused && hasStartPoint) {
            elapsedMs = SystemClock.elapsedRealtime() - startedAt;
        }
    }

    public synchronized boolean isTracking() {
        return tracking;
    }

    public synchronized boolean isPaused() { return paused; }
    public synchronized boolean isAwaitingFreshFix() { return awaitingFreshFix; }
    public synchronized String error() { return error; }

    public synchronized boolean hasStart() {
        return hasStartPoint;
    }

    public synchronized double distanceM() {
        return lastDistanceM;
    }

    public synchronized double bearingDeg() {
        return lastBearingDeg;
    }

    public synchronized double courseDeg() {
        return lastCourseDeg;
    }

    public synchronized boolean courseValid() {
        return courseValid;
    }

    public synchronized double pathLengthM() {
        return pathLengthM;
    }

    public synchronized double altitudeM() {
        return altitudeM;
    }

    public synchronized boolean altitudeValid() {
        return altitudeValid;
    }

    public synchronized long elapsedMs() {
        return elapsedMs;
    }

    public synchronized long trackingStartedAt() {
        return trackingStartedAt;
    }

    public synchronized long lastFixAt() {
        return lastFixAt;
    }

    public synchronized int rawFixCount() {
        return rawFixCount;
    }

    public synchronized boolean hasAccuracy() {
        return hasAccuracy;
    }

    public synchronized float lastAccuracyM() {
        return lastAccuracyM;
    }

    public synchronized int trailCount() {
        return trail.size();
    }

    public static final class TrailSnapshot {
        public final long tripId;
        public final TrailPoint[] points;
        public final boolean hasStart;
        public final double startLat;
        public final double startLng;

        private TrailSnapshot(long tripId, TrailPoint[] points, boolean hasStart,
                              double startLat, double startLng) {
            this.tripId = tripId;
            this.points = points;
            this.hasStart = hasStart;
            this.startLat = startLat;
            this.startLng = startLng;
        }
    }

    /** Snapshot the trail and its origin together for the map. */
    public synchronized TrailSnapshot copyTrail() {
        return new TrailSnapshot(tripId, trail.toArray(new TrailPoint[trail.size()]),
                hasStartPoint, startLat, startLng);
    }
}
