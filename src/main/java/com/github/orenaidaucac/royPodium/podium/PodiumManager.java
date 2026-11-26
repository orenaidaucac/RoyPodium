package com.github.orenaidaucac.royPodium.podium;

import com.github.orenaidaucac.royPodium.config.ConfigManager;
import com.github.orenaidaucac.royPodium.config.MainConfig;
import com.github.orenaidaucac.royPodium.queue.CommandDispatchQueue;
import com.github.orenaidaucac.royPodium.queue.PendingReward;
import com.github.orenaidaucac.royPodium.queue.QueueManager;
import com.github.orenaidaucac.royPodium.scheduler.ScheduleSpec;
import com.github.orenaidaucac.royPodium.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Captures immutable leaderboard snapshots and dispatches rewards to online or offline players.
 */
public final class PodiumManager {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ConfigManager configManager;
    private final PlaceholderResolver placeholderResolver;
    private final CommandDispatchQueue dispatcher;
    private final QueueManager queueManager;

    public PodiumManager(ConfigManager configManager,
                         PlaceholderResolver placeholderResolver,
                         CommandDispatchQueue dispatcher,
                         QueueManager queueManager) {
        this.configManager = configManager;
        this.placeholderResolver = placeholderResolver;
        this.dispatcher = dispatcher;
        this.queueManager = queueManager;
    }

    /**
     * Captures a snapshot of {@code def} on the Global Region / Main Thread.
     */
    public PodiumSnapshot captureSnapshot(PodiumDefinition def, String payoutId) {
        MainConfig mainCfg = configManager.getMainConfig();
        Set<String> invalidValues = mainCfg.getInvalidPlaceholderValues();
        Set<UUID> seenUuids = new HashSet<>();

        ScheduleSpec spec = configManager.getSchedules().get(def.getScheduleId());
        ZoneId zone = spec != null ? spec.getTimezone() : mainCfg.getDefaultTimezone();
        ZonedDateTime nowZdt = Instant.now().atZone(zone);
        String dateStr = DATE_FMT.format(nowZdt);
        String timeStr = TIME_FMT.format(nowZdt);
        String strippedName = ColorUtil.strip(def.getDisplayName());

        List<PodiumSnapshot.Entry> entries = new ArrayList<>();
        List<PodiumSnapshot.SkippedEntry> skipped = new ArrayList<>();
        for (int rank = 1; rank <= def.getTopSize(); rank++) {
            String rawPlayer = placeholderResolver.resolveForRank(def.getPlayerPlaceholder(), rank);
            if (placeholderResolver.isInvalidValue(rawPlayer, invalidValues)) {
                continue;
            }

            String rawValue = def.getValuePlaceholder() == null || def.getValuePlaceholder().isBlank()
                    ? "0"
                    : placeholderResolver.resolveForRank(def.getValuePlaceholder(), rank);
            if (rawValue.isBlank()) {
                rawValue = "0";
            }

            // Evaluate podium conditions at snapshot time; failed players are skipped without shifting ranks
            String failureReason = evaluateConditions(def, rawValue);
            if (failureReason != null) {
                skipped.add(PodiumSnapshot.SkippedEntry.builder()
                        .rank(rank)
                        .playerName(rawPlayer)
                        .value(rawValue)
                        .reason(failureReason)
                        .build());
                continue;
            }

            Player onlinePlayer = Bukkit.getPlayerExact(rawPlayer);
            boolean isOnline = onlinePlayer != null && onlinePlayer.isOnline();
            UUID uuid = isOnline ? onlinePlayer.getUniqueId() : resolveOfflineUuid(rawPlayer);

            if (def.getTiePolicy() == TiePolicy.SKIP_DUPLICATE_PLAYER && uuid != null && !seenUuids.add(uuid)) {
                continue;
            }

            List<String> templateCmds = def.getCommandsForRank(rank);
            if (templateCmds.isEmpty()) {
                continue;
            }

            String uuidStr = uuid != null ? uuid.toString() : "UNKNOWN";
            List<String> resolvedCmds = new ArrayList<>(templateCmds.size());
            for (String cmd : templateCmds) {
                resolvedCmds.add(cmd
                        .replace("{player}", rawPlayer)
                        .replace("{uuid}", uuidStr)
                        .replace("{rank}", String.valueOf(rank))
                        .replace("{podium}", def.getId())
                        .replace("{podium_name}", strippedName)
                        .replace("{value}", rawValue)
                        .replace("{date}", dateStr)
                        .replace("{time}", timeStr));
            }

            entries.add(PodiumSnapshot.Entry.builder()
                    .rank(rank)
                    .playerName(rawPlayer)
                    .playerUuid(uuid)
                    .value(rawValue)
                    .online(isOnline)
                    .commands(resolvedCmds)
                    .build());
        }

        return PodiumSnapshot.builder()
                .podiumId(def.getId())
                .displayName(def.getDisplayName())
                .payoutId(payoutId)
                .capturedAt(Instant.now())
                .entries(List.copyOf(entries))
                .skippedEntries(List.copyOf(skipped))
                .build();
    }

    private String evaluateConditions(PodiumDefinition def, String rawValue) {
        if (def.getConditions() == null || def.getConditions().isEmpty()) {
            return null;
        }
        for (ConditionSpec cond : def.getConditions()) {
            ConditionSpec.EvaluationResult res = cond.evaluate(rawValue);
            if (!res.passed()) {
                return res.failureReason();
            }
        }
        return null;
    }

    /**
     * Executes or simulates a podium payout. Must be called from the Global Region / Main Thread.
     */
    public void runPayout(PodiumDefinition def, String payoutId, boolean simulate, CommandSender feedbackTarget) {
        if (!placeholderResolver.isPlaceholderApiAvailable()) {
            if (feedbackTarget != null) {
                configManager.getMessages().send(feedbackTarget, "general.papi-missing",
                        "{prefix}&cPlaceholderAPI is not installed! Leaderboard payouts are paused.");
            }
            return;
        }

        PodiumSnapshot snapshot = captureSnapshot(def, payoutId);
        if (simulate) {
            sendSimulationReport(snapshot, feedbackTarget);
            return;
        }

        int onlineCount = 0;
        int offlineCount = 0;
        long nowMs = System.currentTimeMillis();
        Duration exp = configManager.getMainConfig().getQueueExpiration();
        long expiresAtMs = (exp != null && !exp.isZero() && !exp.isNegative()) ? nowMs + exp.toMillis() : 0L;

        for (PodiumSnapshot.Entry entry : snapshot.getEntries()) {
            if (entry.isOnline()) {
                dispatcher.enqueueAll(entry.getCommands());
                onlineCount++;
            } else if (entry.getPlayerUuid() != null) {
                PendingReward reward = PendingReward.builder()
                        .entryId(UUID.randomUUID().toString())
                        .playerUuid(entry.getPlayerUuid())
                        .lastKnownName(entry.getPlayerName())
                        .podiumId(def.getId())
                        .rank(entry.getRank())
                        .value(entry.getValue())
                        .payoutId(payoutId)
                        .commands(entry.getCommands())
                        .createdAtEpochMs(nowMs)
                        .expiresAtEpochMs(expiresAtMs)
                        .build();
                if (queueManager.enqueueOffline(reward)) {
                    offlineCount++;
                }
            }
        }

        if (feedbackTarget != null) {
            configManager.getMessages().send(feedbackTarget, "test.executed",
                    "{prefix}&aExecuted payout for &e{podium}&a: &e{online} online&a, &e{offline} queued offline&a.",
                    Map.of(
                            "podium", def.getId(),
                            "online", String.valueOf(onlineCount),
                            "offline", String.valueOf(offlineCount)
                    ));
        }
    }

    private void sendSimulationReport(PodiumSnapshot snapshot, CommandSender target) {
        if (target == null) {
            return;
        }
        var msgs = configManager.getMessages();
        msgs.send(target, "test.sim-header", "&8&m----&r &6Simulation: {podium} &7({id}) &8&m----",
                Map.of("podium", snapshot.getDisplayName(), "id", snapshot.getPodiumId()));
        if (snapshot.getEntries().isEmpty() && snapshot.getSkippedEntries().isEmpty()) {
            msgs.send(target, "test.sim-empty", "&7No valid ranked players found for this podium.");
        } else {
            for (PodiumSnapshot.Entry e : snapshot.getEntries()) {
                msgs.send(target, "test.sim-entry", "&7#{rank} &f{player} &8(&7{value}&8) &7-> &e{status} &8[{commands} cmds]",
                        Map.of(
                                "rank", String.valueOf(e.getRank()),
                                "status", e.isOnline() ? "ONLINE (Immediate)" : "OFFLINE (Queued)",
                                "commands", String.valueOf(e.getCommands().size())
                        ),
                        Map.of(
                                "player", e.getPlayerName(),
                                "value", e.getValue()
                        ));
            }
            for (PodiumSnapshot.SkippedEntry s : snapshot.getSkippedEntries()) {
                msgs.send(target, "test.sim-skipped", "&7#{rank} &f{player} &8(&7{value}&8) &7-> &cSKIPPED &8({reason})",
                        Map.of(
                                "rank", String.valueOf(s.getRank()),
                                "reason", s.getReason()
                        ),
                        Map.of(
                                "player", s.getPlayerName(),
                                "value", s.getValue()
                        ));
            }
        }
        msgs.send(target, "test.sim-footer", "&8&m----------------------------------------");
    }

    @SuppressWarnings("deprecation")
    private UUID resolveOfflineUuid(String playerName) {
        try {
            // Paper API fast non-blocking cached lookup
            OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(playerName);
            if (cached != null) {
                return cached.getUniqueId();
            }
        } catch (NoSuchMethodError ignored) {
            // Spigot/Bukkit fallback
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(playerName);
        return offline.getUniqueId();
    }
}
