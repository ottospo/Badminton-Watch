package com.ottospo.tripcompanion;

public final class GnssSummaryTest {
    public static void main(String[] args) {
        String[] names = {"Other", "GPS", "SBAS", "GLO", "QZSS", "BDS", "GAL", "NavIC"};
        GnssSummary summary = new GnssSummary(names.length);
        if (!summary.format(names, "none").equals("none")) {
            throw new AssertionError("An empty fix must not claim any contributing satellites");
        }
        summary.add(1, true);
        summary.add(1, false);
        summary.add(5, true);
        summary.add(5, true);
        summary.add(6, true);
        summary.add(99, true);
        if (!summary.format(names, "none").equals("Other 1 · GPS 1 · BDS 2 · GAL 1")) {
            throw new AssertionError("Satellite counts must be grouped by constellation");
        }
        System.out.println("GNSS summary tests passed");
    }
}
