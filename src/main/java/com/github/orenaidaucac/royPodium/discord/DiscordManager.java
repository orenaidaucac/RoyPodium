package com.github.orenaidaucac.royPodium.discord;

import com.github.orenaidaucac.royPodium.config.ConfigManager;
import com.github.orenaidaucac.royPodium.config.DiscordConfig;
import com.github.orenaidaucac.royPodium.podium.PodiumDefinition;
import com.github.orenaidaucac.royPodium.podium.PodiumSnapshot;
import com.github.orenaidaucac.royPodium.scheduler.ScheduleSpec;
import com.github.orenaidaucac.royPodium.scheduler.SchedulerAdapter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Coordinates Discord Webhook payout notifications, test previews, cooldowns, and lifecycle.
 */
public final class DiscordManager {

    private final ConfigManager configManager;
    private final SchedulerAdapter scheduler;
    private final DiscordWebhookSender sender;
    private final Logger logger;

    private final Map<String, Long> testCooldowns = new ConcurrentHashMap<>();

    public DiscordManager(ConfigManager configManager, SchedulerAdapter scheduler, Logger logger) {
        this.configManager = configManager;
        this.scheduler = scheduler;
        this.logger = logger;
        this.sender = new DiscordWebhookSender(logger);
    }

    /**
     * Dispatches Discord webhook notification(s) asynchronously after a live payout.
     * Never throws or blocks the payout flow.
     */
    public void notifyPayout(PodiumDefinition def, PodiumSnapshot snapshot, int onlineCount, int offlineCount) {
        try {
            DiscordConfig cfg = configManager.getDiscordConfig();
            if (cfg == null || !cfg.isEnabled() || !def.isDiscordEnabled()) {
                return;
            }
            List<DiscordConfig.WebhookProfile> targets = resolveProfiles(cfg, def);
            if (targets.isEmpty()) {
                return;
            }
            ZoneId zone = resolveZone(def);
            for (DiscordConfig.WebhookProfile profile : targets) {
                List<String> payloads = DiscordEmbedBuilder.buildPayloads(
                        cfg, profile, def, snapshot, onlineCount, offlineCount, zone
                );
                sender.enqueue(profile, payloads, cfg);
            }
        } catch (Throwable ex) {
            logger.warning("Failed to prepare Discord webhook notification for podium '" + def.getId() + "': " + ex.getMessage());
        }
    }

    /**
     * Previews or sends a test Discord webhook without awarding any rewards or mutating queues.
     */
    public void runTest(CommandSender feedbackTarget,
                        PodiumDefinition def,
                        PodiumSnapshot snapshot,
                        String webhookIdOpt,
                        boolean sendLive) {
        DiscordConfig cfg = configManager.getDiscordConfig();
        var msgs = configManager.getMessages();

        if (cfg == null) {
            return;
        }

        ZoneId zone = resolveZone(def);
        int onlineCount = (int) snapshot.getEntries().stream().filter(PodiumSnapshot.Entry::isOnline).count();
        int offlineCount = snapshot.getEntries().size() - onlineCount;

        String targetName = resolveSingleProfileName(cfg, def, webhookIdOpt);
        if (!sendLive) {
            msgs.send(feedbackTarget, "webhook.preview-header",
                    "&8&m----&r &9Discord Preview: {podium} &7({webhook}) &8&m----",
                    Map.of("podium", def.getId(), "webhook", targetName));
            for (String line : DiscordEmbedBuilder.buildPreviewLines(cfg, def, snapshot, onlineCount, offlineCount, zone)) {
                msgs.send(feedbackTarget, "webhook.preview-line", "&7{line}",
                        Map.of(), Map.of("line", line));
            }
            msgs.send(feedbackTarget, "webhook.preview-footer", "&8&m----------------------------------------");
            return;
        }

        if (!cfg.isEnabled() || !def.isDiscordEnabled()) {
            msgs.send(feedbackTarget, "webhook.disabled",
                    "{prefix}&cDiscord webhooks are disabled in discord.yml (or for this podium).");
            return;
        }

        DiscordConfig.WebhookProfile profile = cfg.getWebhooks().get(targetName);
        if (profile == null) {
            msgs.send(feedbackTarget, "webhook.not-found",
                    "{prefix}&cWebhook profile '&e{webhook}&c' does not exist or has an invalid URL.",
                    Map.of("webhook", targetName));
            return;
        }

        // Enforce cooldown on live test send
        int cooldownSec = cfg.getTestCooldownSeconds();
        if (cooldownSec > 0) {
            String key = feedbackTarget instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";
            long now = System.currentTimeMillis();
            Long until = testCooldowns.get(key);
            if (until != null && now < until) {
                long rem = Math.max(1L, (until - now + 999L) / 1000L);
                msgs.send(feedbackTarget, "webhook.cooldown",
                        "{prefix}&cPlease wait &e{seconds}s&c before sending another test webhook.",
                        Map.of("seconds", String.valueOf(rem)));
                return;
            }
            testCooldowns.put(key, now + cooldownSec * 1000L);
        }

        msgs.send(feedbackTarget, "webhook.sending",
                "{prefix}&7Sending test Discord webhook '&e{webhook}&7' for podium &e{podium}&7...",
                Map.of("webhook", profile.getId(), "podium", def.getId()));

        List<String> payloads = DiscordEmbedBuilder.buildPayloads(
                cfg, profile, def, snapshot, onlineCount, offlineCount, zone
        );
        sender.sendTestAsync(profile, payloads, cfg).thenAccept(res -> {
            Runnable reply = () -> {
                if (res.success()) {
                    msgs.send(feedbackTarget, "webhook.send-success",
                            "{prefix}&aTest webhook '&e{webhook}&a' sent successfully (HTTP &e{code}&a).",
                            Map.of("webhook", profile.getId(), "code", String.valueOf(res.httpCode())));
                } else {
                    msgs.send(feedbackTarget, "webhook.send-failed",
                            "{prefix}&cFailed to send test webhook '&e{webhook}&c': &f{error}",
                            Map.of("webhook", profile.getId(), "error", res.errorMessage()));
                }
            };
            if (feedbackTarget instanceof Player p) {
                scheduler.runAtEntity(p, reply);
            } else {
                scheduler.runGlobal(reply);
            }
        });
    }

    public void reload() {
        testCooldowns.clear();
        DiscordConfig cfg = configManager.getDiscordConfig();
        int drain = cfg != null ? cfg.getShutdownDrainSeconds() : 5;
        sender.restartAfterReload(drain);
    }

    public void shutdown() {
        testCooldowns.clear();
        DiscordConfig cfg = configManager.getDiscordConfig();
        int drain = cfg != null ? cfg.getShutdownDrainSeconds() : 5;
        sender.shutdown(drain);
    }

    private ZoneId resolveZone(PodiumDefinition def) {
        ScheduleSpec spec = configManager.getSchedules().get(def.getScheduleId());
        return spec != null ? spec.getTimezone() : configManager.getMainConfig().getDefaultTimezone();
    }

    private List<DiscordConfig.WebhookProfile> resolveProfiles(DiscordConfig cfg, PodiumDefinition def) {
        List<DiscordConfig.WebhookProfile> list = new ArrayList<>();
        if (def.getDiscordWebhooks() != null && !def.getDiscordWebhooks().isEmpty()) {
            for (String id : def.getDiscordWebhooks()) {
                DiscordConfig.WebhookProfile p = cfg.getWebhooks().get(id);
                if (p != null) {
                    list.add(p);
                }
            }
            return list;
        }
        DiscordConfig.WebhookProfile defProfile = cfg.getWebhooks().get(cfg.getDefaultWebhook());
        if (defProfile != null) {
            list.add(defProfile);
        }
        return list;
    }

    private String resolveSingleProfileName(DiscordConfig cfg, PodiumDefinition def, String override) {
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        if (def.getDiscordWebhooks() != null && !def.getDiscordWebhooks().isEmpty()) {
            return def.getDiscordWebhooks().get(0);
        }
        return cfg.getDefaultWebhook() != null ? cfg.getDefaultWebhook() : "default";
    }
}
