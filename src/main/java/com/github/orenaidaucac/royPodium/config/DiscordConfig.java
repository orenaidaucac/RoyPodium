package com.github.orenaidaucac.royPodium.config;

import com.github.orenaidaucac.royPodium.discord.DiscordTemplate;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * Parsed configuration from {@code discord.yml}, including named templates and webhook profiles.
 */
@Getter
@Builder
public final class DiscordConfig {

    private final boolean enabled;
    private final String defaultWebhook;
    private final String defaultTemplate;
    private final String fallbackTemplate;

    private final Map<String, WebhookProfile> webhooks;
    private final Map<String, DiscordTemplate> templates;

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

    public DiscordTemplate resolveTemplate(String requestedId) {
        if (templates != null) {
            if (requestedId != null && !requestedId.isBlank() && templates.containsKey(requestedId)) {
                return templates.get(requestedId);
            }
            if (defaultTemplate != null && templates.containsKey(defaultTemplate)) {
                return templates.get(defaultTemplate);
            }
            if (fallbackTemplate != null && templates.containsKey(fallbackTemplate)) {
                return templates.get(fallbackTemplate);
            }
        }
        return DiscordTemplate.minimalFallback();
    }

    public DiscordTemplate getFallbackOrMinimal() {
        if (templates != null && fallbackTemplate != null && templates.containsKey(fallbackTemplate)) {
            return templates.get(fallbackTemplate);
        }
        return DiscordTemplate.minimalFallback();
    }

    @Getter
    @Builder
    public static final class WebhookProfile {
        private final String id;
        private final String url;
        private final String maskedUrl;
        private final String username;
        private final String avatarUrl;
        private final String template;
        private final String threadId;
        private final String threadName;
        @Builder.Default
        private final List<String> appliedTags = List.of();
    }
}
