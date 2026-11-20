package com.ottospo.tripcompanion;

final class GnssSummary {
    private final int[] counts;

    GnssSummary(int constellationCount) {
        counts = new int[constellationCount];
    }

    void add(int constellation, boolean usedInFix) {
        if (!usedInFix) return;
        int index = constellation >= 0 && constellation < counts.length ? constellation : 0;
        ++counts[index];
    }

    String format(String[] names, String none) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < counts.length; ++i) {
            if (counts[i] == 0) continue;
            if (text.length() > 0) text.append(" · ");
            text.append(names[i]).append(' ').append(counts[i]);
        }
        return text.length() == 0 ? none : text.toString();
    }
}
