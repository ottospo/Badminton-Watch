/**
 * @file      breadcrumb.cpp
 * @brief     Breadcrumb trail map for the T-Watch-S3.
 *
 *            Records GPS fixes and draws them as connected vectors on a
 *            canvas. Every accepted coordinate is retained in PSRAM for the
 *            current trail. The start-centered map automatically zooms out
 *            to fit the whole trail. Tap to zoom in, double-tap to zoom out,
 *            drag to pan, and long-press to restore automatic fit.
 *
 *            A fix whose step from the last accepted fix exceeds what the
 *            trail's average speed allows for the elapsed time is a bogus
 *            spike and is discarded completely, so one bad fix cannot distort
 *            the map scale or the recorded trail.
 *
 *            The trail shares the trip companion's GPS: it only records while
 *            the GPS is powered and producing genuine fixes.
 */
#include <LilyGoLib.h>
#include <esp_heap_caps.h>
#include <math.h>
#include "trip_companion.h"
#include "breadcrumb.h"
#include "trail_viewport.h"

namespace {

constexpr int16_t MAP_SIZE = 240;           // square canvas edge in pixels
                                            // (full LCD width)
constexpr size_t INITIAL_POINT_CAPACITY = 256;
constexpr uint32_t DOUBLE_TAP_MS = 400;
constexpr int32_t TOUCH_SLOP = 8;
constexpr double MIN_POINT_DIST_M = 1.0;    // ignore sub-meter distance jitter
// Same plausibility gate as the trip companion: the differential distance
// between a new fix and the last accepted fix must never exceed what the
// trail's average speed allows for the elapsed time; such a fix is a bogus
// spike and is discarded completely. The floor covers moving off after a
// long stop, when the average has decayed toward zero. If the "spike"
// position persists it is real (re-acquisition after a long signal gap), so
// after MAX_DISCARD_STREAK consecutive discards the fix is accepted.
constexpr double STEP_FLOOR_M = 20.0;       // always allow steps up to this
constexpr double SPEED_MARGIN = 3.0;        // allow bursts up to 3x avg speed
constexpr int MAX_DISCARD_STREAK = 10;      // consecutive discards before re-lock

lv_obj_t *map_canvas;
lv_obj_t *status_label;
lv_obj_t *toggle_btn;
// 240x240 ARGB8888 is 230 KB, far too large for static RAM; allocate from
// the 8 MB PSRAM at first use instead.
uint8_t *map_buf = nullptr;                 // ARGB8888

struct TrailPoint {
    double lat;
    double lng;
    bool break_before;
};
TrailPoint *points = nullptr;
size_t point_count = 0;
size_t point_capacity = 0;

bool recording = false;
double origin_lat = 0.0;
double origin_lng = 0.0;
double meters_per_deg_lat = 0.0;
double meters_per_deg_lng = 0.0;
double last_lat = 0.0;                      // last fix that passed the gate
double last_lng = 0.0;
bool has_last = false;
uint32_t last_accepted_at = 0;              // millis() of that fix
double trail_length_m = 0.0;                // accepted movement, for avg speed
uint32_t trail_started_at = 0;
int discard_streak = 0;                     // consecutive fixes rejected by the gate
uint32_t last_fix_commit = 0;               // millis() of the last fix used
uint32_t fresh_fix_after = 0;
bool waiting_for_fresh_fix = false;
bool rebase_after_pause = false;
TrailViewport viewport;
bool tap_pending = false;
uint32_t tap_time = 0;
lv_point_t tap_point = {};
lv_point_t press_point = {};
lv_point_t last_touch = {};
bool dragging_map = false;

// Same genuine-fix rule as the trip companion: a real position plus a
// plausible date (rejects the all-zero pre-fix NMEA).
bool genuine_fix()
{
    return instance.gps.location.isValid()
           && instance.gps.date.isValid()
           && instance.gps.date.year() >= 2025;
}

void set_origin(double lat, double lng)
{
    origin_lat = lat;
    origin_lng = lng;
    // Equirectangular meters-per-degree at the origin latitude.
    meters_per_deg_lat = 111320.0;
    meters_per_deg_lng = 111320.0 * cos(lat * 3.14159265 / 180.0);
}

void latlng_to_meters(double lat, double lng, float &x, float &y)
{
    x = static_cast<float>((lng - origin_lng) * meters_per_deg_lng);
    y = static_cast<float>((lat - origin_lat) * meters_per_deg_lat);
}

bool add_point(double lat, double lng, bool break_before = false)
{
    if (point_count == point_capacity) {
        size_t new_capacity = point_capacity ? point_capacity * 2 : INITIAL_POINT_CAPACITY;
        if (new_capacity < point_capacity || new_capacity > SIZE_MAX / sizeof(TrailPoint)) {
            Serial.println("Trail: point capacity overflow");
            return false;
        }
        TrailPoint *grown = static_cast<TrailPoint *>(
            heap_caps_realloc(points, new_capacity * sizeof(TrailPoint),
                              MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));
        if (!grown) {
            Serial.println("Trail: insufficient PSRAM for another GPS fix");
            return false;
        }
        points = grown;
        point_capacity = new_capacity;
    }
    points[point_count++] = {lat, lng, break_before};
    return true;
}

void fit_trail()
{
    float max_x = 0.0f, max_y = 0.0f;
    for (size_t i = 0; i < point_count; ++i) {
        float x, y;
        latlng_to_meters(points[i].lat, points[i].lng, x, y);
        max_x = fmaxf(max_x, fabsf(x));
        max_y = fmaxf(max_y, fabsf(y));
    }
    viewport.fit(max_x, max_y);
}

void draw_dot(lv_layer_t *layer, float px, float py, int32_t r, lv_color_t color)
{
    if (px < -r || px > MAP_SIZE + r || py < -r || py > MAP_SIZE + r) return;
    lv_draw_rect_dsc_t rect;
    lv_draw_rect_dsc_init(&rect);
    rect.bg_color = color;
    rect.bg_opa = LV_OPA_COVER;
    rect.border_width = 0;
    rect.radius = LV_RADIUS_CIRCLE;
    lv_area_t a = {static_cast<int32_t>(px - r), static_cast<int32_t>(py - r),
                   static_cast<int32_t>(px + r), static_cast<int32_t>(py + r)};
    lv_draw_rect(layer, &rect, &a);
}

void draw_trail()
{
    lv_canvas_fill_bg(map_canvas, lv_color_black(), LV_OPA_COVER);
    lv_layer_t layer;
    lv_canvas_init_layer(map_canvas, &layer);

    if (point_count > 0) {
        fit_trail();
        float prev_px = 0, prev_py = 0;
        for (size_t i = 0; i < point_count; ++i) {
            float x, y, px, py;
            latlng_to_meters(points[i].lat, points[i].lng, x, y);
            viewport.project(x, y, px, py);
            if (i > 0 && !points[i].break_before) {
                float x1 = prev_px, y1 = prev_py, x2 = px, y2 = py;
                if (TrailViewport::clip_line(x1, y1, x2, y2)) {
                    lv_draw_line_dsc_t line;
                    lv_draw_line_dsc_init(&line);
                    line.color = lv_color_hex(0x5AC8FA);
                    line.width = 2;
                    line.round_end = 1;
                    line.p1.x = x1;
                    line.p1.y = y1;
                    line.p2.x = x2;
                    line.p2.y = y2;
                    lv_draw_line(&layer, &line);
                }
            }
            prev_px = px;
            prev_py = py;
        }
        // Start point in green, latest point in red.
        float sx, sy;
        viewport.project(0, 0, sx, sy);
        draw_dot(&layer, sx, sy, 4, lv_color_hex(0x35C759));
        draw_dot(&layer, prev_px, prev_py, 4, lv_color_hex(0xE02020));
    }

    lv_canvas_finish_layer(map_canvas, &layer);
}

void map_event_cb(lv_event_t *event)
{
    if (point_count == 0) return;
    lv_event_code_t code = lv_event_get_code(event);
    if (code != LV_EVENT_PRESSED && code != LV_EVENT_PRESSING
            && code != LV_EVENT_LONG_PRESSED && code != LV_EVENT_SHORT_CLICKED) return;
    lv_indev_t *indev = lv_event_get_indev(event);
    if (!indev) return;
    lv_point_t point;
    lv_indev_get_point(indev, &point);
    lv_area_t area;
    lv_obj_get_coords(map_canvas, &area);
    point.x -= area.x1;
    point.y -= area.y1;

    if (code == LV_EVENT_PRESSED) {
        press_point = last_touch = point;
        dragging_map = false;
    } else if (code == LV_EVENT_PRESSING) {
        int32_t dx = point.x - press_point.x;
        int32_t dy = point.y - press_point.y;
        if (!dragging_map && (dx * dx + dy * dy > TOUCH_SLOP * TOUCH_SLOP)) {
            dragging_map = true;
            tap_pending = false;
        }
        if (dragging_map) {
            viewport.pan(point.x - last_touch.x, point.y - last_touch.y);
            last_touch = point;
            draw_trail();
        }
    } else if (code == LV_EVENT_LONG_PRESSED && !dragging_map) {
        tap_pending = false;
        viewport.reset();
        draw_trail();
    } else if (code == LV_EVENT_SHORT_CLICKED && !dragging_map) {
        uint32_t now = millis();
        int32_t dx = point.x - tap_point.x;
        int32_t dy = point.y - tap_point.y;
        if (tap_pending && now - tap_time <= DOUBLE_TAP_MS
                && dx * dx + dy * dy <= TOUCH_SLOP * TOUCH_SLOP * 4) {
            tap_pending = false;
            viewport.zoom(0.5f, point.x, point.y);
            draw_trail();
        } else {
            if (tap_pending) {
                viewport.zoom(2.0f, tap_point.x, tap_point.y);
                draw_trail();
            }
            tap_pending = true;
            tap_time = now;
            tap_point = point;
        }
    }
}

void toggle_event_cb(lv_event_t *)
{
    if (recording) {
        breadcrumb_stop();
    } else {
        breadcrumb_start();
    }
}

} // namespace

lv_obj_t *breadcrumb_create(lv_event_cb_t back_cb, lv_event_cb_t next_cb)
{
    lv_obj_t *screen = lv_obj_create(NULL);

    if (!map_buf) {
        map_buf = static_cast<uint8_t *>(
            ps_malloc(MAP_SIZE * MAP_SIZE * 4));
    }

    // Full-LCD map canvas; the status label and buttons overlay it.
    map_canvas = lv_canvas_create(screen);
    lv_obj_clear_flag(screen, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_add_flag(map_canvas, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_clear_flag(map_canvas, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_add_event_cb(map_canvas, map_event_cb, LV_EVENT_ALL, NULL);
    lv_canvas_set_buffer(map_canvas, map_buf, MAP_SIZE, MAP_SIZE,
                         LV_COLOR_FORMAT_ARGB8888);
    lv_obj_align(map_canvas, LV_ALIGN_TOP_MID, 0, 0);
    lv_canvas_fill_bg(map_canvas, lv_color_black(), LV_OPA_COVER);

    status_label = lv_label_create(screen);
    lv_label_set_text(status_label, "Trail: stopped");
    lv_obj_set_style_text_color(status_label, lv_color_white(), 0);
    lv_obj_align(status_label, LV_ALIGN_TOP_MID, 0, 4);

    // START/STOP and navigation share a row; the shared pause button is below.
    lv_obj_t *back_btn = lv_button_create(screen);
    lv_obj_set_size(back_btn, 44, 34);
    lv_obj_align(back_btn, LV_ALIGN_BOTTOM_LEFT, 4, -40);
    lv_obj_add_event_cb(back_btn, back_cb, LV_EVENT_CLICKED, NULL);
    lv_obj_t *back_label = lv_label_create(back_btn);
    lv_label_set_text(back_label, LV_SYMBOL_LEFT);
    lv_obj_center(back_label);

    toggle_btn = lv_button_create(screen);
    lv_obj_set_size(toggle_btn, 100, 34);
    lv_obj_align(toggle_btn, LV_ALIGN_BOTTOM_MID, 0, -40);
    lv_obj_add_event_cb(toggle_btn, toggle_event_cb, LV_EVENT_CLICKED, NULL);
    lv_obj_t *toggle_label = lv_label_create(toggle_btn);
    lv_label_set_text(toggle_label, "START");
    lv_obj_center(toggle_label);

    // Right-pointing button to the compass (3rd GPS) screen
    lv_obj_t *next_btn = lv_button_create(screen);
    lv_obj_set_size(next_btn, 44, 34);
    lv_obj_align(next_btn, LV_ALIGN_BOTTOM_RIGHT, -4, -40);
    lv_obj_add_event_cb(next_btn, next_cb, LV_EVENT_CLICKED, NULL);
    lv_obj_t *next_label = lv_label_create(next_btn);
    lv_label_set_text(next_label, LV_SYMBOL_RIGHT);
    lv_obj_center(next_label);

    trip_create_pause_button(screen);

    return screen;
}

void breadcrumb_start()
{
    recording = true;
    point_count = 0;
    viewport.reset();
    tap_pending = false;
    has_last = false;
    trail_length_m = 0.0;
    discard_streak = 0;
    last_fix_commit = 0;
    fresh_fix_after = millis();
    waiting_for_fresh_fix = true;
    rebase_after_pause = false;
    lv_label_set_text(lv_obj_get_child(toggle_btn, 0), "STOP");
    lv_label_set_text(status_label, trip_paused() ? "Trail: paused" : "Trail: wait for fix");
    if (!trip_paused()) {
        trip_gps_set_power(true);
    }
    trip_refresh_pause_buttons();
    lv_canvas_fill_bg(map_canvas, lv_color_black(), LV_OPA_COVER);
    Serial.println("Breadcrumb trail started");
}

void breadcrumb_stop()
{
    recording = false;
    lv_label_set_text(lv_obj_get_child(toggle_btn, 0), "START");
    lv_label_set_text(status_label, "Trail: stopped");
    trip_refresh_pause_buttons();
    Serial.println("Breadcrumb trail stopped");
}

bool breadcrumb_active()
{
    return recording;
}

void breadcrumb_pause()
{
    if (recording) {
        lv_label_set_text(status_label, "Trail: paused");
    }
}

void breadcrumb_resume(uint32_t rest_ms)
{
    if (!recording) {
        return;
    }
    fresh_fix_after = millis();
    waiting_for_fresh_fix = true;
    rebase_after_pause = has_last;
    if (has_last) {
        last_accepted_at += rest_ms;
        trail_started_at += rest_ms;
    }
    lv_label_set_text(status_label, "Trail: wait for fix");
}

void breadcrumb_update()
{
    if (tap_pending && millis() - tap_time > DOUBLE_TAP_MS) {
        tap_pending = false;
        viewport.zoom(2.0f, tap_point.x, tap_point.y);
        draw_trail();
    }
    if (!recording || trip_paused() || !trip_gps_powered() || !genuine_fix()
            || instance.gps.location.age() >= 3000) {
        return;
    }
    // Detect a new fix by its commit time (millis() - age()), not isUpdated():
    // the trip companion reads the position first and clears that flag.
    uint32_t commit = millis() - instance.gps.location.age();
    if (waiting_for_fresh_fix && static_cast<int32_t>(commit - fresh_fix_after) < 0) {
        return;
    }
    if (commit == last_fix_commit) {
        return;                             // same fix as last loop iteration
    }
    last_fix_commit = commit;
    waiting_for_fresh_fix = false;

    double lat = instance.gps.location.lat();
    double lng = instance.gps.location.lng();
    uint32_t now = millis();

    if (!has_last) {
        set_origin(lat, lng);
        if (!add_point(lat, lng)) {
            breadcrumb_stop();
            lv_label_set_text(status_label, "Trail: memory full");
            return;
        }
        last_lat = lat;
        last_lng = lng;
        last_accepted_at = now;
        trail_started_at = now;
        has_last = true;
        lv_label_set_text(status_label, "Trail: recording");
        draw_trail();
        return;
    }

    // Plausibility gate: a differential distance greater than the trail's
    // average speed allows for the elapsed time can never be real movement,
    // so the fix is discarded completely — no trail point, no new reference.
    double step = TinyGPSPlus::distanceBetween(lat, lng, last_lat, last_lng);
    double dt = (now - last_accepted_at) / 1000.0;
    double elapsed = (now - trail_started_at) / 1000.0;
    double avg_speed = elapsed > 1.0 ? trail_length_m / elapsed : 0.0;
    double allowed = avg_speed * dt * SPEED_MARGIN;
    if (allowed < STEP_FLOOR_M) {
        allowed = STEP_FLOOR_M;
    }
    if (step > allowed) {
        if (discard_streak < MAX_DISCARD_STREAK) {
            ++discard_streak;
            Serial.printf("Trail fix discarded: %.0f m in %.1f s exceeds allowed %.0f m\n",
                          step, dt, allowed);
            return;
        }
        Serial.println("Trail: sustained offset, accepting fix as re-acquisition");
    }
    if (!add_point(lat, lng, rebase_after_pause)) {
        breadcrumb_stop();
        lv_label_set_text(status_label, "Trail: memory full");
        return;
    }
    if (rebase_after_pause) {
        rebase_after_pause = false;
        step = 0.0;
    }
    discard_streak = 0;
    last_lat = lat;
    last_lng = lng;
    last_accepted_at = now;
    if (step >= MIN_POINT_DIST_M) {
        trail_length_m += step;             // ignore sub-meter jitter
    }

    draw_trail();
    lv_label_set_text_fmt(status_label, "Trail: %lu pts",
                          static_cast<unsigned long>(point_count));
}
