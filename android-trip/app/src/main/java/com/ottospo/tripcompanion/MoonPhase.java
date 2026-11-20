package com.ottospo.tripcompanion;

final class MoonPhase {
    // Mean lunar cycle measured from the new moon on 6 January 2000, 18:14 UTC.
    private static final long REFERENCE_NEW_MOON_MS = 947182440000L;
    private static final double SYNODIC_MONTH_MS = 29.530588853 * 24 * 60 * 60 * 1000;

    static int index(long timeMs) {
        double cycles = (timeMs - REFERENCE_NEW_MOON_MS) / SYNODIC_MONTH_MS;
        double fraction = cycles - Math.floor(cycles);
        return ((int) Math.floor(fraction * 8 + 0.5)) % 8;
    }

    private MoonPhase() {}
}
