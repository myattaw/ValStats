package com.valstats.service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SeasonNames {

    private static final Pattern SHORT_CODE = Pattern.compile("(?i)^e(\\d+)a(\\d+)$");

    private SeasonNames() {
    }

    public static String format(String shortCode) {
        if (shortCode == null) return "";
        Matcher matcher = SHORT_CODE.matcher(shortCode.trim());
        if (!matcher.matches()) return "";
        return "Episode " + Integer.parseInt(matcher.group(1))
                + " Act " + Integer.parseInt(matcher.group(2));
    }

    public static String normalizeShortCode(String shortCode) {
        String formatted = format(shortCode);
        return formatted.isEmpty() ? "" : shortCode.trim().toLowerCase(Locale.ROOT);
    }

}
