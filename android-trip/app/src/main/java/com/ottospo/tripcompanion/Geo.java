package com.ottospo.tripcompanion;

/**
 * Great-circle helpers ported from the TinyGPSPlus functions the T-Watch-S3
 * firmware uses (src/trip_companion.cpp, src/breadcrumb.cpp), so the phone
 * and the watch compute identical distances and bearings.
 */
public final class Geo {

    // Same hypothetical-sphere radius as TinyGPSPlus::distanceBetween().
    private static final double EARTH_RADIUS_M = 6372795.0;

    private static final String[] CARDINALS = {
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    };

    private Geo() {
    }

    /** Port of TinyGPSPlus::distanceBetween(): meters between two positions. */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double delta = Math.toRadians(lng1 - lng2);
        double sdlong = Math.sin(delta);
        double cdlong = Math.cos(delta);
        lat1 = Math.toRadians(lat1);
        lat2 = Math.toRadians(lat2);
        double slat1 = Math.sin(lat1);
        double clat1 = Math.cos(lat1);
        double slat2 = Math.sin(lat2);
        double clat2 = Math.cos(lat2);
        delta = clat1 * slat2 - slat1 * clat2 * cdlong;
        delta *= delta;
        double cross = clat2 * sdlong;
        delta += cross * cross;
        delta = Math.sqrt(delta);
        double denom = slat1 * slat2 + clat1 * clat2 * cdlong;
        return Math.atan2(delta, denom) * EARTH_RADIUS_M;
    }

    /** Port of TinyGPSPlus::courseTo(): initial bearing 0..360 (North = 0). */
    public static double bearingDeg(double lat1, double lng1, double lat2, double lng2) {
        double dlon = Math.toRadians(lng2 - lng1);
        lat1 = Math.toRadians(lat1);
        lat2 = Math.toRadians(lat2);
        double a1 = Math.sin(dlon) * Math.cos(lat2);
        double a2 = Math.sin(lat1) * Math.cos(lat2) * Math.cos(dlon);
        a2 = Math.cos(lat1) * Math.sin(lat2) - a2;
        a2 = Math.atan2(a1, a2);
        if (a2 < 0.0) {
            a2 += 2.0 * Math.PI;
        }
        return Math.toDegrees(a2);
    }

    /** Port of TinyGPSPlus::cardinal(): 16-point compass point name. */
    public static String cardinal(double deg) {
        int i = (int) ((deg + 11.25) / 22.5) % 16;
        return CARDINALS[i];
    }
}
