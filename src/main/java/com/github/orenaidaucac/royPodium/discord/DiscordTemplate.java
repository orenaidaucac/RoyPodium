package com.github.orenaidaucac.royPodium.discord;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * Named Discord Webhook message/embed template supporting inheritance, multi-embed/field layouts,
 * conditional colors, flags, threads/forums, custom fields, and link buttons.
 */
@Getter
@Builder(toBuilder = true)
public final class DiscordTemplate {

    public enum LayoutMode {
        LIST_FIELD,
        PER_ENTRY_FIELD,
        PER_ENTRY_EMBED
    }

    public enum OverflowPolicy {
        SPLIT_MESSAGE,
        TRUNCATE
    }

    public enum ThumbnailMode {
        NONE,
        TOP_1_PLAYER,
        CUSTOM_URL
    }

    private final String id;
    private final String extendsTemplate;

    @Builder.Default private final LayoutMode layoutMode = LayoutMode.LIST_FIELD;
    @Builder.Default private final OverflowPolicy overflowPolicy = OverflowPolicy.SPLIT_MESSAGE;

    @Builder.Default private final String content = "";
    @Builder.Default private final boolean tts = false;
    @Builder.Default private final boolean suppressEmbeds = false;
    @Builder.Default private final boolean suppressNotifications = false;

    @Builder.Default private final List<String> mentionRoles = List.of();
    @Builder.Default private final List<String> mentionUsers = List.of();

    @Builder.Default private final String authorName = "";
    @Builder.Default private final String authorUrl = "";
    @Builder.Default private final String authorIconUrl = "";

    @Builder.Default private final int color = 0xFFD700;
    @Builder.Default private final Map<String, Integer> rankColors = Map.of();

    @Builder.Default private final String title = "{podium_name} — Payout Results";
    @Builder.Default private final String titleUrl = "";
    @Builder.Default private final String description = "Scheduled payout completed at {date} {time} ({timezone}).";
    @Builder.Default private final boolean includeTimestamp = true;

    @Builder.Default private final ThumbnailMode thumbnailMode = ThumbnailMode.TOP_1_PLAYER;
    @Builder.Default private final String thumbnailUrl = "";
    @Builder.Default private final String imageUrl = "";

    @Builder.Default private final String fieldTitle = "Leaderboard";
    @Builder.Default private final boolean inlineEntryFields = false;
    @Builder.Default private final Map<String, String> entryFormats = Map.of();
    @Builder.Default private final String defaultEntryFormat = "{rank_icon} **#{rank}** {player} — **{value}** *({status})*";
    @Builder.Default private final String skippedFormat = "{rank_icon} **#{rank}** {player} — {value} *(Skipped: {reason})*";
    @Builder.Default private final boolean includeSkipped = true;
    @Builder.Default private final String emptyMessage = "No eligible players were rewarded in this payout.";

    @Builder.Default private final String entryEmbedTitle = "{rank_icon} Rank #{rank} — {player}";
    @Builder.Default private final String entryEmbedDescription = "Score: **{value}**\nStatus: *{status}*";

    @Builder.Default private final String footerText = "RoyPodium • {online_count} online • {offline_count} queued • {skipped_count} skipped";
    @Builder.Default private final String footerIconUrl = "";

    @Builder.Default private final List<CustomField> customFields = List.of();
    @Builder.Default private final List<LinkButton> linkButtons = List.of();

    public String getEntryFormatForRank(int rank) {
        if (entryFormats != null) {
            String exact = entryFormats.get(String.valueOf(rank));
            if (exact != null) {
                return exact;
            }
        }
        return defaultEntryFormat != null ? defaultEntryFormat : "{rank_icon} **#{rank}** {player} — **{value}** *({status})*";
    }

    public int resolveColor(Integer podiumOverrideColor, Integer topRank) {
        if (topRank != null && rankColors != null) {
            Integer rc = rankColors.get(String.valueOf(topRank));
            if (rc != null) {
                return rc;
            }
        }
        if (podiumOverrideColor != null) {
            return podiumOverrideColor;
        }
        return color;
    }

    public static DiscordTemplate minimalFallback() {
        return DiscordTemplate.builder()
                .id("minimal_fallback")
                .layoutMode(LayoutMode.LIST_FIELD)
                .overflowPolicy(OverflowPolicy.TRUNCATE)
                .title("{podium_name} — Payout")
                .description("Completed {timestamp_relative}")
                .thumbnailMode(ThumbnailMode.NONE)
                .includeSkipped(false)
                .footerText("RoyPodium")
                .build();
    }

    @Getter
    @Builder
    public static final class CustomField {
        private final String name;
        private final String value;
        private final boolean inline;
    }

    @Getter
    @Builder
    public static final class LinkButton {
        private final String label;
        private final String url;
        private final String emoji;
    }
}
