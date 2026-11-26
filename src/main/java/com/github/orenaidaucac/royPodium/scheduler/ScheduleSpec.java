package com.github.orenaidaucac.royPodium.scheduler;

import lombok.Builder;
import lombok.Getter;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Immutable DST-aware schedule specification supporting DAILY, WEEKLY, MONTHLY, and INTERVAL frequencies.
 */
@Getter
@Builder
public final class ScheduleSpec {

    public enum Frequency {
        DAILY,
        WEEKLY,
        MONTHLY,
        INTERVAL
    }

    private final String id;
    private final ZoneId timezone;
    private final Frequency frequency;
    private final LocalTime timeStart;
    private final Set<DayOfWeek> daysOfWeek;
    /** 1..31, or -1 for LAST day of month. */
    private final int dayOfMonth;
    private final Duration interval;
    private final MissedPolicy missedPolicy;

    /**
     * Computes the next scheduled execution instant strictly after {@code after}.
     */
    public Instant nextExecutionAfter(Instant after) {
        ZonedDateTime zdtAfter = after.atZone(timezone);
        return switch (frequency) {
            case DAILY -> nextDaily(zdtAfter, after);
            case WEEKLY -> nextWeekly(zdtAfter, after);
            case MONTHLY -> nextMonthly(zdtAfter, after);
            case INTERVAL -> nextInterval(zdtAfter, after);
        };
    }

    /**
     * Collects missed execution instants in {@code (lastRun, now]} up to {@code maxScan}.
     */
    public List<Instant> missedExecutionsBetween(Instant lastRun, Instant now, int maxScan) {
        List<Instant> missed = new ArrayList<>();
        if (lastRun == null || !now.isAfter(lastRun)) {
            return missed;
        }
        Instant cursor = lastRun;
        int safety = Math.max(1, maxScan);
        for (int i = 0; i < safety; i++) {
            Instant next = nextExecutionAfter(cursor);
            if (next.isAfter(now)) {
                break;
            }
            missed.add(next);
            cursor = next;
        }
        return missed;
    }

    private Instant nextDaily(ZonedDateTime zdtAfter, Instant after) {
        LocalDate date = zdtAfter.toLocalDate();
        Instant candidate = ZonedDateTime.of(date, timeStart, timezone).toInstant();
        if (!candidate.isAfter(after)) {
            candidate = ZonedDateTime.of(date.plusDays(1), timeStart, timezone).toInstant();
        }
        return candidate;
    }

    private Instant nextWeekly(ZonedDateTime zdtAfter, Instant after) {
        Set<DayOfWeek> days = (daysOfWeek == null || daysOfWeek.isEmpty())
                ? Set.of(DayOfWeek.SUNDAY)
                : daysOfWeek;
        LocalDate base = zdtAfter.toLocalDate();
        for (int offset = 0; offset <= 7; offset++) {
            LocalDate date = base.plusDays(offset);
            if (!days.contains(date.getDayOfWeek())) {
                continue;
            }
            Instant candidate = ZonedDateTime.of(date, timeStart, timezone).toInstant();
            if (candidate.isAfter(after)) {
                return candidate;
            }
        }
        // Fallback: next week
        return ZonedDateTime.of(base.plusWeeks(1), timeStart, timezone).toInstant();
    }

    private Instant nextMonthly(ZonedDateTime zdtAfter, Instant after) {
        YearMonth ym = YearMonth.from(zdtAfter);
        for (int i = 0; i < 24; i++) {
            YearMonth targetYm = ym.plusMonths(i);
            int targetDay = (dayOfMonth < 0)
                    ? targetYm.lengthOfMonth()
                    : Math.min(dayOfMonth, targetYm.lengthOfMonth());
            LocalDate date = targetYm.atDay(targetDay);
            Instant candidate = ZonedDateTime.of(date, timeStart, timezone).toInstant();
            if (candidate.isAfter(after)) {
                return candidate;
            }
        }
        return ZonedDateTime.of(zdtAfter.toLocalDate().plusMonths(1), timeStart, timezone).toInstant();
    }

    private Instant nextInterval(ZonedDateTime zdtAfter, Instant after) {
        long stepSeconds = (interval != null && interval.getSeconds() > 0) ? interval.getSeconds() : 3600L;
        Instant anchor = ZonedDateTime.of(zdtAfter.toLocalDate().minusDays(1), timeStart, timezone).toInstant();
        if (anchor.isAfter(after)) {
            return anchor;
        }
        long diff = after.getEpochSecond() - anchor.getEpochSecond();
        long steps = (diff / stepSeconds) + 1;
        Instant candidate = anchor.plusSeconds(steps * stepSeconds);
        while (!candidate.isAfter(after)) {
            candidate = candidate.plusSeconds(stepSeconds);
        }
        return candidate;
    }
}
