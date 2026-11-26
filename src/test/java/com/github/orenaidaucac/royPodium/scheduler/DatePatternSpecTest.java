package com.github.orenaidaucac.royPodium.scheduler;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatePatternSpecTest {

    @Test
    void rejectsInvalidCalendarDatesAndMalformedEntries() {
        assertFalse(DatePatternSpec.parse("2026-02-29 12:00:00").isValid()); // 2026 is not leap year
        assertTrue(DatePatternSpec.parse("2028-02-29 12:00:00").isValid());  // 2028 is leap year
        assertFalse(DatePatternSpec.parse("2026-02-30 12:00:00").isValid());
        assertFalse(DatePatternSpec.parse("2026-04-31 12:00:00").isValid());
        assertFalse(DatePatternSpec.parse("*-02-30 12:00:00").isValid());
        assertFalse(DatePatternSpec.parse("2026-13-01 12:00:00").isValid());
        assertFalse(DatePatternSpec.parse("2026-12-25 25:00:00").isValid());
        assertFalse(DatePatternSpec.parse("not-a-date").isValid());
    }

    @Test
    void handlesMultipleTimesSameDayAndPastDates() {
        ZoneId utc = ZoneId.of("UTC");
        ScheduleSpec spec = ScheduleSpec.builder()
                .id("events")
                .timezone(utc)
                .frequency(ScheduleSpec.Frequency.SPECIFIC_DATES)
                .timeStart(LocalTime.MIDNIGHT)
                .datePatterns(List.of(
                        DatePatternSpec.parse("2026-12-25 20:00:00").spec(),
                        DatePatternSpec.parse("2026-12-25 12:00:00").spec()
                ))
                .build();

        Instant morning = ZonedDateTime.of(2026, 12, 25, 8, 0, 0, 0, utc).toInstant();
        Instant noon = ZonedDateTime.of(2026, 12, 25, 12, 0, 0, 0, utc).toInstant();
        Instant evening = ZonedDateTime.of(2026, 12, 25, 20, 0, 0, 0, utc).toInstant();

        assertEquals(noon, spec.nextExecutionAfter(morning));
        assertEquals(evening, spec.nextExecutionAfter(noon));
        assertNull(spec.nextExecutionAfter(evening));
    }

    @Test
    void handlesShortMonthPoliciesSkipVsLastDay() {
        ZoneId utc = ZoneId.of("UTC");
        DatePatternSpec day31 = DatePatternSpec.parse("*-*-31 12:00:00").spec();
        Instant feb1 = ZonedDateTime.of(2026, 2, 1, 0, 0, 0, 0, utc).toInstant();

        // SKIP_MONTH: February has no 31st -> skips to March 31
        Instant skipped = day31.nextExecutionAfter(feb1, utc, ShortMonthPolicy.SKIP_MONTH);
        assertEquals(ZonedDateTime.of(2026, 3, 31, 12, 0, 0, 0, utc).toInstant(), skipped);

        // LAST_DAY: February clamps to Feb 28
        Instant clamped = day31.nextExecutionAfter(feb1, utc, ShortMonthPolicy.LAST_DAY);
        assertEquals(ZonedDateTime.of(2026, 2, 28, 12, 0, 0, 0, utc).toInstant(), clamped);
    }

    @Test
    void supportsWildcardsShorthandAndFarFuture() {
        ZoneId vn = ZoneId.of("Asia/Ho_Chi_Minh");
        DatePatternSpec annual = DatePatternSpec.parse("01-01 00:00").spec();
        Instant dec2026 = ZonedDateTime.of(2026, 12, 1, 0, 0, 0, 0, vn).toInstant();
        assertEquals(ZonedDateTime.of(2027, 1, 1, 0, 0, 0, 0, vn).toInstant(),
                annual.nextExecutionAfter(dec2026, vn, ShortMonthPolicy.SKIP_MONTH));

        DatePatternSpec farFuture = DatePatternSpec.parse("2099-12-31 23:59:59").spec();
        assertEquals(ZonedDateTime.of(2099, 12, 31, 23, 59, 59, 0, vn).toInstant(),
                farFuture.nextExecutionAfter(dec2026, vn, ShortMonthPolicy.SKIP_MONTH));
    }

    @Test
    void handlesDstAndMissedPastDatesDeterministically() {
        ZoneId ny = ZoneId.of("America/New_York");
        ScheduleSpec spec = ScheduleSpec.builder()
                .id("dst_specific")
                .timezone(ny)
                .frequency(ScheduleSpec.Frequency.SPECIFIC_DATES)
                .timeStart(LocalTime.MIDNIGHT)
                .datePatterns(List.of(
                        DatePatternSpec.parse("2026-03-08 02:30:00").spec(),
                        DatePatternSpec.parse("2026-11-01 01:30:00").spec()
                ))
                .build();

        Instant beforeSpring = ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, ny).toInstant();
        Instant springRun = spec.nextExecutionAfter(beforeSpring);
        assertNotNull(springRun);
        assertEquals(3, springRun.atZone(ny).getHour());

        Instant fallRun = ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 0, ny).toInstant();
        assertNull(spec.nextExecutionAfter(fallRun)); // Does not fire twice during fall-back

        // Missed executions between Jan 1 and Dec 1 collects both
        Instant jan1 = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ny).toInstant();
        Instant dec1 = ZonedDateTime.of(2026, 12, 1, 0, 0, 0, 0, ny).toInstant();
        assertEquals(2, spec.missedExecutionsBetween(jan1, dec1, 10).size());
    }
}
