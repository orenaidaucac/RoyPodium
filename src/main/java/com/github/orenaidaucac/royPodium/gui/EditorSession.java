package com.github.orenaidaucac.royPodium.gui;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Tracks an authenticated admin's active GUI editor session.
 */
@Getter
@Setter
public final class EditorSession {

    private final UUID playerUuid;
    private final String sessionToken;
    private final long createdAtMs;
    private volatile boolean transitioning;

    public EditorSession(UUID playerUuid) {
        this.playerUuid = playerUuid;
        this.sessionToken = UUID.randomUUID().toString();
        this.createdAtMs = System.currentTimeMillis();
    }
}
