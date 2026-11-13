/**
 * @file      trip_companion.cpp
 * @brief     GPS trip companion for the T-Watch-S3.
 *
 *            START captures the current position as the trip's starting
 *            point; from then on the readouts show the straight-line
 *            distance and bearing back to it, plus the elapsed trip time,
 *            the altitude, and the total path length. The compass dial with
 *            the course over ground lives on the separate compass face.
 *
 *            While tracking, the GPS stays powered continuously and the
 *            readouts update live at 1 Hz. Duty-cycling is not possible:
 *            cutting the module's power also cuts its backup domain, so
 *            every wake would be a full cold start (~30 s to fix). The GPS
 *            powers down when both GPS features stop or are paused. A shared
 *            pause preserves the sessions and excludes rest from trip time.
 *
 *            A fix whose step from the last accepted fix exceeds what the
 *            trip's average speed allows for the elapsed time is a bogus
 *            spike (a lost fix can report a position kilometers away) and is
 *            discarded completely, so one bad fix cannot distort the start
 *            point, the distance, or the path length.
 */
#include <LilyGoLib.h>
#include "trip_companion.h"
#include "breadcrumb.h"

namespace {

constexpr uint32_t UI_UPDATE_MS = 1000;                     // 1 Hz readout refresh
// GPS course over ground is only meaningful while moving.
constexpr float COURSE_MIN_SPEED_MPS = 0.5f;

lv_obj_t *status_label;
lv_obj_t *distance_label;
lv_obj_t *bearing_label;
lv_obj_t *course_label;
lv_obj_t *elapsed_label;
lv_obj_t *extra_label;
lv_obj_t *toggle_btn;
lv_obj_t *pause_buttons[2] = {};
int pause_button_count = 0;

bool tracking = false;
bool paused = false;
uint32_t paused_at = 0;
uint32_t fresh_fix_after = 0;
bool waiting_for_fresh_fix = false;
bool rebase_after_pause = false;
bool has_start_point = false;
double start_lat = 0.0;
double start_lng = 0.0;
double last_distance_m = 0.0;
double last_bearing_deg = 0.0;
double last_course_deg = 0.0;
bool course_valid = false;
double path_length_m = 0.0;     // total distance walked (sum of segments)
double altitude_m = 0.0;
bool altitude_valid = false;
double prev_smooth_lat = 0.0;   // previous smoothed fix, for path length
double prev_smooth_lng = 0.0;
bool has_prev_smooth = false;
uint32_t started_at = 0;
uint32_t elapsed_ms = 0;
uint32_t tracking_started_at = 0;
uint32_t last_fix_at = 0;
uint32_t last_ui_update = 0;

// Position smoothing: a stationary GPS receiver reports a position that
// wanders by tens of meters, which would otherwise show up as movement.
// An exponential moving average damps that jitter. The start point is
// captured only after the average has settled over a few fixes.
// After the start point locks, the smoothed position only moves when a fix
// lands far enough away to be real movement (beyond the noise floor). A noisy
// signal makes the GPS speed reading unreliable, so displacement — not speed
// — decides stationary vs. moving. MOVE_DIST_M is the deadband radius.
constexpr float SMOOTH_MOVING = 0.4f;      // smoothing weight while moving
constexpr double MOVE_DIST_M = 4.0;        // min displacement to count as moving
constexpr int START_SETTLE_FIXES = 10;     // fixes averaged before START locks
constexpr int START_MIN_SATS = 5;          // min satellites to lock the start
constexpr float START_MAX_HDOP = 3.0f;     // max HDOP for a trustworthy start
// Plausibility gate: the differential distance between a new fix and the
// last accepted fix must never exceed what the trip's average speed allows
// for the elapsed time; such a fix is a bogus spike and is discarded
// completely. The floor covers moving off after a long stop, when the
// average has decayed toward zero. If the "spike" position persists it is
// real (re-acquisition after a long signal gap), so after MAX_DISCARD_STREAK
// consecutive discards the fix is accepted as the new reference.
constexpr double STEP_FLOOR_M = 20.0;      // always allow steps up to this
constexpr double SPEED_MARGIN = 3.0;       // allow bursts up to 3x avg speed
constexpr int MAX_DISCARD_STREAK = 10;     // consecutive discards before re-lock
double smooth_lat = 0.0;
double smooth_lng = 0.0;
int smooth_count = 0;
int raw_fix_count = 0;          // every accepted genuine fix, moving or not
double last_accepted_lat = 0.0; // last fix that passed the plausibility gate
double last_accepted_lng = 0.0;
uint32_t last_accepted_at = 0;
bool has_accepted = false;
int discard_streak = 0;         // consecutive fixes rejected by the gate

bool gps_powered = true;        // GPS is powered at boot for the time sync

// Accept a position only when it comes from actual satellite reception:
// TinyGPSPlus treats all-zero NMEA fields as valid, so before a real fix the
// module reports a bogus position with a 1999-era date.
bool genuine_fix()
{
    return instance.gps.location.isValid()
           && instance.gps.date.isValid()
           && instance.gps.date.year() >= 2025;
}

void set_distance_text(double dist_m)
{
    if (dist_m < 1000.0) {
        lv_label_set_text_fmt(distance_label, "%lu m",
                              static_cast<unsigned long>(dist_m + 0.5));
    } else {
        // lv_snprintf has no float support: split into km + 10 m digits
        unsigned long deci_km = static_cast<unsigned long>(dist_m / 10.0 + 0.5);
        lv_label_set_text_fmt(distance_label, "%lu.%02lu km",
                              deci_km / 100, deci_km % 100);
    }
}

void update_labels(uint32_t now)
{
    // Live satellite count so the user can tell acquiring (sats climbing)
    // apart from no sky view (sats stuck at 0).
    int sats = instance.gps.satellites.isValid()
               ? static_cast<int>(instance.gps.satellites.value()) : 0;
    if (paused && tracking) {
        lv_label_set_text(status_label, "GPS: paused");
    } else if (!tracking) {
        lv_label_set_text(status_label,
                          trip_gps_powered() ? "GPS: time sync..." : "GPS off");
    } else if (waiting_for_fresh_fix && has_start_point) {
        lv_label_set_text_fmt(status_label, "Reacquiring GPS... Sats: %d", sats);
    } else if (!has_start_point) {
        lv_label_set_text_fmt(status_label, "Acquiring GPS... %lus  Sats: %d",
                              static_cast<unsigned long>((now - tracking_started_at) / 1000),
                              sats);
    } else {
        lv_label_set_text_fmt(status_label, "Fix: %lus ago  Sats: %d",
                              static_cast<unsigned long>((now - last_fix_at) / 1000),
                              sats);
    }

    if (!has_start_point) {
        if (tracking) {
            if (raw_fix_count >= START_SETTLE_FIXES && sats < START_MIN_SATS) {
                lv_label_set_text_fmt(distance_label, "Need %d+ sats (%d)",
                                      START_MIN_SATS, sats);
            } else if (raw_fix_count > 0) {
                lv_label_set_text_fmt(distance_label, "Settling %d/%d",
                                      raw_fix_count, START_SETTLE_FIXES);
            } else {
                lv_label_set_text_fmt(distance_label, "Wait for fix (%d sat%s)", sats,
                                      sats == 1 ? "" : "s");
            }
        } else {
            lv_label_set_text(distance_label, "--");
        }
        lv_label_set_text(bearing_label, "--");
    } else {
        set_distance_text(last_distance_m);
        lv_label_set_text_fmt(bearing_label, "%lu deg %s",
                              static_cast<unsigned long>(last_bearing_deg + 0.5),
                              TinyGPSPlus::cardinal(last_bearing_deg));
    }

    if (course_valid) {
        lv_label_set_text_fmt(course_label, "Course: %lu deg %s",
                              static_cast<unsigned long>(last_course_deg + 0.5),
                              TinyGPSPlus::cardinal(last_course_deg));
    } else {
        lv_label_set_text(course_label, "Course: --");
    }

    // Altitude and total path length. Altitude shows -- until the GPS
    // reports a valid value; the path length reuses the set_distance_text
    // approach (lv_snprintf has no float support: split km into integer +
    // 100 m digits).
    char alt_buf[12];
    if (altitude_valid) {
        lv_snprintf(alt_buf, sizeof(alt_buf), "%ld m",
                    static_cast<long>(altitude_m + 0.5));
    } else {
        lv_snprintf(alt_buf, sizeof(alt_buf), "--");
    }
    if (path_length_m < 1000.0) {
        lv_label_set_text_fmt(extra_label, "Alt: %s  Path: %lu m", alt_buf,
                              static_cast<unsigned long>(path_length_m + 0.5));
    } else {
        unsigned long deci_km = static_cast<unsigned long>(path_length_m / 100.0 + 0.5);
        lv_label_set_text_fmt(extra_label, "Alt: %s  Path: %lu.%lu km", alt_buf,
                              deci_km / 10, deci_km % 10);
    }

    uint32_t elapsed_seconds = elapsed_ms / 1000;
    lv_label_set_text_fmt(elapsed_label, "Elapsed: %02luh:%02lum:%02lusec",
                          static_cast<unsigned long>(elapsed_seconds / 3600),
                          static_cast<unsigned long>((elapsed_seconds % 3600) / 60),
                          static_cast<unsigned long>(elapsed_seconds % 60));
}

void toggle_event_cb(lv_event_t *)
{
    tracking = !tracking;
    lv_label_set_text(lv_obj_get_child(toggle_btn, 0), tracking ? "STOP" : "START");
    if (tracking) {
        has_start_point = false;
        course_valid = false;
        elapsed_ms = 0;
        path_length_m = 0.0;
        altitude_valid = false;
        has_prev_smooth = false;
        smooth_lat = 0.0;
        smooth_lng = 0.0;
        smooth_count = 0;
        raw_fix_count = 0;
        has_accepted = false;
        discard_streak = 0;
        tracking_started_at = paused ? paused_at : millis();
        fresh_fix_after = millis();
        waiting_for_fresh_fix = true;
        rebase_after_pause = false;
        if (!paused) {
            trip_gps_set_power(true);
        }
        Serial.println("Trip tracking started");
    } else {
        // Do not power the GPS down here: the main loop owns that decision
        // (a pending clock time sync may still need it).
        Serial.println("Trip tracking stopped");
    }
    trip_refresh_pause_buttons();
}

void pause_event_cb(lv_event_t *)
{
    if (!tracking && !breadcrumb_active()) {
        return;
    }
    uint32_t now = millis();
    paused = !paused;
    if (paused) {
        paused_at = now;
        if (tracking && has_start_point) {
            elapsed_ms = now - started_at;
        }
        course_valid = false;
        breadcrumb_pause();
        trip_gps_set_power(false);
        Serial.println("GPS sessions paused");
    } else {
        uint32_t rest = now - paused_at;
        if (tracking) {
            tracking_started_at += rest;
            if (has_start_point) {
                started_at += rest;
                rebase_after_pause = true;
                if (has_accepted) {
                    last_accepted_at += rest;
                }
                has_prev_smooth = false;
            }
            fresh_fix_after = now;
            waiting_for_fresh_fix = true;
        }
        breadcrumb_resume(rest);
        trip_gps_set_power(true);
        Serial.println("GPS sessions resumed; waiting for a fresh fix");
    }
    trip_refresh_pause_buttons();
    update_labels(now);
}

} // namespace

lv_obj_t *trip_create_pause_button(lv_obj_t *parent)
{
    lv_obj_t *button = lv_button_create(parent);
    lv_obj_set_size(button, 100, 34);
    lv_obj_align(button, LV_ALIGN_BOTTOM_MID, 0, -2);
    lv_obj_add_event_cb(button, pause_event_cb, LV_EVENT_CLICKED, NULL);
    lv_obj_t *label = lv_label_create(button);
    lv_label_set_text(label, "PAUSE");
    lv_obj_center(label);
    if (pause_button_count < 2) {
        pause_buttons[pause_button_count++] = button;
    }
    trip_refresh_pause_buttons();
    return button;
}

void trip_refresh_pause_buttons()
{
    bool active = tracking || breadcrumb_active();
    if (!active) {
        paused = false;
    }
    for (int i = 0; i < pause_button_count; ++i) {
        lv_label_set_text(lv_obj_get_child(pause_buttons[i], 0),
                          paused ? "UNPAUSE" : "PAUSE");
        if (active) {
            lv_obj_remove_state(pause_buttons[i], LV_STATE_DISABLED);
        } else {
            lv_obj_add_state(pause_buttons[i], LV_STATE_DISABLED);
        }
    }
}

bool trip_paused()
{
    return paused;
}

void trip_gps_set_power(bool on)
{
    if (on == gps_powered) {
        return;
    }
    gps_powered = on;
    instance.powerControl(POWER_GPS, on);
    Serial.printf("GPS power %s\n", on ? "ON" : "OFF");
}

bool trip_gps_powered()
{
    return gps_powered;
}

bool trip_companion_tracking()
{
    return tracking;
}

double trip_bearing_deg()
{
    return last_bearing_deg;
}

double trip_course_deg()
{
    return last_course_deg;
}

bool trip_course_valid()
{
    return course_valid;
}

bool trip_has_start()
{
    return has_start_point;
}

lv_obj_t *trip_companion_create(lv_event_cb_t back_cb, lv_event_cb_t map_cb)
{
    lv_obj_t *trip_screen = lv_obj_create(NULL);

    status_label = lv_label_create(trip_screen);
    lv_label_set_text(status_label, "GPS off");
    lv_obj_align(status_label, LV_ALIGN_TOP_MID, 0, 6);

    distance_label = lv_label_create(trip_screen);
    lv_label_set_text(distance_label, "--");
    lv_obj_set_style_text_font(distance_label, &lv_font_montserrat_24, 0);
    lv_obj_align(distance_label, LV_ALIGN_TOP_MID, 0, 30);

    // Label colors match the compass markers
    bearing_label = lv_label_create(trip_screen);
    lv_label_set_text(bearing_label, "--");
    lv_obj_set_style_text_font(bearing_label, &lv_font_montserrat_16, 0);
    lv_obj_set_style_text_color(bearing_label, lv_color_hex(0x35C759), 0);
    lv_obj_align(bearing_label, LV_ALIGN_TOP_MID, 0, 60);

    course_label = lv_label_create(trip_screen);
    lv_label_set_text(course_label, "Course: --");
    lv_obj_set_style_text_font(course_label, &lv_font_montserrat_16, 0);
    lv_obj_set_style_text_color(course_label, lv_color_hex(0xE02020), 0);
    lv_obj_align(course_label, LV_ALIGN_TOP_MID, 0, 82);

    extra_label = lv_label_create(trip_screen);
    lv_label_set_text(extra_label, "Alt: --  Path: 0 m");
    lv_obj_align(extra_label, LV_ALIGN_TOP_MID, 0, 104);

    elapsed_label = lv_label_create(trip_screen);
    lv_label_set_text(elapsed_label, "Elapsed: 00h:00m:00sec");
    lv_obj_align(elapsed_label, LV_ALIGN_TOP_MID, 0, 126);

    // Navigation and START/STOP share a row, with PAUSE underneath.
    lv_obj_t *back_btn = lv_button_create(trip_screen);
    lv_obj_set_size(back_btn, 44, 38);
    lv_obj_align(back_btn, LV_ALIGN_TOP_LEFT, 4, 160);
    lv_obj_add_event_cb(back_btn, back_cb, LV_EVENT_CLICKED, NULL);
    lv_obj_t *back_label = lv_label_create(back_btn);
    lv_label_set_text(back_label, LV_SYMBOL_LEFT);
    lv_obj_center(back_label);

    toggle_btn = lv_button_create(trip_screen);
    lv_obj_set_size(toggle_btn, 84, 34);
    lv_obj_align(toggle_btn, LV_ALIGN_TOP_MID, 0, 160);
    lv_obj_add_event_cb(toggle_btn, toggle_event_cb, LV_EVENT_CLICKED, NULL);
    lv_obj_t *toggle_label = lv_label_create(toggle_btn);
    lv_label_set_text(toggle_label, "START");
    lv_obj_center(toggle_label);

    // Right-pointing button to the breadcrumb trail (2nd GPS) screen
    lv_obj_t *map_btn = lv_button_create(trip_screen);
    lv_obj_set_size(map_btn, 44, 38);
    lv_obj_align(map_btn, LV_ALIGN_TOP_RIGHT, -4, 160);
    lv_obj_add_event_cb(map_btn, map_cb, LV_EVENT_CLICKED, NULL);
    lv_obj_t *map_label = lv_label_create(map_btn);
    lv_label_set_text(map_label, LV_SYMBOL_RIGHT);
    lv_obj_center(map_label);

    trip_create_pause_button(trip_screen);

    return trip_screen;
}

void trip_companion_update()
{
    uint32_t now = millis();

    // The GPS stays powered continuously while tracking; readouts update on
    // each new 1 Hz position, smoothed to suppress stationary jitter.
    if (tracking && !paused && trip_gps_powered() && instance.gps.location.isUpdated()
            && genuine_fix() && instance.gps.location.age() < 3000
            && (!waiting_for_fresh_fix
                || static_cast<int32_t>(now - instance.gps.location.age() - fresh_fix_after) >= 0)) {
        waiting_for_fresh_fix = false;
        double lat = instance.gps.location.lat();
        double lng = instance.gps.location.lng();
        last_fix_at = now;              // the GPS is alive, even if the fix is bogus
        // Plausibility gate: a differential distance greater than the average
        // speed allows for the elapsed time can never be real movement, so
        // the fix is discarded completely — it must not touch the smoothed
        // position, the start point, or the path length.
        bool accept = true;
        if (has_accepted) {
            double step = TinyGPSPlus::distanceBetween(lat, lng,
                                                       last_accepted_lat,
                                                       last_accepted_lng);
            double dt = (now - last_accepted_at) / 1000.0;
            double avg_speed = has_start_point && now > started_at
                               ? path_length_m / ((now - started_at) / 1000.0) : 0.0;
            double allowed = avg_speed * dt * SPEED_MARGIN;
            if (allowed < STEP_FLOOR_M) {
                allowed = STEP_FLOOR_M;
            }
            if (step > allowed) {
                if (discard_streak < MAX_DISCARD_STREAK) {
                    ++discard_streak;
                    accept = false;
                    Serial.printf("Fix discarded: %.0f m in %.1f s exceeds allowed %.0f m\n",
                                  step, dt, allowed);
                } else {
                    Serial.println("Sustained offset: accepting fix as re-acquisition");
                }
            }
        }
        if (!accept) {
            // Discarded: keep every result unchanged. Skipping the elapsed/UI
            // update below is harmless — the next loop iteration handles it.
            return;
        }
        if (rebase_after_pause) {
            smooth_lat = lat;
            smooth_lng = lng;
            rebase_after_pause = false;
        }
        discard_streak = 0;
        has_accepted = true;
        last_accepted_lat = lat;
        last_accepted_lng = lng;
        last_accepted_at = now;
        ++raw_fix_count;

        // While settling (no start point yet), average every fix so the start
        // position converges to an accurate fix. After the start locks, only
        // move the smoothed position when a fix lands beyond the deadband —
        // real movement — so stationary noise can't drift the readouts.
        if (!has_start_point) {
            if (smooth_count == 0) {
                smooth_lat = lat;
                smooth_lng = lng;
            } else {
                float w = 1.0f / (smooth_count + 1);   // true running average
                smooth_lat += (lat - smooth_lat) * w;
                smooth_lng += (lng - smooth_lng) * w;
            }
            ++smooth_count;
        } else {
            double moved = TinyGPSPlus::distanceBetween(lat, lng,
                                                        smooth_lat, smooth_lng);
            if (moved >= MOVE_DIST_M) {
                smooth_lat += (lat - smooth_lat) * SMOOTH_MOVING;
                smooth_lng += (lng - smooth_lng) * SMOOTH_MOVING;
            }
            // else: within the deadband — hold position, ignore noise.
        }

        // Lock the start point only once enough genuine fixes have arrived
        // AND the fix is trustworthy (enough satellites, low HDOP), so an
        // early inaccurate fix on a weak signal doesn't become "home". Uses
        // the raw fix count so it settles even when standing still.
        int sats = instance.gps.satellites.isValid()
                   ? static_cast<int>(instance.gps.satellites.value()) : 0;
        float hdop = instance.gps.hdop.isValid()
                     ? static_cast<float>(instance.gps.hdop.hdop()) : 99.0f;
        if (!has_start_point && raw_fix_count >= START_SETTLE_FIXES
                && sats >= START_MIN_SATS && hdop <= START_MAX_HDOP) {
            start_lat = smooth_lat;
            start_lng = smooth_lng;
            has_start_point = true;
            started_at = now;
            elapsed_ms = 0;
            Serial.printf("Trip start point: %.6f, %.6f (sats=%d hdop=%.1f)\n",
                          start_lat, start_lng, sats, hdop);
        }
        if (has_start_point) {
            last_distance_m = TinyGPSPlus::distanceBetween(smooth_lat, smooth_lng,
                                                           start_lat, start_lng);
            last_bearing_deg = TinyGPSPlus::courseTo(smooth_lat, smooth_lng,
                                                     start_lat, start_lng);
            // Accumulate path length from the smoothed track; ignore sub-meter
            // steps so stationary jitter does not inflate the total.
            if (has_prev_smooth) {
                double step = TinyGPSPlus::distanceBetween(smooth_lat, smooth_lng,
                                                           prev_smooth_lat, prev_smooth_lng);
                if (step >= 1.0) {
                    path_length_m += step;
                    prev_smooth_lat = smooth_lat;
                    prev_smooth_lng = smooth_lng;
                }
            } else {
                prev_smooth_lat = smooth_lat;
                prev_smooth_lng = smooth_lng;
                has_prev_smooth = true;
            }
        }
        if (instance.gps.altitude.isValid()) {
            altitude_m = instance.gps.altitude.meters();
            altitude_valid = true;
        }
        if (instance.gps.course.isValid() && instance.gps.speed.isValid()
                && instance.gps.speed.mps() >= COURSE_MIN_SPEED_MPS) {
            last_course_deg = instance.gps.course.deg();
            course_valid = true;
        } else {
            course_valid = false;   // standing still: course is noise
        }
    }

    if (tracking && !paused && has_start_point) {
        elapsed_ms = now - started_at;
    }

    if (now - last_ui_update >= UI_UPDATE_MS) {
        last_ui_update = now;
        update_labels(now);
    }
}
