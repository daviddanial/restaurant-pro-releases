package com.telerad.order;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Pure matching rules (KB note 15 §4, §5); no database access, so they are easy to test. */
public final class OrderMatcher {
    private OrderMatcher() {}

    /** Patient IDs compare case-insensitively, ignoring surrounding spaces and leading zeros. */
    public static boolean samePatientId(String a, String b) {
        String na = normaliseId(a), nb = normaliseId(b);
        return na != null && na.equals(nb);
    }

    static String normaliseId(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        if (s.isEmpty()) return null;
        String stripped = s.replaceFirst("^0+(?=.)", "");
        return stripped;
    }

    /**
     * Names compare as a set of words, so "ABEBE^KEBEDE" (DICOM) equals "Kebede Abebe" (reception).
     * Case, accents, punctuation and DICOM separators are ignored.
     *
     * @return true when the scanner name is blank (the MRT did not type it), or the words match
     */
    public static boolean nameMatches(String orderName, String scannerName) {
        String s = nameKey(scannerName);
        if (s.isEmpty()) return true;
        return s.equals(nameKey(orderName));
    }

    static String nameKey(String raw) {
        if (raw == null) return "";
        String s = Normalizer.normalize(raw, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = s.toUpperCase(Locale.ROOT).replaceAll("[\\^,.\\-_'=]", " ").trim();
        if (s.isEmpty()) return "";
        return Arrays.stream(s.split("\\s+")).sorted().collect(Collectors.joining(" "));
    }

    /** DICOM PN "LAST^FIRST^MIDDLE" for display; other text is returned as is. */
    public static String displayName(String dicomName) {
        if (dicomName == null) return null;
        if (!dicomName.contains("^")) return dicomName.trim();
        String[] p = dicomName.split("\\^", -1);
        String last = p.length > 0 ? p[0].trim() : "";
        String first = p.length > 1 ? p[1].trim() : "";
        String middle = p.length > 2 ? p[2].trim() : "";
        return String.join(" ", Arrays.stream(new String[]{first, middle, last}).filter(x -> !x.isEmpty()).toList());
    }
}
