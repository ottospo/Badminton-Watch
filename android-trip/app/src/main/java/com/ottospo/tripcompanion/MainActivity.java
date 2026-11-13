package com.ottospo.tripcompanion;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import java.util.Locale;

/**
 * TrailTracker — Android port of the T-Watch-S3 firmware's GPS trip
 * companion (src/trip_companion.cpp), compass face (src/compass.cpp) and
 * breadcrumb trail (src/breadcrumb.cpp).
 *
 * START locks the current position as "home"; the readouts then show the
 * straight-line distance and bearing back to it, the course over ground,
 * altitude, total path length, and elapsed trip time, alongside a compass
 * dial and a pannable, pinch-zoomable breadcrumb trail.
 * The trail initially fits around the start; double-tap to fit it again.
 * Everything runs
 * on the phone's GPS alone — no network, no map tiles — for hiking where
 * no mapping is available. Tracking runs in a foreground service so it
 * continues with the screen off.
 */
public class MainActivity extends Activity {

    private static final int REQ_LOCATION = 1;
    private static final int REQ_NOTIFICATION = 2;
    private static final long UI_UPDATE_MS = 1000;  // 1 Hz readout refresh
    private static final String PRIVACY_URL = "https://ottospo.github.io/trip-companion-privacy/";

    private final TripTracker tracker = TripTracker.getInstance();

    private TextView statusText;
    private TextView distanceText;
    private TextView bearingText;
    private TextView courseText;
    private TextView extraText;
    private TextView elapsedText;
    private TextView trailCaption;
    private Button startStopButton;
    private Button pauseButton;
    private CompassView compassView;
    private TrailView trailView;
    private BatteryView batteryView;
    private LocationManager locationManager;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable uiUpdater = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            handler.postDelayed(this, UI_UPDATE_MS);
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                batteryView.setPercent(level * 100 / scale);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (Build.VERSION.SDK_INT >= 35) {
            View content = findViewById(R.id.trip_root);
            int left = content.getPaddingLeft();
            int top = content.getPaddingTop();
            int right = content.getPaddingRight();
            int bottom = content.getPaddingBottom();
            content.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                view.setPadding(left + bars.left, top + bars.top,
                        right + bars.right, bottom + bars.bottom);
                return insets;
            });
        }

        statusText = findViewById(R.id.status_text);
        distanceText = findViewById(R.id.distance_text);
        bearingText = findViewById(R.id.bearing_text);
        courseText = findViewById(R.id.course_text);
        extraText = findViewById(R.id.extra_text);
        elapsedText = findViewById(R.id.elapsed_text);
        trailCaption = findViewById(R.id.trail_caption);
        startStopButton = findViewById(R.id.start_stop_button);
        pauseButton = findViewById(R.id.pause_button);
        compassView = findViewById(R.id.compass_view);
        trailView = findViewById(R.id.trail_view);
        batteryView = findViewById(R.id.battery_view);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        findViewById(R.id.privacy_link).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)));
            } catch (ActivityNotFoundException e) {
                android.util.Log.e("TripCompanion", "Unable to open privacy policy", e);
                new AlertDialog.Builder(this)
                        .setMessage(R.string.privacy_no_browser)
                        .setPositiveButton("OK", null)
                        .show();
            }
        });

        startStopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tracker.isTracking()) {
                    stopTracking();
                } else {
                    requestStart();
                }
            }
        });
        pauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String action = tracker.isPaused()
                        ? TrackingService.ACTION_RESUME : TrackingService.ACTION_PAUSE;
                startService(new Intent(MainActivity.this, TrackingService.class).setAction(action));
            }
        });

        refreshUi();
    }

    private void requestStart() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQ_LOCATION);
            return;
        }
        requestNotificationThenStart();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            if (Build.VERSION.SDK_INT < 23
                    || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED) {
                requestNotificationThenStart();
            } else {
                new AlertDialog.Builder(this)
                        .setMessage("Precise location is required to record your GPS trail. "
                                + "Enable precise location for TrailTracker in app settings.")
                        .setPositiveButton("OK", null)
                        .show();
            }
        } else if (requestCode == REQ_NOTIFICATION) {
            startTracking();
        }
    }

    private void requestNotificationThenStart() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATION);
        } else {
            startTracking();
        }
    }

    private void startTracking() {
        if (locationManager == null
                || !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            new AlertDialog.Builder(this)
                    .setMessage("Enable location services before starting a trip.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        try {
            Intent intent = new Intent(this, TrackingService.class)
                    .setAction(TrackingService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            startStopButton.setText("STOP");    // refreshUi() keeps it truthful
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (IllegalStateException | SecurityException e) {
            android.util.Log.e("TripCompanion", "Unable to start trip service", e);
            new AlertDialog.Builder(this)
                    .setMessage("Unable to start GPS tracking. Check location permissions and try again.")
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private void stopTracking() {
        startService(new Intent(this, TrackingService.class).setAction(TrackingService.ACTION_STOP));
        startStopButton.setText("START");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void refreshUi() {
        tracker.tick();
        long now = SystemClock.elapsedRealtime();
        boolean tracking = tracker.isTracking();
        boolean paused = tracker.isPaused();
        boolean hasStart = tracker.hasStart();

        // Keep the button and screen-on flag in sync with the real state
        // (covers rotation and the async service start).
        startStopButton.setText(tracking ? "STOP" : "START");
        pauseButton.setEnabled(tracking);
        pauseButton.setText(paused ? "UNPAUSE" : "PAUSE");
        if (tracking && !paused) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // Status line: the watch shows satellite count; Android exposes the
        // accuracy radius instead.
        boolean providerOn = locationManager != null
                && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        String acc = tracker.hasAccuracy()
                ? String.format(Locale.US, "±%dm", Math.round(tracker.lastAccuracyM()))
                : "--";
        if (tracker.error() != null) {
            statusText.setText(tracker.error());
        } else if (paused) {
            statusText.setText("Trip paused");
        } else if (!providerOn) {
            statusText.setText("Location is OFF — enable GPS");
        } else if (!tracking) {
            statusText.setText("Ready");
        } else if (hasStart && tracker.isAwaitingFreshFix()) {
            statusText.setText("Reacquiring GPS...");
        } else if (!hasStart) {
            statusText.setText(String.format(Locale.US, "Acquiring GPS... %ds  Acc: %s",
                    (now - tracker.trackingStartedAt()) / 1000, acc));
        } else {
            statusText.setText(String.format(Locale.US, "Fix: %ds ago  Acc: %s",
                    (now - tracker.lastFixAt()) / 1000, acc));
        }

        // Distance back to start + bearing (port of the firmware's
        // update_labels()).
        if (!hasStart) {
            bearingText.setText("--");
            if (!tracking) {
                distanceText.setText("--");
            } else if (tracker.rawFixCount() >= TripTracker.START_SETTLE_FIXES
                    && tracker.hasAccuracy()
                    && tracker.lastAccuracyM() > TripTracker.START_MAX_ACCURACY_M) {
                distanceText.setText(String.format(Locale.US, "Need ±%dm fix (%s)",
                        (int) TripTracker.START_MAX_ACCURACY_M, acc));
            } else if (tracker.rawFixCount() > 0) {
                distanceText.setText(String.format(Locale.US, "Settling %d/%d",
                        Math.min(tracker.rawFixCount(), TripTracker.START_SETTLE_FIXES),
                        TripTracker.START_SETTLE_FIXES));
            } else {
                distanceText.setText("Wait for fix");
            }
        } else {
            distanceText.setText(formatDistance(tracker.distanceM()));
            bearingText.setText(String.format(Locale.US, "%d deg %s",
                    Math.round(tracker.bearingDeg()), Geo.cardinal(tracker.bearingDeg())));
        }

        if (tracker.courseValid()) {
            courseText.setText(String.format(Locale.US, "Course: %d deg %s",
                    Math.round(tracker.courseDeg()), Geo.cardinal(tracker.courseDeg())));
        } else {
            courseText.setText("Course: --");
        }

        String alt = tracker.altitudeValid()
                ? String.format(Locale.US, "%d m", Math.round(tracker.altitudeM()))
                : "--";
        double path = tracker.pathLengthM();
        if (path < 1000.0) {
            extraText.setText(String.format(Locale.US, "Alt: %s  Path: %d m", alt,
                    Math.round(path)));
        } else {
            extraText.setText(String.format(Locale.US, "Alt: %s  Path: %.1f km", alt,
                    path / 1000.0));
        }

        long elapsedSeconds = tracker.elapsedMs() / 1000;
        elapsedText.setText(String.format(Locale.US, "Elapsed: %02dh:%02dm:%02dsec",
                elapsedSeconds / 3600, (elapsedSeconds % 3600) / 60, elapsedSeconds % 60));

        compassView.setState(tracker.bearingDeg(), tracker.courseDeg(), hasStart,
                tracker.courseValid());
        trailView.invalidate();
        updateCaption();
    }

    private static String formatDistance(double distM) {
        if (distM < 1000.0) {
            return String.format(Locale.US, "%d m", Math.round(distM));
        }
        return String.format(Locale.US, "%.2f km", distM / 1000.0);
    }

    private void updateCaption() {
        trailCaption.setText(String.format(Locale.US, "Trail (%d) · 2x tap: fit",
                tracker.trailCount()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        handler.post(uiUpdater);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(batteryReceiver);
        handler.removeCallbacks(uiUpdater);
    }
}
