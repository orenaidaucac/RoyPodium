package com.github.orenaidaucac.royPodium.config;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * Parsed configuration from {@code discord.yml}.
 */
@Getter
@Builder
public final class DiscordConfig {

    public enum ThumbnailMode {
        NONE,
        TOP_1_PLAYER,
        CUSTOM_URL
    }

    private final boolean enabled;
    private final String defaultWebhook;
    private final Map<String, WebhookProfile> webhooks;

    private final int timeoutSeconds;
    private final int maxRetries;
    private final long minIntervalMs;
    private final int shutdownDrainSeconds;
    private final int testCooldownSeconds;

    private final boolean playerAvatarEnabled;
    private final String playerAvatarUrlTemplate;

    private final String statusOnline;
    private final String statusOffline;
    private final String statusSkipped;

    private final Map<String, String> rankIcons;
    private final String defaultRankIcon;

    private final int embedColor;
    private final String embedTitle;
    private final String embedDescription;
    private final boolean includeTimestamp;
    private final ThumbnailMode thumbnailMode;
    private final String thumbnailUrl;
    private final String fieldTitle;
    private final String entryFormat;
    private final String skippedFormat;
    private final boolean includeSkipped;
    private final String emptyMessage;
    private final String footer;

    public String getRankIcon(int rank) {
        if (rankIcons != null) {
            String exact = rankIcons.get(String.valueOf(rank));
            if (exact != null) {
                return exact;
            }
        }
        return (defaultRankIcon != null ? defaultRankIcon : "#{rank}")
                .replace("{rank}", String.valueOf(rank));
    }

    @Getter
    @Builder
    public static final class WebhookProfile {
        private final String id;
        private final String url;
        private final String maskedUrl;
        private final String username;
        private final String avatarUrl;
    }
}
