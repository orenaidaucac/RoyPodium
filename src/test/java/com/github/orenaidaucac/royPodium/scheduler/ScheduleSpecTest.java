package com.github.orenaidaucac.royPodium.scheduler;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleSpecTest {

    @Test
    void dailyScheduleAdvancesToNextDayAfterStartTime() {
        ZoneId utc = ZoneId.of("UTC");
        ScheduleSpec spec = ScheduleSpec.builder()
                .id("daily")
                .timezone(utc)
                .frequency(ScheduleSpec.Frequency.DAILY)
                .timeStart(LocalTime.of(12, 0))
                .build();

        Instant before = ZonedDateTime.of(2026, 5, 10, 11, 59, 0, 0, utc).toInstant();
        Instant expectedSameDay = ZonedDateTime.of(2026, 5, 10, 12, 0, 0, 0, utc).toInstant();
        assertEquals(expectedSameDay, spec.nextExecutionAfter(before));

        Instant atExact = expectedSameDay;
        Instant expectedNextDay = ZonedDateTime.of(2026, 5, 11, 12, 0, 0, 0, utc).toInstant();
        assertEquals(expectedNextDay, spec.nextExecutionAfter(atExact));
    }

    @Test
    void weeklySchedulePicksConfiguredDaysOnly() {
        ZoneId utc = ZoneId.of("UTC");
        ScheduleSpec spec = ScheduleSpec.builder()
                .id("weekly")
                .timezone(utc)
                .frequency(ScheduleSpec.Frequency.WEEKLY)
                .daysOfWeek(Set.of(DayOfWeek.FRIDAY))
                .timeStart(LocalTime.of(20, 0))
                .build();

        // 2026-05-11 is Monday -> next Friday is 2026-05-15
        Instant monday = ZonedDateTime.of(2026, 5, 11, 10, 0, 0, 0, utc).toInstant();
        Instant expectedFriday = ZonedDateTime.of(2026, 5, 15, 20, 0, 0, 0, utc).toInstant();
        assertEquals(expectedFriday, spec.nextExecutionAfter(monday));
    }

    @Test
    void monthlyLastDayHandlesFebruary() {
        ZoneId utc = ZoneId.of("UTC");
        ScheduleSpec spec = ScheduleSpec.builder()
                .id("monthly_last")
                .timezone(utc)
                .frequency(ScheduleSpec.Frequency.MONTHLY)
                .dayOfMonth(-1)
                .timeStart(LocalTime.of(23, 59))
                .build();

        Instant feb1 = ZonedDateTime.of(2026, 2, 1, 0, 0, 0, 0, utc).toInstant();
        Instant expected = ZonedDateTime.of(2026, 2, 28, 23, 59, 0, 0, utc).toInstant();
        assertEquals(expected, spec.nextExecutionAfter(feb1));
    }

    @Test
    void dstSpringForwardAndFallBackDoNotDuplicateOrSkip() {
        ZoneId ny = ZoneId.of("America/New_York");
        ScheduleSpec spec = ScheduleSpec.builder()
                .id("dst_test")
                .timezone(ny)
                .frequency(ScheduleSpec.Frequency.DAILY)
                .timeStart(LocalTime.of(2, 30))
                .build();

        // 2026-03-08 is US spring-forward (02:00 -> 03:00). 02:30 adjusts to 03:30 EDT.
        Instant beforeSpring = ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, ny).toInstant();
        Instant springRun = spec.nextExecutionAfter(beforeSpring);
        assertTrue(springRun.isAfter(beforeSpring));
        assertEquals(3, springRun.atZone(ny).getHour());

        // 2026-11-01 is US fall-back (02:00 -> 01:00). For 01:30, next run after 01:30 EDT is Nov 2 (no duplicate on Nov 1).
        ScheduleSpec fallSpec = ScheduleSpec.builder()
                .id("dst_fall")
                .timezone(ny)
                .frequency(ScheduleSpec.Frequency.DAILY)
                .timeStart(LocalTime.of(1, 30))
                .build();
        Instant fallRun = ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 0, ny).toInstant();
        Instant nextAfterFall = fallSpec.nextExecutionAfter(fallRun);
        assertEquals(2, nextAfterFall.atZone(ny).getDayOfMonth());
    }

    @Test
    void missedExecutionsCollectsAllMissedWithinWindow() {
        ZoneId utc = ZoneId.of("UTC");
        ScheduleSpec spec = ScheduleSpec.builder()
                .id("interval")
                .timezone(utc)
                .frequency(ScheduleSpec.Frequency.INTERVAL)
                .timeStart(LocalTime.MIDNIGHT)
                .interval(Duration.ofHours(1))
                .build();

        Instant last = ZonedDateTime.of(2026, 5, 10, 10, 0, 0, 0, utc).toInstant();
        Instant now = ZonedDateTime.of(2026, 5, 10, 13, 15, 0, 0, utc).toInstant();
        List<Instant> missed = spec.missedExecutionsBetween(last, now, 10);
        assertEquals(3, missed.size()); // 11:00, 12:00, 13:00
    }
}
