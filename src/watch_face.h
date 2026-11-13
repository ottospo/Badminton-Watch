/**
 * @file      watch_face.h
 * @brief     Analog watch face for the T-Watch-S3 round display.
 */
#pragma once

#include <lvgl.h>

/**
 * Create the analog watch face screen, including the app launcher icons.
 * @param open_app_cb   called when the badminton icon (bottom left) is clicked
 * @param open_trip_cb  called when the world icon (bottom right) is clicked
 * @return the watch face screen object
 */
lv_obj_t *watch_face_create(lv_event_cb_t open_app_cb, lv_event_cb_t open_trip_cb);

/**
 * Redraw the moon phase icon (e.g. after the RTC date changes at midnight).
 */
void watch_face_refresh_moon();
