/**
 * @file      breadcrumb.h
 * @brief     Breadcrumb trail map: plots the GPS track as connected vectors
 *            on the LCD, initially centered on the start and automatically
 *            zoomed to fit the whole trail. Tap to zoom in, double-tap to
 *            zoom out, drag to pan, and long-press to restore automatic fit.
 *            Accepted coordinates remain in PSRAM until a new trail starts
 *            or the watch reboots; recording stops if allocation fails.
 */
#pragma once

#include <lvgl.h>

/**
 * Create the breadcrumb trail screen.
 * @param back_cb  called when the back button is clicked
 * @param next_cb  called when the compass (right arrow) button is clicked
 * @return the breadcrumb screen object
 */
lv_obj_t *breadcrumb_create(lv_event_cb_t back_cb, lv_event_cb_t next_cb);

/**
 * Record a position fix and redraw the trail. Call from the main loop.
 */
void breadcrumb_update();

/**
 * Start recording a new trail (clears any previous points).
 */
void breadcrumb_start();

/**
 * Stop recording (the trail stays on screen).
 */
void breadcrumb_stop();

/**
 * @return true while the breadcrumb trail is recording
 */
bool breadcrumb_active();

/**
 * Reacquire the position after a shared GPS pause without joining the gap.
 */
void breadcrumb_pause();
void breadcrumb_resume(uint32_t rest_ms);
