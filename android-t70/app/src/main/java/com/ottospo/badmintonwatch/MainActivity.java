package com.ottospo.badmintonwatch;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.content.SharedPreferences;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * Badminton hit counter — Android port of the T-Watch-S3 firmware
 * (src/main.cpp, non-LoRa "twatchs3-nolora" build).
 */
public class MainActivity extends Activity implements SensorEventListener {

    private static final String TAG = "HitCounter";

    // The watch's BMA423 runs at +/-4G (8192 LSB/g), so the firmware's raw delta
    // threshold of 650 equals 650 / 8192 * 9.81 = 0.78 m/s^2 in Android units.
    // Phone accelerometers are far more sensitive, so the default is higher and
    // user-tunable via the on-screen slider (0.5 .. 15.0 m/s^2).
    private static final float DEFAULT_THRESHOLD_MS2 = 10.0f;
    private static final long HIT_REFRACTORY_MS = 1000;  // min time between hits
    private static final long SET_RESET_MS = 5000;       // idle gap that ends a set
    private static final String PREFS_NAME = "hit_counter";
    private static final String PREF_THRESHOLD_PROGRESS = "threshold_progress";

    private SensorManager sensorManager;
    private Sensor accelerometer;

    private TextView statsValues;
    private TextView elapsedText;
    private TextView sensitivityText;
    private SeekBar sensitivityBar;
    private Button startStopButton;
    private BatteryView batteryView;
    private SharedPreferences prefs;
    private float hitThresholdMs2 = DEFAULT_THRESHOLD_MS2;

    private int setHitCount = 0;
    private int totalHitCount = 0;
    private int maxSetHitCount = 0;
    private float previousX, previousY, previousZ;
    private boolean hasPreviousSample = false;
    private long nextHitTime = 0;
    private long timerStartedAt = 0;
    private long elapsedTimeMs = 0;
    private long lastHitAt = 0;
    private boolean hasHitInSet = false;
    private boolean countingEnabled = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable elapsedUpdater = new Runnable() {
        @Override
        public void run() {
            if (countingEnabled) {
                elapsedTimeMs = SystemClock.uptimeMillis() - timerStartedAt;
                updateElapsedLabel();
                handler.postDelayed(this, 250);
            }
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

        statsValues = findViewById(R.id.stats_values);
        elapsedText = findViewById(R.id.elapsed_time);
        sensitivityText = findViewById(R.id.sensitivity_text);
        sensitivityBar = findViewById(R.id.sensitivity_bar);
        startStopButton = findViewById(R.id.start_stop_button);
        batteryView = findViewById(R.id.battery_view);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sensitivityBar.setMax(145); // 0.5 .. 15.0 m/s^2 in 0.1 steps
        sensitivityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                hitThresholdMs2 = 0.5f + progress * 0.1f;
                updateSensitivityLabel();
                prefs.edit().putInt(PREF_THRESHOLD_PROGRESS, progress).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        sensitivityBar.setProgress(prefs.getInt(PREF_THRESHOLD_PROGRESS,
                Math.round((DEFAULT_THRESHOLD_MS2 - 0.5f) * 10)));

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        startStopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleCounting();
            }
        });

        updateStatsLabels();
        updateElapsedLabel();
    }

    private void toggleCounting() {
        countingEnabled = !countingEnabled;
        startStopButton.setText(countingEnabled ? "STOP" : "START");
        if (countingEnabled) {
            timerStartedAt = SystemClock.uptimeMillis();
            elapsedTimeMs = 0;
            setHitCount = 0;
            totalHitCount = 0;
            maxSetHitCount = 0;
            lastHitAt = 0;
            hasHitInSet = false;
            hasPreviousSample = false;
            nextHitTime = 0;
            updateStatsLabels();
            updateElapsedLabel();
            handler.post(elapsedUpdater);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            elapsedTimeMs = SystemClock.uptimeMillis() - timerStartedAt;
            handler.removeCallbacks(elapsedUpdater);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        Log.d(TAG, countingEnabled ? "Hit counting started" : "Hit counting stopped");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        if (!hasPreviousSample) {
            previousX = x;
            previousY = y;
            previousZ = z;
            hasPreviousSample = true;
            return;
        }

        float delta = Math.abs(x - previousX) + Math.abs(y - previousY) + Math.abs(z - previousZ);
        long now = SystemClock.uptimeMillis();

        if (countingEnabled && delta > hitThresholdMs2 && now >= nextHitTime) {
            if (!hasHitInSet || now - lastHitAt >= SET_RESET_MS) {
                setHitCount = 0;
            }
            setHitCount++;
            totalHitCount++;
            maxSetHitCount = Math.max(maxSetHitCount, setHitCount);
            lastHitAt = now;
            hasHitInSet = true;
            nextHitTime = now + HIT_REFRACTORY_MS;
            updateStatsLabels();
            Log.d(TAG, "Hit detected: set=" + setHitCount + " total=" + totalHitCount
                    + " max=" + maxSetHitCount + " delta=" + delta);
        }

        previousX = x;
        previousY = y;
        previousZ = z;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void updateStatsLabels() {
        statsValues.setText(String.format(Locale.US, "%d\n%d\n%d",
                setHitCount, totalHitCount, maxSetHitCount));
    }

    private void updateSensitivityLabel() {
        sensitivityText.setText(String.format(Locale.US, "Hit threshold: %.1f", hitThresholdMs2));
    }

    private void updateElapsedLabel() {
        long elapsedSeconds = elapsedTimeMs / 1000;
        elapsedText.setText(String.format(Locale.US, "Elapsed: %02dh:%02dm:%02dsec",
                elapsedSeconds / 3600, (elapsedSeconds % 3600) / 60, elapsedSeconds % 60));
    }

    @Override
    protected void onResume() {
        super.onResume();
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        unregisterReceiver(batteryReceiver);
    }
}
