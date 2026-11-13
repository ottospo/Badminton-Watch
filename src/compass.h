/**
 * @file      compass.h
 * @brief     Compass dial: shows the GPS course over ground (red arrow; the
 *            watch has no magnetometer) against the bearing back to the
 *            trip's starting point (green marker): steer so they line up.
 *            The bearing/course state is read from the trip companion.
 */
#pragma once

#include <lvgl.h>

/**
 * Create the compass screen.
 * @param back_cb  called when the back button is clicked
 * @return the compass screen object
 */
lv_obj_t *compass_create(lv_event_cb_t back_cb);

/**
 * Redraw the compass dial when the displayed state changes.
 * Call from the main loop.
 */
void compass_update();
