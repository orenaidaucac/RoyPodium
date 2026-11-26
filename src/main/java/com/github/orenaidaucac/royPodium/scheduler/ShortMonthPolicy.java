package com.github.orenaidaucac.royPodium.scheduler;

/**
 * Policy when a recurring specific-date pattern (e.g. day 29, 30, or 31) falls in a shorter month.
 */
public enum ShortMonthPolicy {
    /** Skip months that do not contain the configured day. */
    SKIP_MONTH,
    /** Clamp to the last valid day of that month (e.g. Feb 28/29 or Apr 30). */
    LAST_DAY
}
