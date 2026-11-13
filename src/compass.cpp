/**
 * @file      compass.cpp
 * @brief     Compass dial for the T-Watch-S3.
 *
 *            A large dial showing the GPS course over ground (red arrow;
 *            the watch has no magnetometer) against the bearing back to
 *            the trip's starting point (green marker): steer so they line
 *            up. The bearing/course state is read from the trip companion
 *            and the dial is redrawn only when the displayed state changes.
 */
#include <LilyGoLib.h>
#include <math.h>
#include "trip_companion.h"
#include "compass.h"

namespace {

// Compass dial (canvas): green marker = bearing back to start, red arrow =
// course over ground. Redrawn only when the displayed state changes.
constexpr int16_t COMPASS_SIZE = 200;
constexpr int16_t COMPASS_CX = COMPASS_SIZE / 2;
constexpr int16_t COMPASS_CY = COMPASS_SIZE / 2;
constexpr int16_t COMPASS_RADIUS = 90;
// 200x200 ARGB8888 is 160 KB, far too large for static RAM; allocate from
// the 8 MB PSRAM at first use instead.
uint8_t *compass_buf = nullptr;             // ARGB8888

lv_obj_t *compass_canvas;

double drawn_bearing_deg = -1.0;
double drawn_course_deg = -1.0;
bool drawn_has_start = false;
bool drawn_course_valid = false;

float to_radians(float degrees)
{
    return degrees * 3.14159265f / 180.0f;
}

// Line between two radii on the dial; 0 deg = north = up.
void draw_radial_line(lv_layer_t *layer, lv_color_t color, int32_t width,
                      float degrees, float inner_r, float outer_r)
{
    float rad = to_radians(degrees);
    lv_draw_line_dsc_t dsc;
    lv_draw_line_dsc_init(&dsc);
    dsc.color = color;
    dsc.width = width;
    dsc.round_end = 1;
    dsc.p1.x = COMPASS_CX + inner_r * sinf(rad);
    dsc.p1.y = COMPASS_CY - inner_r * cosf(rad);
    dsc.p2.x = COMPASS_CX + outer_r * sinf(rad);
    dsc.p2.y = COMPASS_CY - outer_r * cosf(rad);
    lv_draw_line(layer, &dsc);
}

void draw_arrowhead(lv_layer_t *layer, lv_color_t color, float degrees, float tip_r)
{
    float rad = to_radians(degrees);
    float tip_x = COMPASS_CX + tip_r * sinf(rad);
    float tip_y = COMPASS_CY - tip_r * cosf(rad);
    for (int side = -1; side <= 1; side += 2) {
        float back = to_radians(degrees + side * 150.0f);
        lv_draw_line_dsc_t dsc;
        lv_draw_line_dsc_init(&dsc);
        dsc.color = color;
        dsc.width = 3;
        dsc.round_end = 1;
        dsc.p1.x = tip_x;
        dsc.p1.y = tip_y;
        dsc.p2.x = tip_x + 14.0f * sinf(back);
        dsc.p2.y = tip_y - 14.0f * cosf(back);
        lv_draw_line(layer, &dsc);
    }
}

void draw_compass()
{
    drawn_bearing_deg = trip_bearing_deg();
    drawn_course_deg = trip_course_deg();
    drawn_has_start = trip_has_start();
    drawn_course_valid = trip_course_valid();

    lv_canvas_fill_bg(compass_canvas, lv_color_black(), LV_OPA_TRANSP);
    lv_layer_t layer;
    lv_canvas_init_layer(compass_canvas, &layer);

    // Dial outline
    lv_draw_rect_dsc_t rect;
    lv_draw_rect_dsc_init(&rect);
    rect.bg_opa = LV_OPA_TRANSP;
    rect.border_width = 2;
    rect.border_color = lv_color_hex(0x808080);
    rect.radius = LV_RADIUS_CIRCLE;
    lv_area_t dial = {static_cast<int32_t>(COMPASS_CX - COMPASS_RADIUS),
                      static_cast<int32_t>(COMPASS_CY - COMPASS_RADIUS),
                      static_cast<int32_t>(COMPASS_CX + COMPASS_RADIUS),
                      static_cast<int32_t>(COMPASS_CY + COMPASS_RADIUS)};
    lv_draw_rect(&layer, &rect, &dial);

    // Cardinal ticks, north emphasized
    for (int i = 0; i < 4; ++i) {
        bool north = i == 0;
        draw_radial_line(&layer, north ? lv_color_white() : lv_color_hex(0x808080),
                         north ? 4 : 3, i * 90.0f,
                         COMPASS_RADIUS - 10, COMPASS_RADIUS - 2);
    }

    // Green marker: bearing back to the starting point (full radius so it
    // stays visible next to the shorter course arrow when both align)
    if (drawn_has_start) {
        draw_radial_line(&layer, lv_color_hex(0x35C759), 4,
                         static_cast<float>(drawn_bearing_deg), 0, COMPASS_RADIUS - 5);
    }

    // Red arrow: course over ground (direction of travel)
    if (drawn_course_valid) {
        float course = static_cast<float>(drawn_course_deg);
        draw_radial_line(&layer, lv_color_hex(0xE02020), 4, course, 0, COMPASS_RADIUS - 18);
        draw_arrowhead(&layer, lv_color_hex(0xE02020), course, COMPASS_RADIUS - 18);
    }

    lv_canvas_finish_layer(compass_canvas, &layer);
}

} // namespace

lv_obj_t *compass_create(lv_event_cb_t back_cb)
{
    lv_obj_t *screen = lv_obj_create(NULL);

    if (!compass_buf) {
        compass_buf = static_cast<uint8_t *>(
            ps_malloc(COMPASS_SIZE * COMPASS_SIZE * 4));
    }

    // Large centered dial; the back button overlays the bottom-left corner.
    compass_canvas = lv_canvas_create(screen);
    lv_canvas_set_buffer(compass_canvas, compass_buf, COMPASS_SIZE, COMPASS_SIZE,
                         LV_COLOR_FORMAT_ARGB8888);
    lv_obj_center(compass_canvas);
    lv_canvas_fill_bg(compass_canvas, lv_color_black(), LV_OPA_TRANSP);

    lv_obj_t *back_btn = lv_button_create(screen);
    lv_obj_set_size(back_btn, 44, 34);
    lv_obj_align(back_btn, LV_ALIGN_BOTTOM_LEFT, 4, -2);
    lv_obj_add_event_cb(back_btn, back_cb, LV_EVENT_CLICKED, NULL);
    lv_obj_t *back_label = lv_label_create(back_btn);
    lv_label_set_text(back_label, LV_SYMBOL_LEFT);
    lv_obj_center(back_label);

    return screen;
}

void compass_update()
{
    if (trip_bearing_deg() != drawn_bearing_deg || trip_course_deg() != drawn_course_deg
            || trip_has_start() != drawn_has_start || trip_course_valid() != drawn_course_valid) {
        draw_compass();
    }
}
