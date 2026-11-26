package com.github.orenaidaucac.royPodium.discord;

import com.github.orenaidaucac.royPodium.config.DiscordConfig;
import com.github.orenaidaucac.royPodium.podium.PodiumDefinition;
import com.github.orenaidaucac.royPodium.podium.PodiumSnapshot;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds Discord Webhook JSON payloads strictly adhering to official Discord Embed limits
 * (Title <= 256, Description <= 4096, Footer <= 2048, Field Value <= 1024, Fields <= 25, Total <= 6000).
 */
public final class DiscordEmbedBuilder {

    public static final int MAX_TITLE = 256;
    public static final int MAX_DESCRIPTION = 4096;
    public static final int MAX_FIELD_NAME = 256;
    public static final int MAX_FIELD_VALUE = 1024;
    public static final int MAX_FOOTER = 2048;
    public static final int MAX_FIELDS_PER_EMBED = 25;
    public static final int MAX_TOTAL_CHARS = 6000;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private DiscordEmbedBuilder() {
    }

    /**
     * Builds one or more JSON payloads for a payout snapshot, automatically splitting long leaderboards
     * across multiple fields and messages while respecting Discord's 6,000-character total limit.
     */
    public static List<String> buildPayloads(DiscordConfig cfg,
                                             DiscordConfig.WebhookProfile profile,
                                             PodiumDefinition def,
                                             PodiumSnapshot snapshot,
                                             int onlineCount,
                                             int offlineCount,
                                             ZoneId zone) {
        List<String> lines = buildRenderedLines(cfg, def, snapshot);
        ZonedDateTime zdt = snapshot.getCapturedAt().atZone(zone);
        String dateStr = DATE_FMT.format(zdt);
        String timeStr = TIME_FMT.format(zdt);
        String strippedPodiumName = DiscordTextSanitizer.stripMinecraft(def.getDisplayName());
        int skippedCount = snapshot.getSkippedEntries().size();
        int totalCount = snapshot.getEntries().size() + skippedCount;

        String title = truncate(DiscordTextSanitizer.sanitizeTemplateOutput(
                applyGlobalVars(cfg.getEmbedTitle(), def.getId(), strippedPodiumName, dateStr, timeStr,
                        zone.getId(), onlineCount, offlineCount, skippedCount, totalCount)), MAX_TITLE);

        String description = truncate(DiscordTextSanitizer.sanitizeTemplateOutput(
                applyGlobalVars(cfg.getEmbedDescription(), def.getId(), strippedPodiumName, dateStr, timeStr,
                        zone.getId(), onlineCount, offlineCount, skippedCount, totalCount)), MAX_DESCRIPTION);

        String footer = truncate(DiscordTextSanitizer.sanitizeTemplateOutput(
                applyGlobalVars(cfg.getFooter(), def.getId(), strippedPodiumName, dateStr, timeStr,
                        zone.getId(), onlineCount, offlineCount, skippedCount, totalCount)), MAX_FOOTER);

        String thumbnailUrl = resolveThumbnailUrl(cfg, snapshot);
        String timestampIso = cfg.isIncludeTimestamp() ? snapshot.getCapturedAt().toString() : null;

        // Partition lines into 1024-char field chunks
        List<String> fieldChunks = chunkLines(lines, MAX_FIELD_VALUE);
        if (fieldChunks.isEmpty()) {
            fieldChunks = List.of(truncate(DiscordTextSanitizer.sanitizeTemplateOutput(cfg.getEmptyMessage()), MAX_FIELD_VALUE));
        }

        // Partition field chunks into messages respecting MAX_FIELDS_PER_EMBED and MAX_TOTAL_CHARS
        int baseOverhead = title.length() + description.length() + footer.length() + 128;
        List<List<FieldEntry>> messageBatches = new ArrayList<>();
        List<FieldEntry> currentBatch = new ArrayList<>();
        int currentChars = baseOverhead;

        for (int i = 0; i < fieldChunks.size(); i++) {
            String fieldTitle = cfg.getFieldTitle();
            if (fieldChunks.size() > 1) {
                fieldTitle = fieldTitle + " (" + (i + 1) + "/" + fieldChunks.size() + ")";
            }
            fieldTitle = truncate(DiscordTextSanitizer.sanitizeTemplateOutput(fieldTitle), MAX_FIELD_NAME);
            String fieldVal = fieldChunks.get(i);
            int fieldCost = fieldTitle.length() + fieldVal.length();

            if (!currentBatch.isEmpty()
                    && (currentBatch.size() >= MAX_FIELDS_PER_EMBED || currentChars + fieldCost > MAX_TOTAL_CHARS)) {
                messageBatches.add(currentBatch);
                currentBatch = new ArrayList<>();
                currentChars = baseOverhead;
            }
            currentBatch.add(new FieldEntry(fieldTitle, fieldVal));
            currentChars += fieldCost;
        }
        if (!currentBatch.isEmpty()) {
            messageBatches.add(currentBatch);
        }

        List<String> payloads = new ArrayList<>(messageBatches.size());
        for (int b = 0; b < messageBatches.size(); b++) {
            String batchTitle = messageBatches.size() > 1
                    ? truncate(title + " (" + (b + 1) + "/" + messageBatches.size() + ")", MAX_TITLE)
                    : title;
            String batchDesc = b == 0 ? description : "";
            payloads.add(serializePayload(
                    profile,
                    cfg.getEmbedColor(),
                    batchTitle,
                    batchDesc,
                    messageBatches.get(b),
                    footer,
                    b == 0 ? thumbnailUrl : null,
                    timestampIso
            ));
        }
        return payloads;
    }

    /**
     * Builds human-readable preview lines for in-game/console {@code /roypodium webhook preview}.
     */
    public static List<String> buildPreviewLines(DiscordConfig cfg,
                                                 PodiumDefinition def,
                                                 PodiumSnapshot snapshot,
                                                 int onlineCount,
                                                 int offlineCount,
                                                 ZoneId zone) {
        List<String> out = new ArrayList<>();
        ZonedDateTime zdt = snapshot.getCapturedAt().atZone(zone);
        String dateStr = DATE_FMT.format(zdt);
        String timeStr = TIME_FMT.format(zdt);
        String strippedName = DiscordTextSanitizer.stripMinecraft(def.getDisplayName());
        int skipped = snapshot.getSkippedEntries().size();
        int total = snapshot.getEntries().size() + skipped;

        out.add("Title: " + DiscordTextSanitizer.sanitizeTemplateOutput(
                applyGlobalVars(cfg.getEmbedTitle(), def.getId(), strippedName, dateStr, timeStr,
                        zone.getId(), onlineCount, offlineCount, skipped, total)));
        out.add("Description: " + DiscordTextSanitizer.sanitizeTemplateOutput(
                applyGlobalVars(cfg.getEmbedDescription(), def.getId(), strippedName, dateStr, timeStr,
                        zone.getId(), onlineCount, offlineCount, skipped, total)));

        List<String> lines = buildRenderedLines(cfg, def, snapshot);
        if (lines.isEmpty()) {
            out.add(DiscordTextSanitizer.sanitizeTemplateOutput(cfg.getEmptyMessage()));
        } else {
            out.addAll(lines);
        }

        out.add("Footer: " + DiscordTextSanitizer.sanitizeTemplateOutput(
                applyGlobalVars(cfg.getFooter(), def.getId(), strippedName, dateStr, timeStr,
                        zone.getId(), onlineCount, offlineCount, skipped, total)));
        return out;
    }

    private static List<String> buildRenderedLines(DiscordConfig cfg, PodiumDefinition def, PodiumSnapshot snapshot) {
        List<String> lines = new ArrayList<>();
        ValueFormatSpec fmt = def.getValueFormat() != null ? def.getValueFormat() : ValueFormatSpec.DEFAULT;

        for (PodiumSnapshot.Entry e : snapshot.getEntries()) {
            String safePlayer = DiscordTextSanitizer.sanitizeUntrusted(e.getPlayerName());
            String formattedVal = DiscordTextSanitizer.sanitizeUntrusted(fmt.format(e.getValue()));
            String status = e.isOnline() ? cfg.getStatusOnline() : cfg.getStatusOffline();
            String rankIcon = cfg.getRankIcon(e.getRank());

            String line = cfg.getEntryFormat()
                    .replace("{rank}", String.valueOf(e.getRank()))
                    .replace("{rank_icon}", rankIcon)
                    .replace("{player}", safePlayer)
                    .replace("{value}", formattedVal)
                    .replace("{status}", status);
            lines.add(DiscordTextSanitizer.sanitizeTemplateOutput(line));
        }

        if (cfg.isIncludeSkipped()) {
            for (PodiumSnapshot.SkippedEntry s : snapshot.getSkippedEntries()) {
                String safePlayer = DiscordTextSanitizer.sanitizeUntrusted(s.getPlayerName());
                String formattedVal = DiscordTextSanitizer.sanitizeUntrusted(fmt.format(s.getValue()));
                String safeReason = DiscordTextSanitizer.sanitizeUntrusted(s.getReason());
                String status = cfg.getStatusSkipped().replace("{reason}", safeReason);
                String rankIcon = cfg.getRankIcon(s.getRank());

                String line = cfg.getSkippedFormat()
                        .replace("{rank}", String.valueOf(s.getRank()))
                        .replace("{rank_icon}", rankIcon)
                        .replace("{player}", safePlayer)
                        .replace("{value}", formattedVal)
                        .replace("{reason}", safeReason)
                        .replace("{status}", status);
                lines.add(DiscordTextSanitizer.sanitizeTemplateOutput(line));
            }
        }
        return lines;
    }

    private static List<String> chunkLines(List<String> lines, int maxCharsPerField) {
        List<String> chunks = new ArrayList<>();
        if (lines.isEmpty()) {
            return chunks;
        }
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            String safeLine = line.length() > maxCharsPerField ? line.substring(0, maxCharsPerField - 3) + "..." : line;
            int needed = current.isEmpty() ? safeLine.length() : safeLine.length() + 1;
            if (!current.isEmpty() && current.length() + needed > maxCharsPerField) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(safeLine);
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private static String applyGlobalVars(String template,
                                          String podiumId,
                                          String podiumName,
                                          String date,
                                          String time,
                                          String timezone,
                                          int onlineCount,
                                          int offlineCount,
                                          int skippedCount,
                                          int totalCount) {
        if (template == null) {
            return "";
        }
        String out = template
                .replace("{podium}", podiumId)
                .replace("{podium_name}", podiumName)
                .replace("{date}", date)
                .replace("{time}", time)
                .replace("{timezone}", timezone)
                .replace("{online_count}", String.valueOf(onlineCount))
                .replace("{offline_count}", String.valueOf(offlineCount))
                .replace("{skipped_count}", String.valueOf(skippedCount))
                .replace("{total_count}", String.valueOf(totalCount));
        if (out.contains("%") && isPlaceholderApiPresent()) {
            try {
                out = PlaceholderAPI.setPlaceholders(null, out);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static boolean isPlaceholderApiPresent() {
        try {
            return Bukkit.getServer() != null && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String resolveThumbnailUrl(DiscordConfig cfg, PodiumSnapshot snapshot) {
        return switch (cfg.getThumbnailMode()) {
            case NONE -> null;
            case CUSTOM_URL -> (cfg.getThumbnailUrl() != null && !cfg.getThumbnailUrl().isBlank())
                    ? cfg.getThumbnailUrl().trim()
                    : null;
            case TOP_1_PLAYER -> {
                if (!cfg.isPlayerAvatarEnabled() || snapshot.getEntries().isEmpty()) {
                    yield null;
                }
                PodiumSnapshot.Entry top1 = snapshot.getEntries().get(0);
                String tpl = cfg.getPlayerAvatarUrlTemplate();
                if (tpl == null || tpl.isBlank()) {
                    yield null;
                }
                String uuid = top1.getPlayerUuid() != null ? top1.getPlayerUuid().toString() : "Steve";
                yield tpl.replace("{player}", top1.getPlayerName()).replace("{uuid}", uuid);
            }
        };
    }

    private static String serializePayload(DiscordConfig.WebhookProfile profile,
                                           int color,
                                           String title,
                                           String description,
                                           List<FieldEntry> fields,
                                           String footer,
                                           String thumbnailUrl,
                                           String timestampIso) {
        StringBuilder json = new StringBuilder(512);
        json.append('{');
        if (profile != null && profile.getUsername() != null && !profile.getUsername().isBlank()) {
            json.append("\"username\":").append(jsonStr(profile.getUsername())).append(',');
        }
        if (profile != null && profile.getAvatarUrl() != null && !profile.getAvatarUrl().isBlank()) {
            json.append("\"avatar_url\":").append(jsonStr(profile.getAvatarUrl())).append(',');
        }
        // Strictly block all Discord mentions
        json.append("\"allowed_mentions\":{\"parse\":[]},");
        json.append("\"embeds\":[{");
        json.append("\"color\":").append(color).append(',');
        if (!title.isEmpty()) {
            json.append("\"title\":").append(jsonStr(title)).append(',');
        }
        if (!description.isEmpty()) {
            json.append("\"description\":").append(jsonStr(description)).append(',');
        }
        if (thumbnailUrl != null && !thumbnailUrl.isBlank()) {
            json.append("\"thumbnail\":{\"url\":").append(jsonStr(thumbnailUrl)).append("},");
        }
        if (timestampIso != null) {
            json.append("\"timestamp\":").append(jsonStr(timestampIso)).append(',');
        }
        if (!footer.isEmpty()) {
            json.append("\"footer\":{\"text\":").append(jsonStr(footer)).append("},");
        }
        json.append("\"fields\":[");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) json.append(',');
            FieldEntry f = fields.get(i);
            json.append("{\"name\":").append(jsonStr(f.name()))
                    .append(",\"value\":").append(jsonStr(f.value()))
                    .append(",\"inline\":false}");
        }
        json.append("]}]}");
        return json.toString();
    }

    static String jsonStr(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 3)) + "...";
    }

    private record FieldEntry(String name, String value) {
    }
}
