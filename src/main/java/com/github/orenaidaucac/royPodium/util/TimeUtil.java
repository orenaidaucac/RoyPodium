package com.github.orenaidaucac.royPodium.util;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility methods for parsing timezones, local times, and human-readable durations.
 */
public final class TimeUtil {

    private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d+)\\s*([smhdw])$", Pattern.CASE_INSENSITIVE);

    private TimeUtil() {
    }

    /**
     * Parses an IANA timezone ID (e.g. {@code Asia/Ho_Chi_Minh}, {@code America/New_York}, {@code UTC}).
     */
    public static Optional<ZoneId> parseZoneId(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ZoneId.of(raw.trim()));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /**
     * Parses {@code HH:mm} or {@code HH:mm:ss}.
     */
    public static Optional<LocalTime> parseLocalTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalTime.parse(raw.trim()));
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    /**
     * Parses compact durations like {@code 30s}, {@code 15m}, {@code 2h}, {@code 7d}, {@code 1w}, or {@code 0}.
     */
    public static Optional<Duration> parseDuration(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if ("0".equals(trimmed) || "never".equalsIgnoreCase(trimmed) || "none".equalsIgnoreCase(trimmed)) {
            return Optional.of(Duration.ZERO);
        }
        Matcher matcher = DURATION_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        long amount = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2).toLowerCase();
        return switch (unit) {
            case "s" -> Optional.of(Duration.ofSeconds(amount));
            case "m" -> Optional.of(Duration.ofMinutes(amount));
            case "h" -> Optional.of(Duration.ofHours(amount));
            case "d" -> Optional.of(Duration.ofDays(amount));
            case "w" -> Optional.of(Duration.ofDays(amount * 7));
            default -> Optional.empty();
        };
    }

    /**
     * Formats a duration into a compact readable string (e.g. {@code 2h 15m 10s}).
     */
    public static String formatDuration(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            return "0s";
        }
        long totalSeconds = duration.getSeconds();
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (seconds > 0 || sb.isEmpty()) sb.append(seconds).append("s");
        return sb.toString().trim();
    }
}
