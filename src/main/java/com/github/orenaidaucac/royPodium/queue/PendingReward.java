package com.github.orenaidaucac.royPodium.queue;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.UUID;

/**
 * Represents a pending leaderboard reward queued for an offline player.
 */
@Getter
@Builder
public final class PendingReward {

    private final String entryId;
    private final UUID playerUuid;
    private final String lastKnownName;
    private final String podiumId;
    private final int rank;
    private final String value;
    private final String payoutId;
    private final List<String> commands;
    private final long createdAtEpochMs;
    /** 0 means never expires. */
    private final long expiresAtEpochMs;

    public boolean isExpired(long nowEpochMs) {
        return expiresAtEpochMs > 0 && nowEpochMs >= expiresAtEpochMs;
    }
}
