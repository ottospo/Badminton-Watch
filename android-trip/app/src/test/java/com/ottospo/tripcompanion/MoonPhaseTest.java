package com.ottospo.tripcompanion;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

public final class MoonPhaseTest {
    public static void main(String[] args) throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        check(format, "2024-04-08 18:21", 0);
        check(format, "2024-04-15 19:13", 2);
        check(format, "2024-03-25 07:00", 4);
        check(format, "2024-04-02 03:15", 6);
        check(format, "2000-01-05 18:14", 0);
        System.out.println("Moon phase tests passed");
    }

    private static void check(SimpleDateFormat format, String date, int expected) throws Exception {
        int actual = MoonPhase.index(format.parse(date).getTime());
        if (actual != expected) {
            throw new AssertionError(date + ": expected " + expected + ", got " + actual);
        }
    }
}
