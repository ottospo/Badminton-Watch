package com.ottospo.tripcompanion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.maplibre.android.MapLibre;
import org.maplibre.android.annotations.MarkerOptions;
import org.maplibre.android.annotations.PolylineOptions;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapLibreMapOptions;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.tile.TileOperation;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Point;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TrailMapActivity extends Activity {
    private static final String TAG = "TrailMap";
    private static final String STYLE = "{\"version\":8,\"sources\":{\"osm\":{"
            + "\"type\":\"raster\",\"tiles\":[\"https://tile.openstreetmap.org/{z}/{x}/{y}.png\"],"
            + "\"tileSize\":256,\"maxzoom\":19}},\"layers\":["
            + "{\"id\":\"background\",\"type\":\"background\",\"paint\":{\"background-color\":\"#eeeeee\"}},"
            + "{\"id\":\"osm\",\"type\":\"raster\",\"source\":\"osm\"}]}";

    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private MapView mapView;
    private TextView status;
    private Uri trailUri;
    private ConnectivityManager connectivity;
    private boolean trailLoaded;
    private boolean trailLoadFailed;
    private boolean mapDisplayFailed;
    private boolean networkCallbackRegistered;
    private final Set<String> failedTiles = new HashSet<>();
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { refreshStatusOnUiThread(); }
        @Override public void onLost(Network network) { refreshStatusOnUiThread(); }
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            refreshStatusOnUiThread();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        trailUri = getIntent().getData();
        if (trailUri == null || !"content".equals(trailUri.getScheme())) {
            Log.e(TAG, "Missing saved trail URI");
            finish();
            return;
        }
        MapLibre.getInstance(this);
        connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout buttons = new LinearLayout(this);
        Button back = new Button(this);
        back.setText(R.string.map_back);
        back.setOnClickListener(v -> finish());
        Button external = new Button(this);
        external.setText(R.string.map_open_external);
        external.setOnClickListener(v -> openExternal(false));
        Button share = new Button(this);
        share.setText(R.string.share_trail);
        share.setOnClickListener(v -> openExternal(true));
        buttons.addView(back, new LinearLayout.LayoutParams(0, -2, 1));
        buttons.addView(external, new LinearLayout.LayoutParams(0, -2, 1));
        buttons.addView(share, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(buttons);
        status = new TextView(this);
        status.setText(R.string.map_loading);
        status.setPadding(12, 8, 12, 8);
        root.addView(status);
        mapView = new MapView(this, MapLibreMapOptions.createFromAttributes(this)
                .setPrefetchesTiles(false).setPrefetchZoomDelta(0));
        mapView.addOnDidFailLoadingMapListener(error -> {
            Log.e(TAG, "Map could not be loaded: " + error);
            mapDisplayFailed = true;
            updateStatus();
        });
        mapView.addOnTileActionListener((operation, x, y, z, wrap, overscaledZ, source) -> {
            if ("osm".equals(source) && (operation == TileOperation.Error
                    || operation == TileOperation.EndParse || operation == TileOperation.Cancelled)) {
                String tile = x + ":" + y + ":" + z + ":" + wrap + ":" + overscaledZ;
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        boolean changed;
                        if (operation == TileOperation.Error) {
                            Log.w(TAG, "A map tile could not be loaded");
                            changed = failedTiles.add(tile);
                        } else {
                            changed = failedTiles.remove(tile);
                        }
                        if (changed) updateStatus();
                    }
                });
            }
        });
        root.addView(mapView, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView attribution = new TextView(this);
        attribution.setText(R.string.map_attribution);
        attribution.setGravity(Gravity.END);
        attribution.setPadding(12, 6, 12, 6);
        attribution.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.openstreetmap.org/copyright")));
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "No browser for OpenStreetMap attribution", e);
                new AlertDialog.Builder(this).setMessage(R.string.map_no_browser)
                        .setPositiveButton(android.R.string.ok, null).show();
            }
        });
        root.addView(attribution);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        mapView.onCreate(state);
        loader.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(trailUri)) {
                if (input == null) throw new IOException("Cannot open saved trail");
                GpxMapTrail trail = GpxMapTrail.read(input);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) showTrail(trail);
                });
            } catch (IOException | SecurityException e) {
                Log.e(TAG, "Cannot load saved trail", e);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        trailLoadFailed = true;
                        updateStatus();
                    }
                });
            }
        });
    }

    private void showTrail(GpxMapTrail trail) {
        ArrayList<LatLng> allPoints = new ArrayList<>();
        for (GpxMapTrail.Segment segment : trail.segments) {
            for (GpxMapTrail.Point point : segment.points) {
                allPoints.add(new LatLng(point.latitude, point.longitude));
            }
        }
        mapView.getMapAsync(map -> mapView.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            fitTrail(map, allPoints);
            map.setStyle(new Style.Builder().fromJson(STYLE), style -> {
                for (GpxMapTrail.Segment segment : trail.segments) {
                    if (segment.points.isEmpty()) continue;
                    ArrayList<LatLng> points = new ArrayList<>();
                    for (GpxMapTrail.Point point : segment.points) {
                        points.add(new LatLng(point.latitude, point.longitude));
                    }
                    if (points.size() > 1) {
                        map.addPolyline(new PolylineOptions().addAll(points)
                                .color(segment.color).width(5f));
                    } else {
                        map.addMarker(new MarkerOptions().position(points.get(0)));
                    }
                }
                if (!trail.photoMarkers.isEmpty()) {
                    ArrayList<Feature> markers = new ArrayList<>();
                    for (GpxMapTrail.Point marker : trail.photoMarkers) {
                        markers.add(Feature.fromGeometry(Point.fromLngLat(marker.longitude, marker.latitude)));
                    }
                    style.addSource(new GeoJsonSource("photo-markers", FeatureCollection.fromFeatures(markers)));
                    style.addLayer(new CircleLayer("photo-markers", "photo-markers").withProperties(
                            PropertyFactory.circleRadius(5f),
                            PropertyFactory.circleColor(0xffffcc00),
                            PropertyFactory.circleStrokeWidth(2f),
                            PropertyFactory.circleStrokeColor(0xff000000)));
                }
                trailLoaded = true;
                updateStatus();
            });
        }));
    }

    private void refreshStatusOnUiThread() {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) updateStatus();
        });
    }

    private void updateStatus() {
        if (trailLoadFailed) {
            status.setText(R.string.map_load_failed);
        } else if (mapDisplayFailed) {
            status.setText(R.string.map_display_failed);
        } else if (!trailLoaded) {
            status.setText(R.string.map_loading);
        } else {
            Network network = connectivity.getActiveNetwork();
            NetworkCapabilities capabilities = network == null ? null : connectivity.getNetworkCapabilities(network);
            if (capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                status.setText(R.string.map_offline);
            } else if (!failedTiles.isEmpty()) {
                status.setText(R.string.map_tiles_failed);
            } else {
                status.setText(R.string.map_network_hint);
            }
        }
    }

    private void fitTrail(MapLibreMap map, ArrayList<LatLng> points) {
        LatLng first = points.get(0);
        LatLngBounds.Builder builder = new LatLngBounds.Builder();
        boolean different = false;
        for (LatLng point : points) {
            builder.include(point);
            different |= point.getLatitude() != first.getLatitude()
                    || point.getLongitude() != first.getLongitude();
        }
        if (different) {
            int padding = (int) (40 * getResources().getDisplayMetrics().density);
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), padding));
            if (map.getCameraPosition().zoom > 17) {
                map.moveCamera(CameraUpdateFactory.zoomTo(17));
            }
        } else {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(first, 16));
        }
    }

    private void openExternal(boolean share) {
        Intent intent = share
                ? new Intent(Intent.ACTION_SEND).setType(TrailShareProvider.MIME_TYPE)
                        .putExtra(Intent.EXTRA_STREAM, trailUri)
                : new Intent(Intent.ACTION_VIEW).setDataAndType(trailUri, TrailShareProvider.MIME_TYPE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("GPX trail", trailUri));
        try {
            startActivity(share ? Intent.createChooser(intent, getString(R.string.share_trail_title)) : intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.e(TAG, "Unable to open or share trail", e);
            new AlertDialog.Builder(this)
                    .setMessage(share ? R.string.trail_share_failed : R.string.trail_open_failed)
                    .setPositiveButton(android.R.string.ok, null).show();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (mapView != null) {
            mapView.onStart();
            connectivity.registerDefaultNetworkCallback(networkCallback);
            networkCallbackRegistered = true;
            updateStatus();
        }
    }
    @Override protected void onResume() { super.onResume(); if (mapView != null) mapView.onResume(); }
    @Override protected void onPause() { if (mapView != null) mapView.onPause(); super.onPause(); }
    @Override protected void onStop() {
        if (networkCallbackRegistered) {
            connectivity.unregisterNetworkCallback(networkCallback);
            networkCallbackRegistered = false;
        }
        if (mapView != null) mapView.onStop();
        super.onStop();
    }
    @Override public void onLowMemory() { super.onLowMemory(); if (mapView != null) mapView.onLowMemory(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (mapView != null) mapView.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        loader.shutdownNow();
        if (mapView != null) mapView.onDestroy();
        super.onDestroy();
    }
}
