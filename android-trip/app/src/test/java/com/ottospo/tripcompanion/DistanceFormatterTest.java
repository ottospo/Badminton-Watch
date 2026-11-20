package com.ottospo.tripcompanion;

public final class DistanceFormatterTest {
    public static void main(String[] args) {
        assertEquals("999 m", DistanceFormatter.formatDistance(999, false));
        assertEquals("1.00 km", DistanceFormatter.formatDistance(1000, false));
        assertEquals("525 ft", DistanceFormatter.formatDistance(160, true));
        assertEquals("0.10 mi", DistanceFormatter.formatDistance(161, true));
        assertEquals("0.62 mi", DistanceFormatter.formatDistance(1000, true));

        assertEquals("1.0 km", DistanceFormatter.formatPathLength(1000, false));
        assertEquals("0.6 mi", DistanceFormatter.formatPathLength(1000, true));
        assertEquals("-100 ft", DistanceFormatter.formatAltitude(-30.48, true));
        assertEquals("±25m", DistanceFormatter.formatAccuracy(25, false));
        assertEquals("±82ft", DistanceFormatter.formatAccuracy(25, true));

        System.out.println("Distance formatter tests passed");
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected \"" + expected + "\" but got \"" + actual + "\"");
        }
    }
}
