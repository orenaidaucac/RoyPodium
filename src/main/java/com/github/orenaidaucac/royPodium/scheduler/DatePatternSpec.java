package com.github.orenaidaucac.royPodium.scheduler;

import com.github.orenaidaucac.royPodium.util.TimeUtil;
import lombok.Getter;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Represents a specific or wildcard date-time entry in {@code schedules.yml},
 * supporting {@code YYYY-MM-DD HH:mm[:ss]} (with {@code *} wildcards) as well as
 * {@code MM-DD HH:mm[:ss]} and {@code DD HH:mm[:ss]} shorthands.
 */
@Getter
public final class DatePatternSpec {

    private final String raw;
    /** Null means wildcard (*). */
    private final Integer year;
    /** 1..12, or null for wildcard (*). */
    private final Integer month;
    /** 1..31, or null for wildcard (*). */
    private final Integer day;
    private final LocalTime time;

    private DatePatternSpec(String raw, Integer year, Integer month, Integer day, LocalTime time) {
        this.raw = raw;
        this.year = year;
        this.month = month;
        this.day = day;
        this.time = time;
    }

    public static ParseResult parse(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return ParseResult.error("Empty date entry.");
        }
        String trimmed = rawInput.trim();
        String[] parts = trimmed.split("\\s+");
        if (parts.length != 2) {
            return ParseResult.error("Expected '<date> <time>' separated by a space (e.g. '2026-12-25 20:00:00' or '*-01-01 00:00:00').");
        }

        Optional<LocalTime> parsedTime = TimeUtil.parseLocalTime(parts[1]);
        if (parsedTime.isEmpty()) {
            return ParseResult.error("Invalid time '" + parts[1] + "'. Use 24-hour 'HH:mm' or 'HH:mm:ss' format.");
        }

        String[] dateParts = parts[0].split("-", -1);
        Integer y;
        Integer m;
        Integer d;
        try {
            if (dateParts.length == 3) {
                y = parsePart(dateParts[0], 1970, 2999, "year");
                m = parsePart(dateParts[1], 1, 12, "month");
                d = parsePart(dateParts[2], 1, 31, "day");
            } else if (dateParts.length == 2) {
                y = null;
                m = parsePart(dateParts[0], 1, 12, "month");
                d = parsePart(dateParts[1], 1, 31, "day");
            } else if (dateParts.length == 1) {
                y = null;
                m = null;
                d = parsePart(dateParts[0], 1, 31, "day");
            } else {
                return ParseResult.error("Invalid date '" + parts[0] + "'. Use 'YYYY-MM-DD', 'MM-DD', or 'DD' (with '*' for wildcards).");
            }
        } catch (IllegalArgumentException ex) {
            return ParseResult.error(ex.getMessage());
        }

        if (y != null && m != null && d != null) {
            try {
                LocalDate.of(y, m, d);
            } catch (DateTimeException ex) {
                return ParseResult.error("Non-existent calendar date '" + parts[0] + "' (" + ex.getMessage() + ").");
            }
        } else if (y == null && m != null && d != null) {
            int maxPossibleDays = YearMonth.of(2024, m).lengthOfMonth();
            if (d > maxPossibleDays) {
                return ParseResult.error("Day " + d + " never exists in month " + m + " (max " + maxPossibleDays + " days).");
            }
        }

        return ParseResult.ok(new DatePatternSpec(trimmed, y, m, d, parsedTime.get()));
    }

    private static Integer parsePart(String token, int min, int max, String fieldName) {
        if ("*".equals(token)) {
            return null;
        }
        int val;
        try {
            val = Integer.parseInt(token);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid " + fieldName + " '" + token + "'. Must be a number or '*'.");
        }
        if (val < min || val > max) {
            throw new IllegalArgumentException("Out-of-range " + fieldName + " '" + token + "'. Must be between " + min + " and " + max + " (or '*').");
        }
        return val;
    }

    /**
     * Computes the next execution instant strictly after {@code after}, or {@code null} if this pattern has no future occurrence.
     */
    public Instant nextExecutionAfter(Instant after, ZoneId zone, ShortMonthPolicy shortMonthPolicy) {
        if (year != null && month != null && day != null) {
            LocalDate exact = LocalDate.of(year, month, day);
            Instant candidate = ZonedDateTime.of(exact, time, zone).toInstant();
            return candidate.isAfter(after) ? candidate : null;
        }

        ZonedDateTime zdtAfter = after.atZone(zone);
        LocalDate cursor = zdtAfter.toLocalDate();
        if (year != null) {
            if (cursor.getYear() > year) {
                return null;
            }
            if (cursor.getYear() < year) {
                cursor = LocalDate.of(year, 1, 1);
            }
        }

        ShortMonthPolicy policy = shortMonthPolicy != null ? shortMonthPolicy : ShortMonthPolicy.SKIP_MONTH;

        if (day != null) {
            YearMonth ym = YearMonth.from(cursor);
            // Scan up to 120 months (10 years) to handle leap-year *-02-29 patterns safely
            for (int i = 0; i < 120; i++) {
                YearMonth current = ym.plusMonths(i);
                if (year != null && current.getYear() > year) {
                    return null;
                }
                if (year != null && current.getYear() != year) {
                    continue;
                }
                if (month != null && current.getMonthValue() != month) {
                    continue;
                }
                int len = current.lengthOfMonth();
                int targetDay;
                if (day <= len) {
                    targetDay = day;
                } else if (policy == ShortMonthPolicy.LAST_DAY) {
                    targetDay = len;
                } else {
                    continue;
                }
                LocalDate date = current.atDay(targetDay);
                Instant candidate = ZonedDateTime.of(date, time, zone).toInstant();
                if (candidate.isAfter(after)) {
                    return candidate;
                }
            }
            return null;
        }

        // Wildcard day (*): scan day-by-day up to 1500 days
        for (int i = 0; i < 1500; i++) {
            LocalDate date = cursor.plusDays(i);
            if (year != null && date.getYear() > year) {
                return null;
            }
            if (year != null && date.getYear() != year) {
                continue;
            }
            if (month != null && date.getMonthValue() != month) {
                continue;
            }
            Instant candidate = ZonedDateTime.of(date, time, zone).toInstant();
            if (candidate.isAfter(after)) {
                return candidate;
            }
        }
        return null;
    }

    public record ParseResult(DatePatternSpec spec, String error) {
        static ParseResult ok(DatePatternSpec spec) {
            return new ParseResult(spec, null);
        }

        static ParseResult error(String message) {
            return new ParseResult(null, message);
        }

        public boolean isValid() {
            return spec != null;
        }
    }
}
