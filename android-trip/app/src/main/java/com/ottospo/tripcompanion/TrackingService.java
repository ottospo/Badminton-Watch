package com.ottospo.tripcompanion;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;

/**
 * Foreground service that owns the GPS while a trip is being tracked, so
 * fixes keep arriving with the screen off (phone in a pocket while hiking).
 * The activity drives start, stop, pause and resume actions and reads the
 * shared TripTracker singleton directly; no binding is needed.
 *
 * The GPS provider works entirely offline — no network or map data — which
 * is the whole point for territory with no mapping.
 */
public class TrackingService extends Service {

    public static final String ACTION_START = "com.ottospo.tripcompanion.START";
    public static final String ACTION_STOP = "com.ottospo.tripcompanion.STOP";
    public static final String ACTION_PAUSE = "com.ottospo.tripcompanion.PAUSE";
    public static final String ACTION_RESUME = "com.ottospo.tripcompanion.RESUME";

    private static final String TAG = "TrackingService";
    private static final String CHANNEL_ID = "trip_tracking";
    private static final int NOTIFICATION_ID = 1;
    private static final long FIX_INTERVAL_MS = 1000;   // 1 Hz, like the watch

    private LocationManager locationManager;
    private boolean listening = false;
    private boolean gnssListening = false;
    private int pendingSaves = 0;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final GnssStatus.Callback gnssCallback = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(GnssStatus status) {
            TripTracker tracker = TripTracker.getInstance();
            if (!gnssListening || !tracker.isTracking() || tracker.isPaused()) return;
            String[] names = getResources().getStringArray(R.array.gnss_constellations);
            GnssSummary summary = new GnssSummary(names.length);
            for (int i = 0; i < status.getSatelliteCount(); ++i) {
                summary.add(status.getConstellationType(i), status.usedInFix(i));
            }
            tracker.setGnssSummary(summary.format(names, getString(R.string.gnss_none_used)));
        }

        @Override
        public void onStopped() {
            TripTracker.getInstance().setGnssSummary(null);
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            TripTracker.getInstance().onFix(location);
            if (!TripTracker.getInstance().isTracking()) {
                stopTracking();
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
            TripTracker.getInstance().setGnssSummary(null);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopAndSave();
            return START_NOT_STICKY;
        }
        TripTracker tracker = TripTracker.getInstance();
        if (ACTION_START.equals(action) && !tracker.isTracking()) {
            tracker.start();
        } else if (ACTION_PAUSE.equals(action) && tracker.isTracking()) {
            tracker.pause();
            removeUpdates();
        } else if (ACTION_RESUME.equals(action) && tracker.isTracking()) {
            tracker.resume();
        }
        if (!tracker.isTracking()) {
            finishIfIdle();
            return START_NOT_STICKY;
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (SecurityException | IllegalStateException e) {
            Log.e(TAG, "Unable to start foreground location service", e);
            tracker.fail("Trip stopped: GPS service unavailable");
            finishIfIdle();
            return START_NOT_STICKY;
        }
        if (!tracker.isPaused()) {
            requestUpdates();
        }
        return START_STICKY;
    }

    @SuppressLint("MissingPermission")  // the activity only starts us after the grant
    private void requestUpdates() {
        if (listening) {
            return;
        }
        try {
            if (locationManager == null) {
                throw new IllegalStateException("Location service unavailable");
            }
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    FIX_INTERVAL_MS, 0f, locationListener);
            listening = true;
            Log.d(TAG, "GPS updates requested");
        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            Log.e(TAG, "Unable to request GPS updates", e);
            TripTracker.getInstance().fail("Trip stopped: GPS unavailable");
            stopTracking();
            return;
        }
        startGnssMonitoring();
    }

    @SuppressLint("MissingPermission")
    private void startGnssMonitoring() {
        try {
            gnssListening = locationManager.registerGnssStatusCallback(gnssCallback);
            if (!gnssListening) {
                Log.w(TAG, "GNSS status callback registration failed");
                TripTracker.getInstance().setGnssSummary(getString(R.string.gnss_unavailable));
            }
        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            Log.e(TAG, "Unable to monitor GNSS satellites", e);
            TripTracker.getInstance().setGnssSummary(getString(R.string.gnss_unavailable));
        }
    }

    private void removeUpdates() {
        if (gnssListening) {
            gnssListening = false;
            locationManager.unregisterGnssStatusCallback(gnssCallback);
        }
        TripTracker.getInstance().setGnssSummary(null);
        if (listening) {
            locationManager.removeUpdates(locationListener);
            listening = false;
        }
    }

    private void stopTracking() {
        removeUpdates();
        TripTracker.getInstance().stop();
        finishIfIdle();
        Log.d(TAG, "Trip tracking stopped");
    }

    private void stopAndSave() {
        TripTracker tracker = TripTracker.getInstance();
        if (!tracker.isTracking()) {
            finishIfIdle();
            return;
        }
        removeUpdates();
        tracker.stop();
        long stoppedAt = System.currentTimeMillis();
        TripTracker.TrailSnapshot trail = tracker.copyTrail();
        if (trail.points.length == 0) {
            Toast.makeText(this, R.string.trail_not_saved_empty, Toast.LENGTH_LONG).show();
            finishIfIdle();
            return;
        }
        ++pendingSaves;
        NotificationManager notifications =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notifications.notify(NOTIFICATION_ID, buildNotification());
        new Thread(() -> {
            SavedTrail saved = null;
            IOException failure = null;
            try {
                saved = FolderTrailStore.save(this, trail, stoppedAt);
            } catch (IOException | SecurityException | IllegalArgumentException | IllegalStateException e) {
                Log.e(TAG, "Unable to save GPX trail", e);
                failure = new IOException("Unable to save GPX trail", e);
            }
            final SavedTrail result = saved;
            final IOException error = failure;
            mainHandler.post(() -> {
                --pendingSaves;
                if (error == null) {
                    tracker.addSavedTrailForChoice(result);
                } else {
                    Toast.makeText(this, R.string.trail_save_failed, Toast.LENGTH_LONG).show();
                }
                finishIfIdle();
            });
        }, "save-gpx").start();
    }

    private void finishIfIdle() {
        if (!TripTracker.getInstance().isTracking() && pendingSaves == 0) {
            stopForeground(true);
            stopSelf();
        }
    }

    private Notification buildNotification() {
        // Notification channels are mandatory once targetSdk >= 26.
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Trip tracking", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
        Intent launch = new Intent(this, MainActivity.class);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, launch, piFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(!TripTracker.getInstance().isTracking()
                        ? getString(R.string.trail_saving)
                        : TripTracker.getInstance().isPaused() ? "Trip paused" : "Tracking your trip")
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .build();
    }

    @Override
    public void onDestroy() {
        removeUpdates();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
