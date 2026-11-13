/**
 * @file      main.cpp
 * @author    Otto Sponring
 * @license   MIT
 * @copyright Copyright (c) 2025 Otto Sponring
 * @date      2026-11-09
 *
 */
#include <LilyGoLib.h>
#include <LV_Helper.h>
#include <Wire.h>
#include <time.h>
#include "watch_face.h"
#include "trip_companion.h"
#include "breadcrumb.h"
#include "compass.h"

static lv_obj_t *watch_screen;
static lv_obj_t *badminton_screen;
static lv_obj_t *trip_screen;
static lv_obj_t *breadcrumb_screen;
static lv_obj_t *compass_screen;
static lv_obj_t *hit_count_label;
static lv_obj_t *hit_stat_names_label;
static lv_obj_t *elapsed_time_label;
static lv_obj_t *sensitivity_label;
#ifdef APP_LORA_CHAT
static lv_obj_t *send_count_label;
static lv_obj_t *chat_status_label;
#endif
static lv_obj_t *battery_fill;
static uint32_t last_battery_update = 0;
static int set_hit_count = 0;
static int total_hit_count = 0;
static int max_set_hit_count = 0;
static int16_t previous_x = 0;
static int16_t previous_y = 0;
static int16_t previous_z = 0;
static uint32_t next_hit_time = 0;
#ifdef APP_LORA_CHAT
static uint32_t pending_hit_count = 0;
static uint32_t next_chat_attempt = 0;
static bool chat_radio_ready = false;
#endif
static uint32_t timer_started_at = 0;
static uint32_t elapsed_time_ms = 0;
static uint32_t last_hit_at = 0;
static bool has_hit_in_set = false;
static bool counting_enabled = false;

// GPS time sync: the GPS delivers UTC, which is converted to local time
// (with daylight saving) using the POSIX TZ string below and written to the
// RTC once a valid fix arrives. US Pacific Time by default; see
// https://github.com/nayarsystems/posix_tz_db/blob/master/zones.csv
#ifndef GPS_TIMEZONE
#define GPS_TIMEZONE "PST8PDT,M3.2.0/2,M11.1.0/2"
#endif
static bool gps_time_synced = false;
// Set at boot when the battery-backed RTC already holds real time from an
// earlier sync; the GPS then stays off until a trip is started.
static bool rtc_time_trusted = false;

// While waiting for the first time sync with no sky view, give up after
// five minutes and power the GPS down; a trip start re-enables it.
constexpr uint32_t GPS_SYNC_MAX_WAIT_MS = 5UL * 60 * 1000;

// Hit detection sensitivity, set by the slider on the badminton screen.
// 1 = least sensitive (threshold 950) ... 10 = most sensitive (threshold 500).
// The default of 7 keeps the original threshold of 650.
static int sensitivity = 7;
static int32_t hit_threshold = 650;

static void update_battery_indicator()
{
    int battery_percent = instance.pmu.getBatteryPercent();
    battery_percent = constrain(battery_percent, 0, 100);
    lv_obj_set_width(battery_fill, max(1, (20 * battery_percent + 99) / 100));
}

static void scan_i2c_bus(TwoWire &bus, const char *name, int sda, int scl)
{
    Serial.printf("I2C scan %s: SDA=%d SCL=%d\n", name, sda, scl);
    bus.begin(sda, scl);
    for (uint8_t address = 1; address < 127; ++address) {
        bus.beginTransmission(address);
        if (bus.endTransmission() == 0) {
            Serial.printf("  device: 0x%02X\n", address);
        }
    }
}

// Once a valid UTC date+time arrives from the GPS, convert it to local time
// and write it to the RTC. Runs until the first successful sync. The caller
// feeds the GPS parser (instance.gps.loop()) while the GPS is powered.
static void sync_time_from_gps()
{
    if (gps_time_synced || !trip_gps_powered()) {
        return;
    }
    // Sync on valid UTC date+time, as the original code did. TinyGPSPlus
    // treats all-zero NMEA fields as valid, so before any satellite reception
    // the module reports a bogus date (e.g. 1999-11-30); the year sanity check
    // rejects that without requiring a full position fix (valid UTC time only
    // needs 1-2 satellites, far fewer than a 3D position fix).
    if (!instance.gps.date.isValid() || !instance.gps.time.isValid()
            || !instance.gps.time.isUpdated()
            || instance.gps.date.year() < 2025) {
        return;
    }

    struct tm utc = {};
    utc.tm_year = instance.gps.date.year() - 1900;
    utc.tm_mon = instance.gps.date.month() - 1;
    utc.tm_mday = instance.gps.date.day();
    utc.tm_hour = instance.gps.time.hour();
    utc.tm_min = instance.gps.time.minute();
    utc.tm_sec = instance.gps.time.second();
    time_t utc_time = mktime(&utc);         // TZ is UTC here, so this is UTC epoch

    setenv("TZ", GPS_TIMEZONE, 1);          // switch to local time zone
    tzset();
    struct tm local;
    localtime_r(&utc_time, &local);

    instance.rtc.setDateTime(RTC_DateTime(local));
    gps_time_synced = true;
    watch_face_refresh_moon();
    Serial.printf("GPS time sync: UTC %04d-%02d-%02d %02d:%02d:%02d -> local %04d-%02d-%02d %02d:%02d:%02d\n",
                  utc.tm_year + 1900, utc.tm_mon + 1, utc.tm_mday,
                  utc.tm_hour, utc.tm_min, utc.tm_sec,
                  local.tm_year + 1900, local.tm_mon + 1, local.tm_mday,
                  local.tm_hour, local.tm_min, local.tm_sec);
}

static void open_badminton_cb(lv_event_t *e)
{
    lv_screen_load_anim(badminton_screen, LV_SCR_LOAD_ANIM_MOVE_LEFT, 250, 0, false);
}

static void open_trip_cb(lv_event_t *e)
{
    lv_screen_load_anim(trip_screen, LV_SCR_LOAD_ANIM_MOVE_LEFT, 250, 0, false);
}

static void open_breadcrumb_cb(lv_event_t *e)
{
    lv_screen_load_anim(breadcrumb_screen, LV_SCR_LOAD_ANIM_MOVE_LEFT, 250, 0, false);
}

static void open_compass_cb(lv_event_t *e)
{
    lv_screen_load_anim(compass_screen, LV_SCR_LOAD_ANIM_MOVE_LEFT, 250, 0, false);
}

static void back_to_watch_cb(lv_event_t *e)
{
    lv_screen_load_anim(watch_screen, LV_SCR_LOAD_ANIM_MOVE_RIGHT, 250, 0, false);
}

static void back_to_trip_cb(lv_event_t *e)
{
    lv_screen_load_anim(trip_screen, LV_SCR_LOAD_ANIM_MOVE_RIGHT, 250, 0, false);
}

static void sensitivity_event_cb(lv_event_t *e)
{
    lv_obj_t *slider = lv_event_get_target_obj(e);
    sensitivity = lv_slider_get_value(slider);
    hit_threshold = 1000 - sensitivity * 50;
    lv_label_set_text_fmt(sensitivity_label, "Sensitivity: %d", sensitivity);
    Serial.printf("Sensitivity %d -> hit threshold %ld\n",
                  sensitivity, static_cast<long>(hit_threshold));
}

static void btn_event_cb(lv_event_t *e)
{
    lv_event_code_t code = lv_event_get_code(e);
    lv_obj_t *btn = lv_event_get_target_obj(e);
    if (code == LV_EVENT_CLICKED) {
        counting_enabled = !counting_enabled;
        lv_obj_t *label = lv_obj_get_child(btn, 0);
        lv_label_set_text(label, counting_enabled ? "STOP" : "START");
        if (counting_enabled) {
            timer_started_at = millis();
            elapsed_time_ms = 0;
            set_hit_count = 0;
            total_hit_count = 0;
            max_set_hit_count = 0;
            last_hit_at = 0;
            has_hit_in_set = false;
            lv_label_set_text(elapsed_time_label, "Elapsed: 00h:00m:00sec");
            lv_label_set_text(hit_count_label, "0\n0\n0");
#ifdef APP_LORA_CHAT
            lv_label_set_text(send_count_label, "Send: 0");
#endif
        } else {
            elapsed_time_ms = millis() - timer_started_at;
        }
        Serial.println(counting_enabled ? "Hit counting started" : "Hit counting stopped");
    }
}

#ifdef APP_LORA_CHAT
static int16_t configure_chat_radio()
{
    instance.lockSPI();
    int16_t state = radio.standby();
    if (state == RADIOLIB_ERR_NONE) state = radio.setFrequency(915.0);
    if (state == RADIOLIB_ERR_NONE) state = radio.setBandwidth(125.0);
    if (state == RADIOLIB_ERR_NONE) state = radio.setSpreadingFactor(7);
    if (state == RADIOLIB_ERR_NONE) state = radio.setCodingRate(5);
    if (state == RADIOLIB_ERR_NONE) state = radio.setSyncWord(0xCD);
    if (state == RADIOLIB_ERR_NONE) state = radio.setOutputPower(22);
    instance.unlockSPI();
    return state;
}

static void send_pending_hit_count()
{
    if (!chat_radio_ready || pending_hit_count == 0 || millis() < next_chat_attempt) {
        return;
    }

    char payload[32];
    snprintf(payload, sizeof(payload), "Hit count: %lu",
             static_cast<unsigned long>(pending_hit_count));
    instance.lockSPI();
    int16_t state = radio.transmit(reinterpret_cast<const uint8_t *>(payload), strlen(payload));
    instance.unlockSPI();
    if (state == RADIOLIB_ERR_NONE) {
        Serial.printf("Hit count sent to LoRa Chat: %lu\n", pending_hit_count);
        pending_hit_count = 0;
        lv_label_set_text(chat_status_label, "LoRa: sent");
        next_chat_attempt = millis() + 1000;
    } else {
        Serial.printf("LoRa Chat send failed: %d\n", state);
        lv_label_set_text(chat_status_label, "LoRa: failed");
        next_chat_attempt = millis() + 10000;
    }
}
#endif // APP_LORA_CHAT

static void create_badminton_screen()
{
    badminton_screen = lv_obj_create(NULL);

    hit_count_label = lv_label_create(badminton_screen);
    hit_stat_names_label = lv_label_create(badminton_screen);
    lv_label_set_text(hit_stat_names_label, "Hits/set:\nTotal hits:\nMax hits/set:");
    lv_obj_set_style_text_font(hit_stat_names_label, &lv_font_montserrat_24, 0);
    lv_obj_set_width(hit_stat_names_label, 155);
    lv_obj_align(hit_stat_names_label, LV_ALIGN_TOP_LEFT, 10, 10);

    lv_label_set_text(hit_count_label, "0\n0\n0");
    lv_obj_set_style_text_font(hit_count_label, &lv_font_montserrat_24, 0);
    lv_obj_set_style_text_align(hit_count_label, LV_TEXT_ALIGN_RIGHT, 0);
    lv_obj_set_width(hit_count_label, 55);
    lv_obj_align(hit_count_label, LV_ALIGN_TOP_MID, 70, 10);

    elapsed_time_label = lv_label_create(badminton_screen);
    lv_label_set_text(elapsed_time_label, "Elapsed: 00h:00m:00sec");
    lv_obj_align(elapsed_time_label, LV_ALIGN_TOP_MID, 0, 98);

#ifdef APP_LORA_CHAT
    send_count_label = lv_label_create(badminton_screen);
    lv_label_set_text(send_count_label, "Send: 0");
    lv_obj_align(send_count_label, LV_ALIGN_TOP_RIGHT, -6, 54);

    chat_status_label = lv_label_create(badminton_screen);
    lv_label_set_text(chat_status_label, "LoRa: setup...");
    lv_obj_align(chat_status_label, LV_ALIGN_BOTTOM_MID, 0, -8);
#endif

    lv_obj_t *battery_body = lv_obj_create(badminton_screen);
    lv_obj_set_size(battery_body, 26, 14);
    lv_obj_align(battery_body, LV_ALIGN_BOTTOM_RIGHT, -8, -8);
    lv_obj_set_style_pad_all(battery_body, 0, 0);
    lv_obj_set_style_bg_opa(battery_body, LV_OPA_TRANSP, 0);
    lv_obj_set_style_border_width(battery_body, 2, 0);
    lv_obj_set_style_border_color(battery_body, lv_color_hex(0x35C759), 0);
    lv_obj_set_style_radius(battery_body, 2, 0);
    lv_obj_clear_flag(battery_body, LV_OBJ_FLAG_SCROLLABLE);

    battery_fill = lv_obj_create(battery_body);
    lv_obj_set_size(battery_fill, 20, 8);
    lv_obj_align(battery_fill, LV_ALIGN_LEFT_MID, 2, 0);
    lv_obj_set_style_bg_color(battery_fill, lv_color_hex(0x35C759), 0);
    lv_obj_set_style_bg_opa(battery_fill, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(battery_fill, 0, 0);
    lv_obj_set_style_radius(battery_fill, 1, 0);
    lv_obj_clear_flag(battery_fill, LV_OBJ_FLAG_SCROLLABLE);

    lv_obj_t *battery_terminal = lv_obj_create(badminton_screen);
    lv_obj_set_size(battery_terminal, 3, 6);
    lv_obj_align_to(battery_terminal, battery_body, LV_ALIGN_OUT_RIGHT_MID, 0, 0);
    lv_obj_set_style_bg_color(battery_terminal, lv_color_hex(0x35C759), 0);
    lv_obj_set_style_bg_opa(battery_terminal, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(battery_terminal, 0, 0);
    lv_obj_set_style_radius(battery_terminal, 1, 0);
    lv_obj_clear_flag(battery_terminal, LV_OBJ_FLAG_SCROLLABLE);
    update_battery_indicator();

    // Back to the watch face, left of the START/STOP button
    lv_obj_t *back_btn = lv_button_create(badminton_screen);
    lv_obj_set_size(back_btn, 44, 38);
    lv_obj_align_to(back_btn, elapsed_time_label, LV_ALIGN_OUT_BOTTOM_LEFT, 0, 6);
    lv_obj_add_event_cb(back_btn, back_to_watch_cb, LV_EVENT_CLICKED, NULL);
    lv_obj_t *back_label = lv_label_create(back_btn);
    lv_label_set_text(back_label, LV_SYMBOL_LEFT);
    lv_obj_center(back_label);

    lv_obj_t *btn = lv_button_create(badminton_screen);            /*Add a button the current screen*/
    lv_obj_set_size(btn, 120, 38);                          /*Set its size*/
    lv_obj_add_event_cb(btn, btn_event_cb, LV_EVENT_ALL, NULL);  /*Assign a callback to the button*/
    lv_obj_align_to(btn, elapsed_time_label, LV_ALIGN_OUT_BOTTOM_RIGHT, 0, 6); /*right-aligned below the label*/

    lv_obj_t *btn_label = lv_label_create(btn);           /*Add a label to the button*/
    lv_label_set_text(btn_label, "START");
    lv_obj_center(btn_label);

    // Hit detection sensitivity slider (1-10)
    sensitivity_label = lv_label_create(badminton_screen);
    lv_label_set_text_fmt(sensitivity_label, "Sensitivity: %d", sensitivity);
#ifdef APP_LORA_CHAT
    lv_obj_align(sensitivity_label, LV_ALIGN_BOTTOM_MID, 0, -62);
#else
    lv_obj_align(sensitivity_label, LV_ALIGN_BOTTOM_MID, 0, -56);
#endif

    lv_obj_t *slider = lv_slider_create(badminton_screen);
    lv_slider_set_range(slider, 1, 10);
    lv_slider_set_value(slider, sensitivity, LV_ANIM_OFF);
    lv_obj_set_width(slider, 150);
#ifdef APP_LORA_CHAT
    lv_obj_align(slider, LV_ALIGN_BOTTOM_MID, 0, -40);
#else
    lv_obj_align(slider, LV_ALIGN_BOTTOM_MID, 0, -32);
#endif
    lv_obj_add_event_cb(slider, sensitivity_event_cb, LV_EVENT_VALUE_CHANGED, NULL);
}

void setup()
{
    // To enable serial print output, you need to enable USB output settings
    // Arduino IDE-> Tools -> USB CDC On Boot -> Enabled
    Serial.begin(115200);

    scan_i2c_bus(Wire, "main", SDA, SCL);
    scan_i2c_bus(Wire1, "touch", TP_SDA, TP_SCL);

    // Initialize the LilyGoLib instance (GPS enabled for time sync)
#ifdef APP_LORA_CHAT
    instance.begin();
#else
    instance.begin(NO_HW_LORA);
#endif

    // GPS NMEA time is UTC; keep TZ at UTC until the first sync applies the
    // local time zone.
    setenv("TZ", "UTC0", 1);
    tzset();

    // A previous firmware bug could write the GPS module's pre-fix default
    // date (1999-11-30) into the RTC. If the RTC holds such a bogus date,
    // reset it to a sensible placeholder until the next GPS sync corrects it.
    RTC_DateTime rtc_now = instance.rtc.getDateTime();
    if (rtc_now.getYear() < 2025) {
        instance.rtc.setDateTime(RTC_DateTime(2025, 1, 1, 12, 0, 0));
        Serial.println("RTC held a bogus date, reset to 2025-01-01 12:00");
    } else if (rtc_now.getYear() > 2025 || rtc_now.getMonth() > 1
               || rtc_now.getDay() > 1) {
        // RTC holds real time from an earlier sync (anything but the
        // 2025-01-01 placeholder); skip the GPS time sync this boot.
        rtc_time_trusted = true;
    }

    instance.sensor.configAccelerometer();
    instance.sensor.enableAccelerometer();

    // Call lvgl initialization
    beginLvglHelper(instance);

    watch_screen = watch_face_create(open_badminton_cb, open_trip_cb);
    create_badminton_screen();
    trip_screen = trip_companion_create(back_to_watch_cb, open_breadcrumb_cb);
    breadcrumb_screen = breadcrumb_create(back_to_trip_cb, open_compass_cb);
    compass_screen = compass_create(back_to_trip_cb);
    lv_screen_load(watch_screen);

    // Set brightness to MAX
    // T-Watch-S3 , T-Watch-S3-Plus brightness level is 0 ~ 255
    instance.setBrightness(DEVICE_MAX_BRIGHTNESS_LEVEL/2);

#ifdef APP_LORA_CHAT
    int16_t radio_state = configure_chat_radio();
    chat_radio_ready = radio_state == RADIOLIB_ERR_NONE;
    if (chat_radio_ready) {
        lv_label_set_text(chat_status_label, "LoRa: ready");
    } else {
        lv_label_set_text_fmt(chat_status_label, "LoRa err: %d", radio_state);
        Serial.printf("LoRa Chat radio setup failed: %d\n", radio_state);
    }
#endif
    lv_timer_handler();
}


void loop()
{
    int16_t x;
    int16_t y;
    int16_t z;

    instance.loop();
    instance.sensor.getAccelerometer(x, y, z);
    uint32_t now = millis();
    if (trip_gps_powered()) {
        instance.gps.loop();
    }
    sync_time_from_gps();
    trip_companion_update();
    breadcrumb_update();
    compass_update();

    // GPS power policy while idle: keep it on until the clock has synced (or
    // the RTC was already trustworthy at boot), with a five-minute ceiling for
    // the no-signal case. While the trip companion or the breadcrumb trail is
    // recording and not paused, that feature owns the GPS and keeps it
    // powered continuously for live 1 Hz fixes. Pausing powers it down; the
    // next fix after resuming may require a cold start (~30 s).
    bool gps_in_use = !trip_paused()
                      && (trip_companion_tracking() || breadcrumb_active());
    if (!gps_in_use && trip_gps_powered()
            && (trip_paused() || gps_time_synced || rtc_time_trusted
                || now >= GPS_SYNC_MAX_WAIT_MS)) {
        trip_gps_set_power(false);
    }

    int32_t delta = abs(static_cast<int32_t>(x) - previous_x)
                    + abs(static_cast<int32_t>(y) - previous_y)
                    + abs(static_cast<int32_t>(z) - previous_z);
    if (now - last_battery_update >= 5000) {
        update_battery_indicator();
        last_battery_update = now;
    }
    if (counting_enabled) {
        elapsed_time_ms = now - timer_started_at;
        uint32_t elapsed_seconds = elapsed_time_ms / 1000;
        uint32_t hours = elapsed_seconds / 3600;
        uint32_t minutes = (elapsed_seconds % 3600) / 60;
        uint32_t seconds = elapsed_seconds % 60;
        lv_label_set_text_fmt(elapsed_time_label, "Elapsed: %02luh:%02lum:%02lusec",
                              static_cast<unsigned long>(hours),
                              static_cast<unsigned long>(minutes),
                              static_cast<unsigned long>(seconds));
    }
    if (counting_enabled && delta > hit_threshold && now >= next_hit_time) {
        if (!has_hit_in_set || now - last_hit_at >= 5000) {
            set_hit_count = 0;
        }
        set_hit_count++;
        total_hit_count++;
        max_set_hit_count = max(max_set_hit_count, set_hit_count);
        last_hit_at = now;
        has_hit_in_set = true;
        next_hit_time = now + 1000;
        lv_label_set_text_fmt(hit_count_label, "%d\n%d\n%d",
                              set_hit_count, total_hit_count, max_set_hit_count);
#ifdef APP_LORA_CHAT
        pending_hit_count = total_hit_count;
        lv_label_set_text_fmt(send_count_label, "Send: %d", total_hit_count);
#endif
        Serial.printf("Hit detected: set=%d total=%d max=%d (x=%d y=%d z=%d delta=%ld)\n",
                      set_hit_count, total_hit_count, max_set_hit_count,
                      x, y, z, static_cast<long>(delta));
    }

    previous_x = x;
    previous_y = y;
    previous_z = z;

#ifdef APP_LORA_CHAT
    send_pending_hit_count();
#endif

    lv_timer_handler();
    delay(20);
}
