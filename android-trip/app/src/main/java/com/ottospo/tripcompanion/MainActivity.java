package com.ottospo.tripcompanion;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ClipData;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.graphics.Insets;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.io.IOException;
import java.util.Date;
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
    private static final int REQ_STORAGE_SHARE = 4;
    private static final int REQ_TRAIL_FOLDER = 5;
    private static final long UI_UPDATE_MS = 1000;  // 1 Hz readout refresh
    private static final String PREF_IMPERIAL_UNITS = "imperial_units";
    private static final String PRIVACY_URL = "https://ottospo.github.io/trip-companion-privacy/";
    private static final String BOOK_URL = "https://www.amazon.com/dp/B0FNCVZQYT";

    private final TripTracker tracker = TripTracker.getInstance();

    private TextView statusText;
    private TextView satelliteText;
    private TextView distanceText;
    private TextView unitsToggleText;
    private View unitsControl;
    private TextView dateMoonText;
    private TextView bearingText;
    private TextView courseText;
    private TextView extraText;
    private TextView elapsedText;
    private TextView trailCaption;
    private Button startStopButton;
    private Button photoButton;
    private Button transportButton;
    private CompassView compassView;
    private TrailView trailView;
    private LocationManager locationManager;
    private boolean imperialUnits;
    private AlertDialog savedTrailDialog;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable uiUpdater = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            showSavedTrailChoice();
            handler.postDelayed(this, UI_UPDATE_MS);
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
        satelliteText = findViewById(R.id.satellite_text);
        distanceText = findViewById(R.id.distance_text);
        unitsToggleText = findViewById(R.id.units_toggle);
        unitsControl = findViewById(R.id.units_control);
        dateMoonText = findViewById(R.id.date_moon);
        bearingText = findViewById(R.id.bearing_text);
        courseText = findViewById(R.id.course_text);
        extraText = findViewById(R.id.extra_text);
        elapsedText = findViewById(R.id.elapsed_text);
        trailCaption = findViewById(R.id.trail_caption);
        startStopButton = findViewById(R.id.start_stop_button);
        photoButton = findViewById(R.id.photo_button);
        transportButton = findViewById(R.id.transport_button);
        compassView = findViewById(R.id.compass_view);
        trailView = findViewById(R.id.trail_view);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        findViewById(R.id.privacy_link).setOnClickListener(
                v -> openExternalUrl(PRIVACY_URL, R.string.privacy_no_browser));
        findViewById(R.id.book_link).setOnClickListener(
                v -> openExternalUrl(BOOK_URL, R.string.book_no_browser));
        refreshUnitsPreference();
        unitsControl.setOnClickListener(v -> {
            imperialUnits = !imperialUnits;
            getPreferences(MODE_PRIVATE).edit()
                    .putBoolean(PREF_IMPERIAL_UNITS, imperialUnits)
                    .apply();
            updateUnitsToggle();
            refreshUi();
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
        photoButton.setOnClickListener(v -> takePhoto());
        transportButton.setOnClickListener(v -> chooseTransportMode());
        findViewById(R.id.share_trail_button).setOnClickListener(v -> shareTrail());

        refreshUi();
    }

    private void takePhoto() {
        TripTracker.PhotoMarkerResult result = tracker.markPhoto();
        if (result == TripTracker.PhotoMarkerResult.IGNORED) return;
        if (result == TripTracker.PhotoMarkerResult.NO_LOCATION) {
            Toast.makeText(this, R.string.photo_without_location, Toast.LENGTH_LONG).show();
        } else if (result == TripTracker.PhotoMarkerResult.MARKER_ERROR) {
            Toast.makeText(this, R.string.photo_marker_failed, Toast.LENGTH_LONG).show();
        }
        trailView.refreshIfChanged();
        try {
            startActivity(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
        } catch (ActivityNotFoundException | SecurityException e) {
            android.util.Log.e("TripCompanion", "Unable to open the camera", e);
            new AlertDialog.Builder(this).setMessage(R.string.photo_camera_failed)
                    .setPositiveButton(android.R.string.ok, null).show();
        }
    }

    private void chooseTransportMode() {
        TripTracker.TransportMode[] modes = TripTracker.TransportMode.values();
        String[] labels = getResources().getStringArray(R.array.transport_modes);
        boolean tracking = tracker.isTracking();
        CharSequence[] choices = new CharSequence[modes.length + (tracking ? 1 : 0)];
        for (int i = 0; i < modes.length; ++i) {
            SpannableString label = enlargeModeSymbol(labels[i]);
            label.setSpan(new ForegroundColorSpan(modes[i].color), 0, label.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            choices[i] = label;
        }
        if (tracking) choices[modes.length] = enlargeModeSymbol(getString(R.string.transport_pause));
        int selected = tracking && tracker.isPaused() ? modes.length : tracker.transportMode().ordinal();
        new AlertDialog.Builder(this)
                .setTitle(R.string.transport_mode_title)
                .setSingleChoiceItems(choices, selected, (dialog, which) -> {
                    if (which == modes.length) {
                        startService(new Intent(this, TrackingService.class)
                                .setAction(TrackingService.ACTION_PAUSE));
                    } else {
                        tracker.setTransportMode(modes[which]);
                        if (tracker.isTracking()) {
                            startService(new Intent(this, TrackingService.class)
                                    .setAction(TrackingService.ACTION_RESUME));
                        }
                    }
                    dialog.dismiss();
                    refreshUi();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private SpannableString enlargeModeSymbol(String text) {
        SpannableString label = new SpannableString(text);
        int end = text.indexOf(' ');
        if (end > 0) {
            label.setSpan(new RelativeSizeSpan(1.4f), 0, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return label;
    }

    private void openExternalUrl(String url, int errorMessage) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            android.util.Log.e("TripCompanion", "Unable to open external link", e);
            new AlertDialog.Builder(this)
                    .setMessage(errorMessage)
                    .setPositiveButton("OK", null)
                    .show();
        }
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
        requestTrailFolderThenStart();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            if (Build.VERSION.SDK_INT < 23
                    || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED) {
                requestTrailFolderThenStart();
            } else {
                new AlertDialog.Builder(this)
                        .setMessage("Precise location is required to record your GPS trail. "
                                + "Enable precise location for TrailTracker in app settings.")
                        .setPositiveButton("OK", null)
                        .show();
            }
        } else if (requestCode == REQ_NOTIFICATION) {
            startTracking();
        } else if (requestCode == REQ_STORAGE_SHARE) {
            if (hasTrailStoragePermission()) {
                shareTrail();
            } else {
                new AlertDialog.Builder(this).setMessage(R.string.trail_storage_permission_required)
                        .setPositiveButton(android.R.string.ok, null).show();
            }
        }
    }

    private boolean hasTrailStoragePermission() {
        return Build.VERSION.SDK_INT >= 29
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestTrailFolderThenStart() {
        try {
            if (FolderTrailStore.selectedFolder(this) != null) {
                requestNotificationThenStart();
                return;
            }
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            android.util.Log.w("TripCompanion", "Trail folder must be selected again", e);
        }
        new AlertDialog.Builder(this)
                .setMessage(R.string.trail_folder_select_explanation)
                .setPositiveButton(R.string.trail_folder_select, (dialog, which) -> openTrailFolderPicker())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openTrailFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= 26) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:"));
        }
        try {
            startActivityForResult(intent, REQ_TRAIL_FOLDER);
        } catch (ActivityNotFoundException | SecurityException e) {
            android.util.Log.e("TripCompanion", "Cannot open trail folder picker", e);
            new AlertDialog.Builder(this).setMessage(R.string.trail_folder_picker_failed)
                    .setPositiveButton(android.R.string.ok, null).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_TRAIL_FOLDER) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            Toast.makeText(this, R.string.trail_folder_not_selected, Toast.LENGTH_LONG).show();
            return;
        }
        Uri uri = data.getData();
        int flags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        int required = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            if (!FolderTrailStore.isExpectedFolder(uri) || flags != required) {
                throw new IOException("The selected folder is not Main Storage/Trails or lacks access");
            }
            getContentResolver().takePersistableUriPermission(uri, flags);
            FolderTrailStore.rememberFolder(this, uri);
            requestNotificationThenStart();
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            android.util.Log.e("TripCompanion", "Cannot use selected trail folder", e);
            new AlertDialog.Builder(this).setMessage(R.string.trail_folder_invalid)
                    .setPositiveButton(android.R.string.ok, null).show();
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

    private void showSavedTrailChoice() {
        if (savedTrailDialog != null) return;
        SavedTrail trail = tracker.savedTrailAwaitingChoice();
        if (trail == null) return;
        String gpxSeeUrl = getString(R.string.gpxsee_url);
        SpannableString message = new SpannableString(
                getString(R.string.trail_saved_path, trail.path, gpxSeeUrl));
        int linkStart = message.toString().indexOf(gpxSeeUrl);
        if (linkStart >= 0) {
            message.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    openExternalUrl(gpxSeeUrl, R.string.gpxsee_no_browser);
                }
            }, linkStart, linkStart + gpxSeeUrl.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.trail_saved_title)
                .setMessage(message)
                .setPositiveButton(R.string.trail_keep, (ignored, which) ->
                        tracker.acknowledgeSavedTrail(trail))
                .setNegativeButton(R.string.trail_delete, null)
                .setCancelable(false)
                .create();
        savedTrailDialog = dialog;
        dialog.setOnDismissListener(ignored -> savedTrailDialog = null);
        dialog.setOnShowListener(ignored -> {
            TextView messageView = dialog.findViewById(android.R.id.message);
            if (messageView != null) messageView.setMovementMethod(LinkMovementMethod.getInstance());
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                try {
                    PublicTrailStore.delete(this, trail);
                    tracker.acknowledgeSavedTrail(trail);
                    dialog.dismiss();
                } catch (IOException | SecurityException | IllegalArgumentException | IllegalStateException e) {
                    android.util.Log.e("TripCompanion", "Unable to delete saved trail", e);
                    Toast.makeText(this, R.string.trail_delete_failed, Toast.LENGTH_LONG).show();
                }
            });
        });
        dialog.show();
    }

    private void shareTrail() {
        showTrailList(false);
    }

    private void showTrailList(boolean sharing) {
        if (!hasTrailStoragePermission()) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE_SHARE);
            return;
        }
        final SavedTrail[] files;
        try {
            files = PublicTrailStore.list(this);
        } catch (IOException | SecurityException | IllegalArgumentException | IllegalStateException e) {
            android.util.Log.e("TripCompanion", "Unable to list saved trails", e);
            new AlertDialog.Builder(this).setMessage(R.string.trail_list_failed)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        if (files.length == 0) {
            new AlertDialog.Builder(this).setMessage(R.string.trail_none_saved)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; ++i) names[i] = files[i].name;
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(sharing ? R.string.share_trail_title : R.string.open_trail_title)
                .setItems(names, (dialog, which) -> {
                    Uri uri = Uri.parse(files[which].uri);
                    if (!sharing) {
                        openTrailMap(uri);
                        return;
                    }
                    Intent intent = new Intent(Intent.ACTION_SEND)
                            .setType(TrailShareProvider.MIME_TYPE)
                            .putExtra(Intent.EXTRA_STREAM, uri);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    intent.setClipData(ClipData.newRawUri("GPX trail", uri));
                    try {
                        startActivity(Intent.createChooser(intent, getString(R.string.share_trail_title)));
                    } catch (ActivityNotFoundException | SecurityException e) {
                        android.util.Log.e("TripCompanion", "Unable to share trail", e);
                        new AlertDialog.Builder(this)
                                .setMessage(R.string.trail_share_failed)
                                .setPositiveButton(android.R.string.ok, null).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null);
        if (!sharing) {
            builder.setNeutralButton(R.string.share_trail_instead,
                    (dialog, which) -> showTrailList(true));
        }
        builder.show();
    }

    private void openTrailMap(Uri uri) {
        Runnable open = () -> startActivity(new Intent(this, TrailMapActivity.class).setData(uri));
        if (getPreferences(MODE_PRIVATE).getBoolean("map_privacy_accepted", false)) {
            open.run();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.map_privacy_title)
                    .setMessage(R.string.map_privacy_message)
                    .setPositiveButton(R.string.map_privacy_continue, (dialog, which) -> {
                        getPreferences(MODE_PRIVATE).edit().putBoolean("map_privacy_accepted", true).apply();
                        open.run();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
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
        photoButton.setEnabled(tracking);
        String mode = tracking && paused ? getString(R.string.transport_pause)
                : getResources().getStringArray(R.array.transport_modes)[tracker.transportMode().ordinal()];
        transportButton.setText(enlargeModeSymbol(mode.substring(0, mode.indexOf(' '))));
        transportButton.setContentDescription(getString(R.string.transport_mode_description, mode));
        updateDateAndMoon();
        if (tracking && !paused) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        boolean providerOn = locationManager != null
                && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        String acc = tracker.hasAccuracy()
                ? DistanceFormatter.formatAccuracy(tracker.lastAccuracyM(), imperialUnits)
                : "--";
        if (tracker.error() != null) {
            statusText.setText(tracker.error());
        } else if (paused) {
            statusText.setText("Trip paused");
        } else if (!providerOn) {
            statusText.setText(tracker.isSample() ? R.string.sample_trip_gps_off : R.string.location_off);
        } else if (tracker.isSample()) {
            statusText.setText(R.string.sample_trip);
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
        String gnssSummary = tracker.gnssSummary();
        satelliteText.setText(!tracking || paused || !providerOn
                ? getString(R.string.gnss_inactive)
                : getString(R.string.gnss_used, gnssSummary == null
                        ? getString(R.string.gnss_waiting) : gnssSummary));

        // Distance back to start + bearing (port of the firmware's
        // update_labels()).
        if (!hasStart) {
            bearingText.setText("--");
            if (!tracking) {
                distanceText.setText("--");
            } else if (tracker.rawFixCount() >= TripTracker.START_SETTLE_FIXES
                    && tracker.hasAccuracy()
                    && tracker.lastAccuracyM() > TripTracker.START_MAX_ACCURACY_M) {
                distanceText.setText(String.format(Locale.US, "Need %s fix (%s)",
                        DistanceFormatter.formatAccuracy(
                                TripTracker.START_MAX_ACCURACY_M, imperialUnits), acc));
            } else if (tracker.rawFixCount() > 0) {
                distanceText.setText(String.format(Locale.US, "Settling %d/%d",
                        Math.min(tracker.rawFixCount(), TripTracker.START_SETTLE_FIXES),
                        TripTracker.START_SETTLE_FIXES));
            } else {
                distanceText.setText("Wait for fix");
            }
        } else {
            distanceText.setText(
                    DistanceFormatter.formatDistance(tracker.distanceM(), imperialUnits));
            bearingText.setText(String.format(Locale.US, "%d° %s",
                    Math.round(tracker.bearingDeg()), Geo.cardinal(tracker.bearingDeg())));
        }

        if (tracker.courseValid()) {
            courseText.setText(String.format(Locale.US, "Course: %d° %s",
                    Math.round(tracker.courseDeg()), Geo.cardinal(tracker.courseDeg())));
        } else {
            courseText.setText("Course: --");
        }

        String alt = tracker.altitudeValid()
                ? DistanceFormatter.formatAltitude(tracker.altitudeM(), imperialUnits)
                : "--";
        extraText.setText(String.format(Locale.US, "Alt: %s\nPath: %s", alt,
                DistanceFormatter.formatPathLength(tracker.pathLengthM(), imperialUnits)));

        long elapsedSeconds = tracker.elapsedMs() / 1000;
        elapsedText.setText(String.format(Locale.US, "Elapsed: %02dh:%02dm:%02dsec",
                elapsedSeconds / 3600, (elapsedSeconds % 3600) / 60, elapsedSeconds % 60));

        compassView.setState(tracker.bearingDeg(), tracker.courseDeg(), hasStart,
                tracker.courseValid());
        trailView.refreshIfChanged();
        updateCaption();
    }

    private void updateUnitsToggle() {
        unitsToggleText.setText(imperialUnits
                ? R.string.units_imperial_short : R.string.units_metric_short);
    }

    private void updateDateAndMoon() {
        long now = System.currentTimeMillis();
        Locale locale = Locale.getDefault();
        String pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMEd");
        String date = new SimpleDateFormat(pattern, locale).format(new Date(now));
        int phase = MoonPhase.index(now);
        String[] symbols = getResources().getStringArray(R.array.moon_phase_symbols);
        String[] names = getResources().getStringArray(R.array.moon_phase_names);
        String symbol = symbols[phase];
        String dateAndMoon = getString(R.string.date_moon, date, symbol);
        SpannableString display = new SpannableString(dateAndMoon);
        int symbolStart = dateAndMoon.indexOf(symbol);
        if (symbolStart >= 0) {
            display.setSpan(new RelativeSizeSpan(1.5f), symbolStart, symbolStart + symbol.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        dateMoonText.setText(display);
        String units = getString(imperialUnits
                ? R.string.units_imperial_description : R.string.units_metric_description);
        unitsControl.setContentDescription(getString(R.string.date_moon_description,
                units, date, names[phase]));
    }

    private void refreshUnitsPreference() {
        imperialUnits = getPreferences(MODE_PRIVATE).getBoolean(PREF_IMPERIAL_UNITS, false);
        unitsToggleText.setVisibility(View.VISIBLE);
        updateUnitsToggle();
    }

    private void updateCaption() {
        trailCaption.setText(getString(R.string.trail_caption, tracker.trailCount()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUnitsPreference();
        handler.post(uiUpdater);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(uiUpdater);
    }
}
