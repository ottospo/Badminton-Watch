/**
 * @file      watch_face.cpp
 * @brief     Analog watch face for the T-Watch-S3 240x240 round display.
 *            Hour/minute/second hands are driven by the PCF8563 RTC, a
 *            badminton icon launches the hit-counter app and a world icon
 *            launches the GPS trip companion.
 */
#include <LilyGoLib.h>
#include <math.h>
#include "watch_face.h"
#include "badminton_icon.h"
#include "world_icon.h"

namespace {

constexpr int16_t CENTER_X = 120;           // dial center (shifted up so the
constexpr int16_t CENTER_Y = 112;           // badminton icon clears the 6 tick)
constexpr int16_t TICK_OUTER = 91;          // dial shrunk ~0.2" in diameter
constexpr int16_t TICK_MAJOR_INNER = 75;    // 12/3/6/9 ticks
constexpr int16_t TICK_MINOR_INNER = 82;
constexpr int16_t MIN_DOT_RADIUS = 87;      // 5-minute increment dots
constexpr int16_t HOUR_LEN = 40;
constexpr int16_t MINUTE_LEN = 78;
constexpr int16_t SECOND_LEN = 70;
constexpr int16_t SECOND_TAIL = 10;
constexpr int32_t HOUR_WIDTH = 6;
constexpr int32_t MINUTE_WIDTH = 4;
constexpr int32_t SECOND_WIDTH = 2;

lv_obj_t *watch_screen_obj;
lv_obj_t *hour_hand;
lv_obj_t *minute_hand;
lv_obj_t *second_hand;
lv_obj_t *date_label;
lv_obj_t *moon_canvas;
lv_point_precise_t hour_points[2];
lv_point_precise_t minute_points[2];
lv_point_precise_t second_points[2];
uint8_t last_second = 0xFF;
uint8_t last_date_day = 0xFF;

constexpr int16_t MOON_SIZE = 32;           // moon icon edge in pixels
constexpr int16_t MOON_RADIUS = 13;
constexpr int16_t MOON_CX = 16;             // moon center inside the canvas
constexpr int16_t MOON_CY = 16;
uint8_t moon_buf[MOON_SIZE * MOON_SIZE * 4];    // ARGB8888 canvas buffer

float to_radians(float degrees)
{
    return degrees * 3.14159265f / 180.0f;
}

// Erase the previous hand position; lv_line only invalidates the new one.
void invalidate_hand(lv_obj_t *screen, const lv_point_precise_t *points, int32_t width)
{
    lv_area_t area;
    area.x1 = static_cast<int32_t>(LV_MIN(points[0].x, points[1].x)) - width;
    area.y1 = static_cast<int32_t>(LV_MIN(points[0].y, points[1].y)) - width;
    area.x2 = static_cast<int32_t>(LV_MAX(points[0].x, points[1].x)) + width;
    area.y2 = static_cast<int32_t>(LV_MAX(points[0].y, points[1].y)) + width;
    lv_obj_invalidate_area(screen, &area);
}

void set_hand(lv_obj_t *screen, lv_obj_t *hand, lv_point_precise_t *points,
              float degrees, int16_t len, int16_t tail, int32_t width)
{
    invalidate_hand(screen, points, width);
    float rad = to_radians(degrees);
    float s = sinf(rad);
    float c = cosf(rad);
    points[0].x = CENTER_X - tail * s;
    points[0].y = CENTER_Y + tail * c;
    points[1].x = CENTER_X + len * s;
    points[1].y = CENTER_Y - len * c;
    lv_line_set_points(hand, points, 2);
}

// Days since the known new moon of 2000-01-06 18:14 UTC (JDN-based).
float moon_age_days(int year, int month, int day)
{
    if (month <= 2) {
        year -= 1;
        month += 12;
    }
    int a = year / 100;
    int b = 2 - a + a / 4;
    long jdn = static_cast<long>(365.25 * (year + 4716))
               + static_cast<long>(30.6001 * (month + 1)) + day + b - 1524;
    float age = static_cast<float>(jdn - 2451550L) - 0.24f;
    float cycle = fmodf(age, 29.53058867f);
    return cycle < 0 ? cycle + 29.53058867f : cycle;
}

void draw_moon(int year, int month, int day)
{
    float phase = moon_age_days(year, month, day) / 29.53058867f;   // 0..1
    float k = cosf(2.0f * 3.14159265f * phase);     // terminator ellipse factor
    bool waxing = phase < 0.5f;                     // lit limb on the right

    lv_canvas_fill_bg(moon_canvas, lv_color_black(), LV_OPA_TRANSP);
    lv_layer_t layer;
    lv_canvas_init_layer(moon_canvas, &layer);

    lv_draw_rect_dsc_t rect;
    lv_draw_rect_dsc_init(&rect);
    rect.bg_opa = LV_OPA_COVER;
    rect.border_width = 0;
    rect.radius = 0;

    for (int dy = -MOON_RADIUS + 1; dy <= MOON_RADIUS - 1; ++dy) {
        float half = sqrtf(static_cast<float>(MOON_RADIUS * MOON_RADIUS - dy * dy));
        float term = k * half;      // terminator x offset from the moon center
        float lit_x1;
        float lit_x2;
        if (waxing) {
            lit_x1 = term;          // lit: terminator .. right limb
            lit_x2 = half;
        } else {
            lit_x1 = -half;         // lit: left limb .. terminator
            lit_x2 = -term;
        }
        int32_t row_y = MOON_CY + dy;
        int32_t left = static_cast<int32_t>(MOON_CX + floorf(-half));
        int32_t right = static_cast<int32_t>(MOON_CX + ceilf(half) - 1);
        int32_t lx1 = static_cast<int32_t>(MOON_CX + floorf(lit_x1));
        int32_t lx2 = static_cast<int32_t>(MOON_CX + ceilf(lit_x2) - 1);

        // Dark disc so the unlit part (and a new/full moon) reads on black
        rect.bg_color = lv_color_hex(0x2A2A2A);
        lv_area_t dark = {left, row_y, right, row_y};
        lv_draw_rect(&layer, &rect, &dark);

        // Lit portion
        if (lx2 >= lx1) {
            rect.bg_color = lv_color_hex(0xF0F0D8);
            lv_area_t lit = {lx1, row_y, lx2, row_y};
            lv_draw_rect(&layer, &rect, &lit);
        }
    }

    lv_canvas_finish_layer(moon_canvas, &layer);
}

void update_date_and_moon()
{
    RTC_DateTime now = instance.rtc.getDateTime();
    if (now.getDay() == last_date_day) {
        return;
    }
    last_date_day = now.getDay();
    lv_label_set_text_fmt(date_label, "%02d.%02d",
                          now.getDay(), now.getMonth());
    draw_moon(now.getYear(), now.getMonth(), now.getDay());
}

void update_hands(lv_timer_t *)
{
    // Skip while another screen is showing; screen_load_cb refreshes on return.
    if (lv_screen_active() != watch_screen_obj) {
        return;
    }
    RTC_DateTime now = instance.rtc.getDateTime();
    uint8_t hour = now.getHour();
    uint8_t minute = now.getMinute();
    uint8_t second = now.getSecond();
    if (second == last_second) {
        return;
    }
    last_second = second;
    set_hand(watch_screen_obj, hour_hand, hour_points,
             (hour % 12) * 30.0f + minute * 0.5f, HOUR_LEN, 0, HOUR_WIDTH);
    set_hand(watch_screen_obj, minute_hand, minute_points,
             minute * 6.0f + second * 0.1f, MINUTE_LEN, 0, MINUTE_WIDTH);
    set_hand(watch_screen_obj, second_hand, second_points,
             second * 6.0f, SECOND_LEN, SECOND_TAIL, SECOND_WIDTH);
    update_date_and_moon();
}

void screen_load_cb(lv_event_t *)
{
    last_second = 0xFF;
    last_date_day = 0xFF;
    update_hands(NULL);
    update_date_and_moon();
}

lv_obj_t *create_hand(lv_obj_t *parent, lv_point_precise_t *points,
                      lv_color_t color, int32_t width)
{
    lv_obj_t *hand = lv_line_create(parent);
    lv_line_set_points(hand, points, 2);
    lv_obj_set_pos(hand, 0, 0);     // points are absolute screen coordinates
    lv_obj_set_style_line_color(hand, color, 0);
    lv_obj_set_style_line_width(hand, width, 0);
    lv_obj_set_style_line_rounded(hand, true, 0);
    return hand;
}

void create_tick(lv_obj_t *parent, int index)
{
    bool major = (index % 3) == 0;
    float rad = to_radians(index * 30.0f);
    float s = sinf(rad);
    float c = cosf(rad);
    int16_t inner = major ? TICK_MAJOR_INNER : TICK_MINOR_INNER;
    lv_point_precise_t points[2] = {
        {static_cast<float>(CENTER_X + inner * s), static_cast<float>(CENTER_Y - inner * c)},
        {static_cast<float>(CENTER_X + TICK_OUTER * s), static_cast<float>(CENTER_Y - TICK_OUTER * c)},
    };
    lv_obj_t *tick = lv_line_create(parent);
    lv_line_set_points(tick, points, 2);
    lv_obj_set_pos(tick, 0, 0);
    lv_obj_set_style_line_color(tick, major ? lv_color_white() : lv_color_hex(0x808080), 0);
    lv_obj_set_style_line_width(tick, major ? 4 : 2, 0);
    lv_obj_set_style_line_rounded(tick, true, 0);
}

lv_obj_t *create_center_cap(lv_obj_t *parent, int16_t size, lv_color_t color)
{
    lv_obj_t *cap = lv_obj_create(parent);
    lv_obj_set_size(cap, size, size);
    lv_obj_set_pos(cap, CENTER_X - size / 2, CENTER_Y - size / 2);
    lv_obj_set_style_radius(cap, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_bg_color(cap, color, 0);
    lv_obj_set_style_bg_opa(cap, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(cap, 0, 0);
    lv_obj_set_style_pad_all(cap, 0, 0);
    lv_obj_clear_flag(cap, LV_OBJ_FLAG_SCROLLABLE);
    return cap;
}

// A single dot at each hour position (12, 1, 2, ... 11).
void create_hour_dot(lv_obj_t *parent, int index)
{
    float r = to_radians(index * 30.0f);
    int16_t x = CENTER_X + static_cast<int16_t>(MIN_DOT_RADIUS * sinf(r));
    int16_t y = CENTER_Y - static_cast<int16_t>(MIN_DOT_RADIUS * cosf(r));
    lv_obj_t *dot = lv_obj_create(parent);
    lv_obj_set_size(dot, 5, 5);
    lv_obj_set_pos(dot, x - 2, y - 2);
    lv_obj_set_style_radius(dot, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_bg_color(dot, lv_color_hex(0xA0A0A0), 0);
    lv_obj_set_style_bg_opa(dot, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(dot, 0, 0);
    lv_obj_set_style_pad_all(dot, 0, 0);
    lv_obj_clear_flag(dot, LV_OBJ_FLAG_SCROLLABLE);
}

} // namespace

lv_obj_t *watch_face_create(lv_event_cb_t open_app_cb, lv_event_cb_t open_trip_cb)
{
    lv_obj_t *screen = lv_obj_create(NULL);
    watch_screen_obj = screen;
    lv_obj_set_style_bg_color(screen, lv_color_black(), 0);
    lv_obj_set_style_bg_opa(screen, LV_OPA_COVER, 0);
    lv_obj_clear_flag(screen, LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_add_event_cb(screen, screen_load_cb, LV_EVENT_SCREEN_LOADED, NULL);

    for (int i = 0; i < 12; ++i) {
        create_tick(screen, i);
        create_hour_dot(screen, i);
    }

    // Date at the top left, moon phase at the top of the dial
    date_label = lv_label_create(screen);
    lv_label_set_text(date_label, "--.--");
    lv_obj_set_style_text_font(date_label, &lv_font_montserrat_16, 0);
    lv_obj_set_style_text_color(date_label, lv_color_hex(0xC0C0C0), 0);
    lv_obj_align(date_label, LV_ALIGN_TOP_LEFT, 16, 8);

    moon_canvas = lv_canvas_create(screen);
    lv_canvas_set_buffer(moon_canvas, moon_buf, MOON_SIZE, MOON_SIZE,
                         LV_COLOR_FORMAT_ARGB8888);
    lv_obj_align(moon_canvas, LV_ALIGN_TOP_RIGHT, -16, 6);

    hour_hand = create_hand(screen, hour_points, lv_color_white(), HOUR_WIDTH);
    minute_hand = create_hand(screen, minute_points, lv_color_white(), MINUTE_WIDTH);
    second_hand = create_hand(screen, second_points, lv_color_hex(0xE02020), SECOND_WIDTH);
    create_center_cap(screen, 12, lv_color_white());
    create_center_cap(screen, 5, lv_color_hex(0xE02020));

    // Badminton app launcher icon, bottom left (T70 launcher icon, black
    // corners blend into the black watch face background).
    lv_obj_t *icon = lv_image_create(screen);
    lv_image_set_src(icon, &badminton_icon);
    lv_obj_align(icon, LV_ALIGN_BOTTOM_LEFT, 14, -14);
    lv_obj_add_flag(icon, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_set_style_image_recolor(icon, lv_color_hex(0x808080), LV_STATE_PRESSED);
    lv_obj_set_style_image_recolor_opa(icon, LV_OPA_40, LV_STATE_PRESSED);
    lv_obj_add_event_cb(icon, open_app_cb, LV_EVENT_CLICKED, NULL);

    // Trip companion launcher icon, bottom right.
    lv_obj_t *trip_icon = lv_image_create(screen);
    lv_image_set_src(trip_icon, &world_icon);
    lv_obj_align(trip_icon, LV_ALIGN_BOTTOM_RIGHT, -14, -14);
    lv_obj_add_flag(trip_icon, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_set_style_image_recolor(trip_icon, lv_color_hex(0x808080), LV_STATE_PRESSED);
    lv_obj_set_style_image_recolor_opa(trip_icon, LV_OPA_40, LV_STATE_PRESSED);
    lv_obj_add_event_cb(trip_icon, open_trip_cb, LV_EVENT_CLICKED, NULL);

    last_second = 0xFF;
    last_date_day = 0xFF;
    lv_timer_create(update_hands, 200, NULL);
    return screen;
}

void watch_face_refresh_moon()
{
    last_date_day = 0xFF;
    update_date_and_moon();
}
