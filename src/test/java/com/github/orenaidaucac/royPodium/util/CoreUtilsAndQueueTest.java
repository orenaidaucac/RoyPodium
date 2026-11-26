package com.github.orenaidaucac.royPodium.util;

import com.github.orenaidaucac.royPodium.queue.PendingReward;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CoreUtilsAndQueueTest {

    @Test
    void timeUtilParsesZonesTimesAndDurations() {
        assertEquals(ZoneId.of("Asia/Ho_Chi_Minh"), TimeUtil.parseZoneId("Asia/Ho_Chi_Minh").orElseThrow());
        assertTrue(TimeUtil.parseZoneId("Invalid/Zone").isEmpty());

        assertEquals(LocalTime.of(20, 30, 0), TimeUtil.parseLocalTime("20:30").orElseThrow());
        assertTrue(TimeUtil.parseLocalTime("99:99").isEmpty());

        assertEquals(Duration.ofDays(7), TimeUtil.parseDuration("7d").orElseThrow());
        assertEquals(Duration.ZERO, TimeUtil.parseDuration("0").orElseThrow());
        assertEquals("2h 15m", TimeUtil.formatDuration(Duration.ofMinutes(135)));
    }

    @Test
    void atomicFileUtilWritesAndOverwritesCleanly(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("data").resolve("pending-rewards.yml");
        AtomicFileUtil.writeAtomic(target, "version: 1\n");
        assertEquals("version: 1\n", Files.readString(target));

        AtomicFileUtil.writeAtomic(target, "version: 2\n");
        assertEquals("version: 2\n", Files.readString(target));
    }

    @Test
    void pendingRewardExpirationRespectsZeroAndTimestamp() {
        PendingReward never = PendingReward.builder()
                .entryId("1")
                .playerUuid(UUID.randomUUID())
                .commands(List.of("say hi"))
                .createdAtEpochMs(1000L)
                .expiresAtEpochMs(0L)
                .build();
        assertFalse(never.isExpired(Long.MAX_VALUE));

        PendingReward expiring = PendingReward.builder()
                .entryId("2")
                .playerUuid(UUID.randomUUID())
                .commands(List.of("say hi"))
                .createdAtEpochMs(1000L)
                .expiresAtEpochMs(5000L)
                .build();
        assertFalse(expiring.isExpired(4999L));
        assertTrue(expiring.isExpired(5000L));
    }
}
