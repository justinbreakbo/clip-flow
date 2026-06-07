package com.clipflow.android;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class IsoClock {
    private IsoClock() {
    }

    static String now() {
        return format(new Date());
    }

    static String tomorrow() {
        return format(new Date(System.currentTimeMillis() + 24L * 60L * 60L * 1000L));
    }

    private static String format(Date date) {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        return formatter.format(date);
    }
}
