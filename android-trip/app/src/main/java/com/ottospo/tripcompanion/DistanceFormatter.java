package com.ottospo.tripcompanion;

import java.util.Locale;

final class DistanceFormatter {
    private static final double METERS_PER_KILOMETER = 1000.0;
    private static final double METERS_PER_TENTH_MILE = 160.9344;
    private static final double METERS_PER_MILE = 1609.344;
    private static final double METERS_PER_FOOT = 0.3048;

    private DistanceFormatter() {}

    static String formatDistance(double meters, boolean imperial) {
        if (imperial) {
            if (meters < METERS_PER_TENTH_MILE) {
                return String.format(Locale.US, "%d ft", Math.round(meters / METERS_PER_FOOT));
            }
            return String.format(Locale.US, "%.2f mi", meters / METERS_PER_MILE);
        }
        if (meters < METERS_PER_KILOMETER) {
            return String.format(Locale.US, "%d m", Math.round(meters));
        }
        return String.format(Locale.US, "%.2f km", meters / METERS_PER_KILOMETER);
    }

    static String formatPathLength(double meters, boolean imperial) {
        if (imperial) {
            if (meters < METERS_PER_TENTH_MILE) {
                return String.format(Locale.US, "%d ft", Math.round(meters / METERS_PER_FOOT));
            }
            return String.format(Locale.US, "%.1f mi", meters / METERS_PER_MILE);
        }
        if (meters < METERS_PER_KILOMETER) {
            return String.format(Locale.US, "%d m", Math.round(meters));
        }
        return String.format(Locale.US, "%.1f km", meters / METERS_PER_KILOMETER);
    }

    static String formatAltitude(double meters, boolean imperial) {
        if (imperial) {
            return String.format(Locale.US, "%d ft",
                    Math.round(meters / METERS_PER_FOOT));
        }
        return String.format(Locale.US, "%d m", Math.round(meters));
    }

    static String formatAccuracy(double meters, boolean imperial) {
        if (imperial) {
            return String.format(Locale.US, "±%dft",
                    Math.round(meters / METERS_PER_FOOT));
        }
        return String.format(Locale.US, "±%dm", Math.round(meters));
    }
}
