/**
 * @file      trip_companion.h
 * @brief     GPS trip companion: tracks the straight-line distance and
 *            bearing back to the starting point, plus the elapsed trip
 *            time, altitude, and total path length. The GPS runs
 *            continuously (1 Hz fixes) while tracking and powers down when
 *            both GPS features are paused or stopped.
 */
#pragma once

#include <lvgl.h>

/**
 * Create the trip companion screen.
 * @param back_cb  called when the back button is clicked
 * @param map_cb   called when the breadcrumb map button is clicked
 * @return the trip companion screen object
 */
lv_obj_t *trip_companion_create(lv_event_cb_t back_cb, lv_event_cb_t map_cb);

/**
 * Run the trip tracker state machine and refresh the readouts.
 * Call from the main loop.
 */
void trip_companion_update();

/**
 * @return true while a trip session is active, including while paused
 */
bool trip_companion_tracking();

/**
 * The pause control is shared by the trip and breadcrumb screens. Pausing
 * preserves both sessions, excludes rest time, and powers down the GPS.
 */
lv_obj_t *trip_create_pause_button(lv_obj_t *parent);
void trip_refresh_pause_buttons();
bool trip_paused();

/**
 * Power the GPS module on/off. The GPS starts powered at boot for the
 * initial time sync; trip_gps_set_power() is idempotent.
 */
void trip_gps_set_power(bool on);

/**
 * @return true while the GPS module is powered
 */
bool trip_gps_powered();

/**
 * Trip state accessors for the compass face: bearing back to the starting
 * point, course over ground, and their validity flags.
 */
double trip_bearing_deg();
double trip_course_deg();
bool trip_course_valid();
bool trip_has_start();
