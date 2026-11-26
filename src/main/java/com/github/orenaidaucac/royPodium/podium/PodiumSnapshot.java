package com.github.orenaidaucac.royPodium.podium;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Immutable snapshot of leaderboard entries captured at the exact moment of a podium payout.
 */
@Getter
@Builder
public final class PodiumSnapshot {

    private final String podiumId;
    private final String displayName;
    private final String payoutId;
    private final Instant capturedAt;
    private final List<Entry> entries;
    @Builder.Default
    private final List<SkippedEntry> skippedEntries = List.of();

    @Getter
    @Builder
    public static final class Entry {
        private final int rank;
        private final String playerName;
        private final UUID playerUuid;
        private final String value;
        private final boolean online;
        private final List<String> commands;
    }

    @Getter
    @Builder
    public static final class SkippedEntry {
        private final int rank;
        private final String playerName;
        private final String value;
        private final String reason;
    }
}
