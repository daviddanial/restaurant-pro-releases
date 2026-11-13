package com.telerad.images;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telerad.common.StudyPriority;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;

/**
 * A facility's rules for sending studies to the cloud automatically, stored as JSON on the tenant:
 * <pre>[{"modality":"CT","fromHour":18,"toHour":8}, {"priority":"STAT"}]</pre>
 * A study matches when any rule matches; a rule matches when all its fields match. Hours are the
 * facility's local time; fromHour 18 / toHour 8 means 18:00–07:59 (wrapping past midnight).
 */
public final class AutoSendRules {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);

    public record Rule(String modality, String priority, Integer fromHour, Integer toHour) {}

    private AutoSendRules() {}

    public static List<Rule> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Rule> rules = MAPPER.readValue(json, new TypeReference<List<Rule>>() {});
            return rules == null ? List.of() : rules;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid automatic send rules: " + e.getMessage());
        }
    }

    /** Validates and normalises rules typed by an admin; returns the JSON to store (null when empty). */
    public static String normalise(String json) {
        List<Rule> rules = parse(json);
        for (Rule r : rules) {
            if (r == null) throw new IllegalArgumentException("Empty rule");
            checkHour(r.fromHour());
            checkHour(r.toHour());
            if ((r.fromHour() == null) != (r.toHour() == null)) {
                throw new IllegalArgumentException("fromHour and toHour go together");
            }
            if (r.priority() != null) StudyPriority.valueOf(r.priority().toUpperCase(Locale.ROOT));
        }
        if (rules.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(rules);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean matches(List<Rule> rules, String modality, StudyPriority priority, ZonedDateTime at) {
        for (Rule r : rules) {
            if (r != null && matches(r, modality, priority, at)) return true;
        }
        return false;
    }

    static boolean matches(Rule r, String modality, StudyPriority priority, ZonedDateTime at) {
        if (r.modality() != null && !r.modality().isBlank()
                && (modality == null || !r.modality().trim().equalsIgnoreCase(modality.trim()))) {
            return false;
        }
        if (r.priority() != null && !r.priority().isBlank()
                && (priority == null || !r.priority().trim().equalsIgnoreCase(priority.name()))) {
            return false;
        }
        if (r.fromHour() != null && r.toHour() != null) {
            int h = at.getHour();
            int from = r.fromHour(), to = r.toHour();
            boolean inside = from <= to ? (h >= from && h < to) : (h >= from || h < to);
            if (!inside) return false;
        }
        return true;
    }

    private static void checkHour(Integer h) {
        if (h != null && (h < 0 || h > 24)) throw new IllegalArgumentException("Hours are 0–24");
    }
}
