package com.github.orenaidaucac.royPodium.discord;

import com.github.orenaidaucac.royPodium.config.DiscordConfig;
import com.github.orenaidaucac.royPodium.podium.PodiumDefinition;
import com.github.orenaidaucac.royPodium.podium.PodiumSnapshot;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;

import java.net.URI;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds Discord Webhook JSON payloads utilizing Discord's full official Webhook & Embed API
 * (content, tts, flags, threads/forums, link buttons, multi-embed, inline fields, author/image/footer icons,
 * dynamic Discord timestamps, and automatic limit enforcement/splitting).
 */
public final class DiscordEmbedBuilder {

    public static final int MAX_CONTENT = 2000;
    public static final int MAX_USERNAME = 80;
    public static final int MAX_TITLE = 256;
    public static final int MAX_DESCRIPTION = 4096;
    public static final int MAX_AUTHOR_NAME = 256;
    public static final int MAX_FIELD_NAME = 256;
    public static final int MAX_FIELD_VALUE = 1024;
    public static final int MAX_FOOTER = 2048;
    public static final int MAX_FIELDS_PER_EMBED = 25;
    public static final int MAX_EMBEDS_PER_MESSAGE = 10;
    public static final int MAX_TOTAL_CHARS = 6000;
    public static final int MAX_LINK_BUTTONS = 5;

    public static final int FLAG_SUPPRESS_EMBEDS = 1 << 2;        // 4
    public static final int FLAG_SUPPRESS_NOTIFICATIONS = 1 << 12; // 4096

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private DiscordEmbedBuilder() {
    }

    public record WebhookPayload(String json, boolean hasComponents, String threadId) {
    }

    public static List<WebhookPayload> buildPayloads(DiscordConfig cfg,
                                                     DiscordConfig.WebhookProfile profile,
                                                     DiscordTemplate template,
                                                     PodiumDefinition def,
                                                     PodiumSnapshot snapshot,
                                                     int onlineCount,
                                                     int offlineCount,
                                                     ZoneId zone) {
        DiscordTemplate tpl = template != null ? template : DiscordTemplate.minimalFallback();
        Context ctx = buildContext(cfg, profile, def, snapshot, onlineCount, offlineCount, zone);

        if (tpl.isSuppressEmbeds()) {
            String msgContent = truncate(DiscordTextSanitizer.sanitizeTemplateOutput(applyVars(tpl.getContent(), ctx)), MAX_CONTENT);
            if (msgContent.isEmpty()) {
                msgContent = truncate(DiscordTextSanitizer.sanitizeTemplateOutput(applyVars(tpl.getTitle(), ctx)), MAX_CONTENT);
            }
            return List.of(serializeMessage(ctx, tpl, msgContent, List.of(), true));
        }

        List<EmbedObject> embeds = switch (tpl.getLayoutMode()) {
            case LIST_FIELD -> buildListFieldEmbeds(cfg, tpl, def, snapshot, ctx);
            case PER_ENTRY_FIELD -> buildPerEntryFieldEmbeds(cfg, tpl, def, snapshot, ctx);
            case PER_ENTRY_EMBED -> buildPerEntryEmbeds(cfg, tpl, def, snapshot, ctx);
        };

        // Group embeds into messages respecting MAX_EMBEDS_PER_MESSAGE (10) and MAX_TOTAL_CHARS (6000)
        List<List<EmbedObject>> messageBatches = new ArrayList<>();
        List<EmbedObject> currentBatch = new ArrayList<>();
        int currentChars = 0;

        for (EmbedObject embed : embeds) {
            int cost = embed.charCount();
            if (!currentBatch.isEmpty()
                    && (currentBatch.size() >= MAX_EMBEDS_PER_MESSAGE || currentChars + cost > MAX_TOTAL_CHARS)) {
                if (tpl.getOverflowPolicy() == DiscordTemplate.OverflowPolicy.TRUNCATE) {
                    break;
                }
                messageBatches.add(currentBatch);
                currentBatch = new ArrayList<>();
                currentChars = 0;
            }
            currentBatch.add(embed);
            currentChars += cost;
        }
        if (!currentBatch.isEmpty()) {
            messageBatches.add(currentBatch);
        }
        if (messageBatches.isEmpty()) {
            messageBatches.add(List.of());
        }

        String content = truncate(DiscordTextSanitizer.sanitizeTemplateOutput(applyVars(tpl.getContent(), ctx)), MAX_CONTENT);
        List<WebhookPayload> result = new ArrayList<>(messageBatches.size());
        for (int i = 0; i < messageBatches.size(); i++) {
            result.add(serializeMessage(ctx, tpl, i == 0 ? content : "", messageBatches.get(i), i == 0));
        }
        return result;
    }

    public static List<String> buildPreviewLines(DiscordConfig cfg,
                                                 DiscordConfig.WebhookProfile profile,
                                                 DiscordTemplate template,
                                                 PodiumDefinition def,
                                                 PodiumSnapshot snapshot,
                                                 int onlineCount,
                                                 int offlineCount,
                                                 ZoneId zone) {
        DiscordTemplate tpl = template != null ? template : DiscordTemplate.minimalFallback();
        Context ctx = buildContext(cfg, profile, def, snapshot, onlineCount, offlineCount, zone);
        List<String> out = new ArrayList<>();

        out.add("Template: " + tpl.getId() + " (Layout: " + tpl.getLayoutMode() + ", Overflow: " + tpl.getOverflowPolicy() + ")");
        if (!ctx.threadId.isEmpty()) out.add("Thread ID: " + ctx.threadId);
        if (!ctx.threadName.isEmpty()) out.add("Forum Thread Name: " + ctx.threadName);
        if (!tpl.getContent().isBlank()) {
            out.add("Content: " + DiscordTextSanitizer.sanitizeTemplateOutput(applyVars(tpl.getContent(), ctx)));
        }
        out.add("Title: " + DiscordTextSanitizer.sanitizeTemplateOutput(applyVars(tpl.getTitle(), ctx)));
        String desc = applyVars(tpl.getDescription(), ctx);
        if (!desc.isBlank()) {
            out.add("Description: " + DiscordTextSanitizer.sanitizeTemplateOutput(desc));
        }
        for (RenderedEntry re : renderAllEntries(cfg, tpl, def, snapshot)) {
            out.add(re.line());
        }
        if (snapshot.getEntries().isEmpty() && snapshot.getSkippedEntries().isEmpty()) {
            out.add(DiscordTextSanitizer.sanitizeTemplateOutput(tpl.getEmptyMessage()));
        }
        if (!tpl.getLinkButtons().isEmpty()) {
            out.add("Link Buttons: " + tpl.getLinkButtons().size());
        }
        out.add("Footer: " + DiscordTextSanitizer.sanitizeTemplateOutput(applyVars(tpl.getFooterText(), ctx)));
        return out;
    }

    /**
     * Validates a template against official Discord API limits, returning English warnings with fix hints.
     */
    public static List<String> validateTemplate(DiscordTemplate tpl) {
        List<String> issues = new ArrayList<>();
        String prefix = "[discord.yml] Template '" + tpl.getId() + "'";
        if (tpl.getContent().length() > MAX_CONTENT) {
            issues.add(prefix + " 'content' exceeds " + MAX_CONTENT + " chars (" + tpl.getContent().length() + "). Fix: Shorten 'content'.");
        }
        if (tpl.getTitle().length() > MAX_TITLE) {
            issues.add(prefix + " 'title' exceeds " + MAX_TITLE + " chars (" + tpl.getTitle().length() + "). Fix: Shorten 'title'.");
        }
        if (tpl.getDescription().length() > MAX_DESCRIPTION) {
            issues.add(prefix + " 'description' exceeds " + MAX_DESCRIPTION + " chars (" + tpl.getDescription().length() + "). Fix: Shorten 'description'.");
        }
        if (tpl.getAuthorName().length() > MAX_AUTHOR_NAME) {
            issues.add(prefix + " 'author.name' exceeds " + MAX_AUTHOR_NAME + " chars (" + tpl.getAuthorName().length() + ").");
        }
        if (tpl.getFooterText().length() > MAX_FOOTER) {
            issues.add(prefix + " 'footer-text' exceeds " + MAX_FOOTER + " chars (" + tpl.getFooterText().length() + ").");
        }
        if (tpl.getCustomFields().size() > MAX_FIELDS_PER_EMBED) {
            issues.add(prefix + " 'custom-fields' has " + tpl.getCustomFields().size() + " fields (Discord max is " + MAX_FIELDS_PER_EMBED + ").");
        }
        if (tpl.getLinkButtons().size() > MAX_LINK_BUTTONS) {
            issues.add(prefix + " 'link-buttons' has " + tpl.getLinkButtons().size() + " buttons (Discord max per Action Row is " + MAX_LINK_BUTTONS + ").");
        }
        for (DiscordTemplate.LinkButton btn : tpl.getLinkButtons()) {
            if (btn.getLabel() == null || btn.getLabel().isBlank() || btn.getLabel().length() > 80) {
                issues.add(prefix + " link-button label '" + btn.getLabel() + "' must be 1..80 characters.");
            }
            if (!btn.getUrl().contains("{") && safeHttpUrl(btn.getUrl()) == null) {
                issues.add(prefix + " link-button URL '" + btn.getUrl() + "' must start with http:// or https://.");
            }
        }
        return issues;
    }

    private static List<EmbedObject> buildListFieldEmbeds(DiscordConfig cfg,
                                                          DiscordTemplate tpl,
                                                          PodiumDefinition def,
                                                          PodiumSnapshot snapshot,
                                                          Context ctx) {
        List<RenderedEntry> entries = renderAllEntries(cfg, tpl, def, snapshot);
        List<String> lines = entries.stream().map(RenderedEntry::line).toList();
        List<String> chunks = chunkLines(lines, MAX_FIELD_VALUE);
        if (chunks.isEmpty()) {
            chunks = List.of(truncate(DiscordTextSanitizer.sanitizeTemplateOutput(applyVars(tpl.getEmptyMessage(), ctx)), MAX_FIELD_VALUE));
        }

        List<FieldEntry> allFields = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String fName = chunks.size() > 1 ? tpl.getFieldTitle() + " (" + (i + 1) + "/" + chunks.size() + ")" : tpl.getFieldTitle();
            allFields.add(new FieldEntry(
                    truncate(DiscordTextSanitizer.sanitizeTemplateOutput(applyVars(fName, ctx)), MAX_FIELD_NAME),
                    chunks.get(i),
                    false
            ));
        }
        appendCustomFields(allFields, tpl, ctx);
        return partitionFieldsIntoEmbeds(tpl, def, snapshot, ctx, allFields);
    }

    private static List<EmbedObject> buildPerEntryFieldEmbeds(DiscordConfig cfg,
                                                              DiscordTemplate tpl,
                                                              PodiumDefinition def,
                                                              PodiumSnapshot snapshot,
                                                              Context ctx) {
        List<RenderedEntry> entries = renderAllEntries(cfg, tpl, def, snapshot);
        List<FieldEntry> allFields = new ArrayList<>();
        if (entries.isEmpty()) {
            allFields.add(new FieldEntry(
                    truncate(DiscordTextSanitizer.sanitizeTemplateOutput(applyVars(tpl.getFieldTitle(), ctx)), MAX_FIELD_NAME),
                    truncate(DiscordTextSanitizer.sanitizeTemplateOutput(applyVars(tpl.getEmptyMessage(), ctx)), MAX_FIELD_VALUE),
                    false
            ));
        } else {
            for (RenderedEntry re : entries) {
                String fName = tpl.getFieldTitle()
                        .replace("{rank}", String.valueOf(re.rank()))
                        .replace("{rank_icon}", cfg.getRankIcon(re.rank()))
                        .replace("{player}", re.safePlayer());
                allFields.add(new FieldEntry(
                        truncate(DiscordTextSanitizer.sanitizeTemplateOutput(applyVars(fName, ctx)), MAX_FIELD_NAME),
                        truncate(re.line(), MAX_FIELD_VALUE),
                        tpl.isInlineEntryFields()
                ));
            }
        }
        appendCustomFields(allFields, tpl, ctx);
        return partitionFieldsIntoEmbeds(tpl, def, snapshot, ctx, allFields);
    }

    private static List<EmbedObject> buildPerEntryEmbeds(DiscordConfig cfg,
                                                         DiscordTemplate tpl,
                                                         PodiumDefinition def,
                                                         PodiumSnapshot snapshot,
                                                         Context ctx) {
        List<EmbedObject> embeds = new ArrayList<>();
        // Header embed
        embeds.add(buildHeaderEmbed(tpl, def, snapshot, ctx, List.of(), true, 0, 1));

        ValueFormatSpec fmt = def.getValueFormat() != null ? def.getValueFormat() : ValueFormatSpec.DEFAULT;
        for (PodiumSnapshot.Entry e : snapshot.getEntries()) {
            String safePlayer = DiscordTextSanitizer.sanitizeUntrusted(e.getPlayerName());
            String formattedVal = DiscordTextSanitizer.sanitizeUntrusted(fmt.format(e.getValue()));
            String status = e.isOnline() ? cfg.getStatusOnline() : cfg.getStatusOffline();
            String rankIcon = cfg.getRankIcon(e.getRank());

            String eTitle = truncate(DiscordTextSanitizer.sanitizeTemplateOutput(
                    tpl.getEntryEmbedTitle()
                            .replace("{rank}", String.valueOf(e.getRank()))
                            .replace("{rank_icon}", rankIcon)
                            .replace("{player}", safePlayer)
                            .replace("{value}", formattedVal)
                            .replace("{status}", status)), MAX_TITLE);

            String eDesc = truncate(DiscordTextSanitizer.sanitizeTemplateOutput(
                    tpl.getEntryEmbedDescription()
                            .replace("{rank}", String.valueOf(e.getRank()))
                            .replace("{rank_icon}", rankIcon)
                            .replace("{player}", safePlayer)
                            .replace("{value}", formattedVal)
                            .replace("{status}", status)), MAX_DESCRIPTION);

            String thumb = cfg.isPlayerAvatarEnabled()
                    ? safeHttpUrl(cfg.getPlayerAvatarUrlTemplate()
                    .replace("{player}", urlEncode(e.getPlayerName()))
                    .replace("{uuid}", e.getPlayerUuid() != null ? e.getPlayerUuid().toString() : "Steve"))
                    : null;

            int color = tpl.resolveColor(def.getDiscordColor(), e.getRank());
            embeds.add(new EmbedObject(color, eTitle, null, eDesc, null, null, null, thumb, null, null, null, null, List.of()));
        }
        return embeds;
    }

    private static List<EmbedObject> partitionFieldsIntoEmbeds(DiscordTemplate tpl,
                                                               PodiumDefinition def,
                                                               PodiumSnapshot snapshot,
                                                               Context ctx,
                                                               List<FieldEntry> fields) {
        List<List<FieldEntry>> groups = new ArrayList<>();
        List<FieldEntry> cur = new ArrayList<>();
        int curChars = 0;
        for (FieldEntry f : fields) {
            int cost = f.name().length() + f.value().length();
            if (!cur.isEmpty() && (cur.size() >= MAX_FIELDS_PER_EMBED || curChars + cost > MAX_TOTAL_CHARS - 600)) {
                if (tpl.getOverflowPolicy() == DiscordTemplate.OverflowPolicy.TRUNCATE) {
                    break;
                }
                groups.add(cur);
                cur = new ArrayList<>();
                curChars = 0;
            }
            cur.add(f);
            curChars += cost;
        }
        if (!cur.isEmpty()) {
            groups.add(cur);
        }
        List<EmbedObject> embeds = new ArrayList<>(groups.size());
        for (int i = 0; i < groups.size(); i++) {
            embeds.add(buildHeaderEmbed(tpl, def, snapshot, ctx, groups.get(i), i == 0, i, groups.size()));
        }
        return embeds;
    }

    private static EmbedObject buildHeaderEmbed(DiscordTemplate tpl,
                                                PodiumDefinition def,
                                                PodiumSnapshot snapshot,
                                                Context ctx,
                                                List<FieldEntry> fields,
                                                boolean includeHeaderDetails,
                                                int index,
                                                int total) {
        String title = truncate(DiscordTextSanitizer.sanitizeTemplateOutput(applyVars(tpl.getTitle(), ctx)), MAX_TITLE);
        if (total > 1) {
            title = truncate(title + " (" + (index + 1) + "/" + total + ")", MAX_TITLE);
        }
        String titleUrl = includeHeaderDetails ? safeHttpUrl(applyVars(tpl.getTitleUrl(), ctx)) : null;
        String desc = includeHeaderDetails ? truncate(DiscordTextSanitizer.sanitizeTemplateOutput(applyVars(tpl.getDescription(), ctx)), MAX_DESCRIPTION) : "";
        String authorName = includeHeaderDetails ? truncate(DiscordTextSanitizer.sanitizeTemplateOutput(applyVars(tpl.getAuthorName(), ctx)), MAX_AUTHOR_NAME) : "";
        String authorUrl = includeHeaderDetails ? safeHttpUrl(applyVars(tpl.getAuthorUrl(), ctx)) : null;
        String authorIcon = includeHeaderDetails ? safeHttpUrl(applyVars(tpl.getAuthorIconUrl(), ctx)) : null;
        String thumb = includeHeaderDetails ? resolveThumbnailUrl(ctx.cfg, tpl, snapshot, ctx) : null;
        String image = includeHeaderDetails ? safeHttpUrl(applyVars(tpl.getImageUrl(), ctx)) : null;
        String footer = truncate(DiscordTextSanitizer.sanitizeTemplateOutput(applyVars(tpl.getFooterText(), ctx)), MAX_FOOTER);
        String footerIcon = safeHttpUrl(applyVars(tpl.getFooterIconUrl(), ctx));
        String ts = tpl.isIncludeTimestamp() ? snapshot.getCapturedAt().toString() : null;
        Integer topRank = snapshot.getEntries().isEmpty() ? null : snapshot.getEntries().get(0).getRank();
        int color = tpl.resolveColor(def.getDiscordColor(), topRank);

        return new EmbedObject(color, title, titleUrl, desc, authorName, authorUrl, authorIcon,
                thumb, image, footer, footerIcon, ts, fields);
    }

    private static void appendCustomFields(List<FieldEntry> out, DiscordTemplate tpl, Context ctx) {
        if (tpl.getCustomFields() == null) return;
        for (DiscordTemplate.CustomField cf : tpl.getCustomFields()) {
            String name = truncate(DiscordTextSanitizer.sanitizeTemplateOutput(applyVars(cf.getName(), ctx)), MAX_FIELD_NAME);
            String val = truncate(DiscordTextSanitizer.sanitizeTemplateOutput(applyVars(cf.getValue(), ctx)), MAX_FIELD_VALUE);
            if (!name.isEmpty() && !val.isEmpty()) {
                out.add(new FieldEntry(name, val, cf.isInline()));
            }
        }
    }

    private static List<RenderedEntry> renderAllEntries(DiscordConfig cfg,
                                                      DiscordTemplate tpl,
                                                      PodiumDefinition def,
                                                      PodiumSnapshot snapshot) {
        List<RenderedEntry> list = new ArrayList<>();
        ValueFormatSpec fmt = def.getValueFormat() != null ? def.getValueFormat() : ValueFormatSpec.DEFAULT;

        for (PodiumSnapshot.Entry e : snapshot.getEntries()) {
            String safePlayer = DiscordTextSanitizer.sanitizeUntrusted(e.getPlayerName());
            String formattedVal = DiscordTextSanitizer.sanitizeUntrusted(fmt.format(e.getValue()));
            String status = e.isOnline() ? cfg.getStatusOnline() : cfg.getStatusOffline();
            String rankIcon = cfg.getRankIcon(e.getRank());

            String line = tpl.getEntryFormatForRank(e.getRank())
                    .replace("{rank}", String.valueOf(e.getRank()))
                    .replace("{rank_icon}", rankIcon)
                    .replace("{player}", safePlayer)
                    .replace("{value}", formattedVal)
                    .replace("{status}", status);
            list.add(new RenderedEntry(e.getRank(), safePlayer, DiscordTextSanitizer.sanitizeTemplateOutput(line)));
        }

        if (tpl.isIncludeSkipped()) {
            for (PodiumSnapshot.SkippedEntry s : snapshot.getSkippedEntries()) {
                String safePlayer = DiscordTextSanitizer.sanitizeUntrusted(s.getPlayerName());
                String formattedVal = DiscordTextSanitizer.sanitizeUntrusted(fmt.format(s.getValue()));
                String safeReason = DiscordTextSanitizer.sanitizeUntrusted(s.getReason());
                String status = cfg.getStatusSkipped().replace("{reason}", safeReason);
                String rankIcon = cfg.getRankIcon(s.getRank());

                String line = tpl.getSkippedFormat()
                        .replace("{rank}", String.valueOf(s.getRank()))
                        .replace("{rank_icon}", rankIcon)
                        .replace("{player}", safePlayer)
                        .replace("{value}", formattedVal)
                        .replace("{reason}", safeReason)
                        .replace("{status}", status);
                list.add(new RenderedEntry(s.getRank(), safePlayer, DiscordTextSanitizer.sanitizeTemplateOutput(line)));
            }
        }
        return list;
    }

    private static WebhookPayload serializeMessage(Context ctx,
                                                   DiscordTemplate tpl,
                                                   String content,
                                                   List<EmbedObject> embeds,
                                                   boolean isFirstMessage) {
        StringBuilder json = new StringBuilder(1024);
        json.append('{');

        if (ctx.profile != null) {
            String user = truncate(DiscordTextSanitizer.stripMinecraft(applyVars(ctx.profile.getUsername(), ctx)), MAX_USERNAME);
            if (!user.isEmpty()) {
                json.append("\"username\":").append(jsonStr(user)).append(',');
            }
            String avatar = safeHttpUrl(applyVars(ctx.profile.getAvatarUrl(), ctx));
            if (avatar != null) {
                json.append("\"avatar_url\":").append(jsonStr(avatar)).append(',');
            }
        }

        if (content != null && !content.isEmpty()) {
            json.append("\"content\":").append(jsonStr(content)).append(',');
        }
        if (tpl.isTts()) {
            json.append("\"tts\":true,");
        }

        int flags = 0;
        if (tpl.isSuppressEmbeds()) flags |= FLAG_SUPPRESS_EMBEDS;
        if (tpl.isSuppressNotifications()) flags |= FLAG_SUPPRESS_NOTIFICATIONS;
        if (flags != 0) {
            json.append("\"flags\":").append(flags).append(',');
        }

        // Forum thread creation only on first message and when thread_id is not set
        if (isFirstMessage && ctx.threadId.isEmpty() && !ctx.threadName.isEmpty()) {
            json.append("\"thread_name\":").append(jsonStr(truncate(ctx.threadName, 100))).append(',');
            if (!ctx.appliedTags.isEmpty()) {
                json.append("\"applied_tags\":[");
                for (int i = 0; i < ctx.appliedTags.size(); i++) {
                    if (i > 0) json.append(',');
                    json.append(jsonStr(ctx.appliedTags.get(i)));
                }
                json.append("],");
            }
        }

        // Allowed mentions: parse=[] by default, optional explicit role/user snowflake IDs
        json.append("\"allowed_mentions\":{\"parse\":[]");
        if (!tpl.getMentionRoles().isEmpty()) {
            json.append(",\"roles\":[");
            appendSnowflakeArray(json, tpl.getMentionRoles());
            json.append(']');
        }
        if (!tpl.getMentionUsers().isEmpty()) {
            json.append(",\"users\":[");
            appendSnowflakeArray(json, tpl.getMentionUsers());
            json.append(']');
        }
        json.append("}");

        if (!embeds.isEmpty()) {
            json.append(",\"embeds\":[");
            for (int i = 0; i < embeds.size(); i++) {
                if (i > 0) json.append(',');
                embeds.get(i).appendJson(json);
            }
            json.append(']');
        }

        // Action Row with Link Buttons (style 5)
        List<DiscordTemplate.LinkButton> validButtons = new ArrayList<>();
        if (isFirstMessage && tpl.getLinkButtons() != null) {
            for (DiscordTemplate.LinkButton btn : tpl.getLinkButtons()) {
                if (validButtons.size() >= MAX_LINK_BUTTONS) break;
                String btnUrl = safeHttpUrl(applyVars(btn.getUrl(), ctx));
                String btnLabel = truncate(DiscordTextSanitizer.stripMinecraft(applyVars(btn.getLabel(), ctx)), 80);
                if (btnUrl != null && !btnLabel.isEmpty()) {
                    validButtons.add(DiscordTemplate.LinkButton.builder()
                            .label(btnLabel).url(btnUrl).emoji(btn.getEmoji()).build());
                }
            }
        }
        if (!validButtons.isEmpty()) {
            json.append(",\"components\":[{\"type\":1,\"components\":[");
            for (int i = 0; i < validButtons.size(); i++) {
                if (i > 0) json.append(',');
                DiscordTemplate.LinkButton b = validButtons.get(i);
                json.append("{\"type\":2,\"style\":5,\"label\":").append(jsonStr(b.getLabel()))
                        .append(",\"url\":").append(jsonStr(b.getUrl()));
                if (b.getEmoji() != null && !b.getEmoji().isBlank()) {
                    json.append(",\"emoji\":{\"name\":").append(jsonStr(b.getEmoji().trim())).append('}');
                }
                json.append('}');
            }
            json.append("]}]");
        }

        json.append('}');
        return new WebhookPayload(json.toString(), !validButtons.isEmpty(), ctx.threadId);
    }

    private static void appendSnowflakeArray(StringBuilder sb, List<String> ids) {
        boolean first = true;
        for (String id : ids) {
            if (id != null && id.trim().matches("^[0-9]{10,30}$")) {
                if (!first) sb.append(',');
                sb.append(jsonStr(id.trim()));
                first = false;
            }
        }
    }

    private static Context buildContext(DiscordConfig cfg,
                                        DiscordConfig.WebhookProfile profile,
                                        PodiumDefinition def,
                                        PodiumSnapshot snapshot,
                                        int onlineCount,
                                        int offlineCount,
                                        ZoneId zone) {
        ZonedDateTime zdt = snapshot.getCapturedAt().atZone(zone);
        long epoch = snapshot.getCapturedAt().getEpochSecond();
        String threadId = !def.getDiscordThreadId().isBlank()
                ? def.getDiscordThreadId().trim()
                : (profile != null && profile.getThreadId() != null ? profile.getThreadId().trim() : "");
        String threadName = !def.getDiscordThreadName().isBlank()
                ? def.getDiscordThreadName().trim()
                : (profile != null && profile.getThreadName() != null ? profile.getThreadName().trim() : "");
        List<String> tags = !def.getDiscordAppliedTags().isEmpty()
                ? def.getDiscordAppliedTags()
                : (profile != null ? profile.getAppliedTags() : List.of());

        Context ctx = new Context(
                cfg, profile, def.getId(),
                DiscordTextSanitizer.stripMinecraft(def.getDisplayName()),
                DATE_FMT.format(zdt), TIME_FMT.format(zdt), zone.getId(),
                onlineCount, offlineCount, snapshot.getSkippedEntries().size(),
                snapshot.getEntries().size() + snapshot.getSkippedEntries().size(),
                epoch, threadId, "", tags
        );
        return new Context(
                cfg, profile, ctx.podiumId, ctx.podiumName, ctx.date, ctx.time, ctx.timezone,
                ctx.onlineCount, ctx.offlineCount, ctx.skippedCount, ctx.totalCount,
                epoch, threadId, DiscordTextSanitizer.stripMinecraft(applyVars(threadName, ctx)), tags
        );
    }

    private static String applyVars(String template, Context ctx) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        String out = template
                .replace("{podium}", ctx.podiumId)
                .replace("{podium_name}", ctx.podiumName)
                .replace("{date}", ctx.date)
                .replace("{time}", ctx.time)
                .replace("{timezone}", ctx.timezone)
                .replace("{online_count}", String.valueOf(ctx.onlineCount))
                .replace("{offline_count}", String.valueOf(ctx.offlineCount))
                .replace("{skipped_count}", String.valueOf(ctx.skippedCount))
                .replace("{total_count}", String.valueOf(ctx.totalCount))
                .replace("{timestamp}", "<t:" + ctx.epochSeconds + ">")
                .replace("{timestamp_relative}", "<t:" + ctx.epochSeconds + ":R>")
                .replace("{timestamp_full}", "<t:" + ctx.epochSeconds + ":F>")
                .replace("{timestamp_short}", "<t:" + ctx.epochSeconds + ":f>")
                .replace("{timestamp_date}", "<t:" + ctx.epochSeconds + ":D>")
                .replace("{timestamp_time}", "<t:" + ctx.epochSeconds + ":T>");
        if (out.contains("%") && isPlaceholderApiPresent() && isPrimaryThreadSafe()) {
            try {
                out = PlaceholderAPI.setPlaceholders(null, out);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static boolean isPrimaryThreadSafe() {
        try {
            return Bukkit.getServer() == null || Bukkit.isPrimaryThread();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isPlaceholderApiPresent() {
        try {
            return Bukkit.getServer() != null && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String resolveThumbnailUrl(DiscordConfig cfg,
                                              DiscordTemplate tpl,
                                              PodiumSnapshot snapshot,
                                              Context ctx) {
        return switch (tpl.getThumbnailMode()) {
            case NONE -> null;
            case CUSTOM_URL -> safeHttpUrl(applyVars(tpl.getThumbnailUrl(), ctx));
            case TOP_1_PLAYER -> {
                if (!cfg.isPlayerAvatarEnabled() || snapshot.getEntries().isEmpty()) {
                    yield null;
                }
                PodiumSnapshot.Entry top1 = snapshot.getEntries().get(0);
                String avatarTpl = cfg.getPlayerAvatarUrlTemplate();
                if (avatarTpl == null || avatarTpl.isBlank()) {
                    yield null;
                }
                String uuid = top1.getPlayerUuid() != null ? top1.getPlayerUuid().toString() : "Steve";
                yield safeHttpUrl(avatarTpl.replace("{player}", urlEncode(top1.getPlayerName())).replace("{uuid}", uuid));
            }
        };
    }

    private static String urlEncode(String s) {
        if (s == null) return "";
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String safeHttpUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return null;
            }
            return uri.toASCIIString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<String> chunkLines(List<String> lines, int maxCharsPerField) {
        List<String> chunks = new ArrayList<>();
        if (lines.isEmpty()) return chunks;
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            String safeLine = truncate(line, maxCharsPerField);
            int needed = current.isEmpty() ? safeLine.length() : safeLine.length() + 1;
            if (!current.isEmpty() && current.length() + needed > maxCharsPerField) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) current.append('\n');
            current.append(safeLine);
        }
        if (!current.isEmpty()) chunks.add(current.toString());
        return chunks;
    }

    static String jsonStr(String s) {
        if (s == null) return "\"\"";
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
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
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

    private record RenderedEntry(int rank, String safePlayer, String line) {
    }

    private record FieldEntry(String name, String value, boolean inline) {
    }

    private record Context(
            DiscordConfig cfg,
            DiscordConfig.WebhookProfile profile,
            String podiumId,
            String podiumName,
            String date,
            String time,
            String timezone,
            int onlineCount,
            int offlineCount,
            int skippedCount,
            int totalCount,
            long epochSeconds,
            String threadId,
            String threadName,
            List<String> appliedTags
    ) {
    }

    private record EmbedObject(
            int color,
            String title,
            String titleUrl,
            String description,
            String authorName,
            String authorUrl,
            String authorIcon,
            String thumbnailUrl,
            String imageUrl,
            String footerText,
            String footerIcon,
            String timestampIso,
            List<FieldEntry> fields
    ) {
        int charCount() {
            int sum = (title != null ? title.length() : 0)
                    + (description != null ? description.length() : 0)
                    + (authorName != null ? authorName.length() : 0)
                    + (footerText != null ? footerText.length() : 0);
            for (FieldEntry f : fields) {
                sum += f.name().length() + f.value().length();
            }
            return sum;
        }

        void appendJson(StringBuilder json) {
            json.append("{\"color\":").append(color);
            if (authorName != null && !authorName.isEmpty()) {
                json.append(",\"author\":{\"name\":").append(jsonStr(authorName));
                if (authorUrl != null) json.append(",\"url\":").append(jsonStr(authorUrl));
                if (authorIcon != null) json.append(",\"icon_url\":").append(jsonStr(authorIcon));
                json.append('}');
            }
            if (title != null && !title.isEmpty()) {
                json.append(",\"title\":").append(jsonStr(title));
            }
            if (titleUrl != null) {
                json.append(",\"url\":").append(jsonStr(titleUrl));
            }
            if (description != null && !description.isEmpty()) {
                json.append(",\"description\":").append(jsonStr(description));
            }
            if (thumbnailUrl != null) {
                json.append(",\"thumbnail\":{\"url\":").append(jsonStr(thumbnailUrl)).append('}');
            }
            if (imageUrl != null) {
                json.append(",\"image\":{\"url\":").append(jsonStr(imageUrl)).append('}');
            }
            if (timestampIso != null) {
                json.append(",\"timestamp\":").append(jsonStr(timestampIso));
            }
            if (footerText != null && !footerText.isEmpty()) {
                json.append(",\"footer\":{\"text\":").append(jsonStr(footerText));
                if (footerIcon != null) json.append(",\"icon_url\":").append(jsonStr(footerIcon));
                json.append('}');
            }
            if (!fields.isEmpty()) {
                json.append(",\"fields\":[");
                for (int i = 0; i < fields.size(); i++) {
                    if (i > 0) json.append(',');
                    FieldEntry f = fields.get(i);
                    json.append("{\"name\":").append(jsonStr(f.name()))
                            .append(",\"value\":").append(jsonStr(f.value()))
                            .append(",\"inline\":").append(f.inline()).append('}');
                }
                json.append(']');
            }
            json.append('}');
        }
    }
}
