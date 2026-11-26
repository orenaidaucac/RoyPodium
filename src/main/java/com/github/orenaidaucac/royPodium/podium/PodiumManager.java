package com.github.orenaidaucac.royPodium.podium;

import com.github.orenaidaucac.royPodium.config.ConfigManager;
import com.github.orenaidaucac.royPodium.config.MainConfig;
import com.github.orenaidaucac.royPodium.discord.DiscordManager;
import com.github.orenaidaucac.royPodium.queue.CommandDispatchQueue;
import com.github.orenaidaucac.royPodium.queue.PendingReward;
import com.github.orenaidaucac.royPodium.queue.QueueManager;
import com.github.orenaidaucac.royPodium.scheduler.ScheduleSpec;
import com.github.orenaidaucac.royPodium.scheduler.SchedulerAdapter;
import com.github.orenaidaucac.royPodium.util.ColorUtil;
import com.github.orenaidaucac.royPodium.util.TpsMonitor;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Captures immutable leaderboard snapshots (with non-blocking UUID resolution and tick-budgeted
 * incremental scanning) and dispatches rewards to online or offline players.
 */
public final class PodiumManager {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ConfigManager configManager;
    private final PlaceholderResolver placeholderResolver;
    private final CommandDispatchQueue dispatcher;
    private final QueueManager queueManager;
    private DiscordManager discordManager;

    private final Set<String> activeRuns = ConcurrentHashMap.newKeySet();

    public PodiumManager(ConfigManager configManager,
                         PlaceholderResolver placeholderResolver,
                         CommandDispatchQueue dispatcher,
                         QueueManager queueManager) {
        this.configManager = configManager;
        this.placeholderResolver = placeholderResolver;
        this.dispatcher = dispatcher;
        this.queueManager = queueManager;
    }

    public void setDiscordManager(DiscordManager discordManager) {
        this.discordManager = discordManager;
    }

    public boolean isRunning(String key) {
        return activeRuns.contains(key);
    }

    /**
     * Captures a snapshot of {@code def} on the Global Region / Main Thread using non-blocking UUID lookups only.
     */
    public PodiumSnapshot captureSnapshot(PodiumDefinition def, String payoutId) {
        SnapshotAccumulator acc = new SnapshotAccumulator(def, payoutId);
        for (int rank = 1; rank <= def.getTopSize(); rank++) {
            processRank(acc, rank);
        }
        return acc.build();
    }

    /**
     * Incrementally captures a snapshot across ticks within {@code snapshot-ranks-per-tick} and {@code tick-budget-ms}
     * so large leaderboards never cause MSPT spikes.
     */
    public void captureSnapshotIncremental(PodiumDefinition def,
                                           String payoutId,
                                           SchedulerAdapter scheduler,
                                           Consumer<PodiumSnapshot> onComplete) {
        SnapshotAccumulator acc = new SnapshotAccumulator(def, payoutId);
        stepSnapshotChunk(acc, 1, scheduler, onComplete);
    }

    private void stepSnapshotChunk(SnapshotAccumulator acc,
                                   int startRank,
                                   SchedulerAdapter scheduler,
                                   Consumer<PodiumSnapshot> onComplete) {
        MainConfig mainCfg = configManager.getMainConfig();
        int topSize = acc.def.getTopSize();
        int budget = TpsMonitor.scaleBudget(mainCfg.getSnapshotRanksPerTick(), mainCfg.getMinTps());
        long deadlineNs = System.nanoTime() + (long) (mainCfg.getTickBudgetMs() * 1_000_000.0);

        int rank = startRank;
        int processed = 0;
        while (rank <= topSize && processed < budget) {
            if (processed > 0 && System.nanoTime() >= deadlineNs) {
                break;
            }
            processRank(acc, rank);
            rank++;
            processed++;
        }

        if (rank <= topSize) {
            final int nextRank = rank;
            scheduler.runGlobalLater(() -> stepSnapshotChunk(acc, nextRank, scheduler, onComplete), 1L);
        } else {
            onComplete.accept(acc.build());
        }
    }

    /**
     * Executes or simulates a batch of podiums incrementally across ticks with overlap protection.
     */
    public boolean runPayoutBatchAsync(String runKey,
                                       List<PodiumDefinition> defs,
                                       Function<PodiumDefinition, String> payoutIdFn,
                                       boolean simulate,
                                       CommandSender feedbackTarget,
                                       SchedulerAdapter scheduler) {
        if (!activeRuns.add(runKey)) {
            return false;
        }
        if (!placeholderResolver.isPlaceholderApiAvailable()) {
            activeRuns.remove(runKey);
            if (feedbackTarget != null) {
                configManager.getMessages().send(feedbackTarget, "general.papi-missing",
                        "{prefix}&cPlaceholderAPI is not installed! Leaderboard payouts are paused.");
            }
            return true;
        }
        scheduler.runGlobal(() -> runNextInBatch(runKey, defs, 0, payoutIdFn, simulate, feedbackTarget, scheduler));
        return true;
    }

    private void runNextInBatch(String runKey,
                                List<PodiumDefinition> defs,
                                int index,
                                Function<PodiumDefinition, String> payoutIdFn,
                                boolean simulate,
                                CommandSender feedbackTarget,
                                SchedulerAdapter scheduler) {
        if (index >= defs.size()) {
            activeRuns.remove(runKey);
            return;
        }
        PodiumDefinition def = defs.get(index);
        String payoutId = payoutIdFn.apply(def);
        captureSnapshotIncremental(def, payoutId, scheduler, snapshot -> {
            deliverSnapshot(def, snapshot, simulate, feedbackTarget, scheduler);
            scheduler.runGlobalLater(() ->
                    runNextInBatch(runKey, defs, index + 1, payoutIdFn, simulate, feedbackTarget, scheduler), 1L);
        });
    }

    /**
     * Executes or simulates a single podium payout immediately on the Global/Main Thread (non-blocking UUIDs).
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
        deliverSnapshot(def, snapshot, simulate, feedbackTarget, null);
    }

    private void deliverSnapshot(PodiumDefinition def,
                                 PodiumSnapshot snapshot,
                                 boolean simulate,
                                 CommandSender feedbackTarget,
                                 SchedulerAdapter scheduler) {
        if (simulate) {
            sendSimulationReport(snapshot, feedbackTarget);
            return;
        }

        int onlineCount = 0;
        long nowMs = System.currentTimeMillis();
        Duration exp = configManager.getMainConfig().getQueueExpiration();
        long expiresAtMs = (exp != null && !exp.isZero() && !exp.isNegative()) ? nowMs + exp.toMillis() : 0L;

        List<String> onlineCommands = new ArrayList<>();
        List<PendingReward> offlineBatch = new ArrayList<>();
        List<PodiumSnapshot.Entry> unresolvedOffline = new ArrayList<>();

        for (PodiumSnapshot.Entry entry : snapshot.getEntries()) {
            if (entry.isOnline()) {
                onlineCommands.addAll(entry.getCommands());
                onlineCount++;
            } else if (entry.getPlayerUuid() != null) {
                offlineBatch.add(buildPendingReward(def, snapshot.getPayoutId(), entry, entry.getPlayerUuid(), nowMs, expiresAtMs));
            } else {
                unresolvedOffline.add(entry);
            }
        }

        if (!onlineCommands.isEmpty()) {
            dispatcher.enqueueAll(onlineCommands);
        }

        int offlineCount = queueManager.enqueueOfflineBatch(offlineBatch);

        // Resolve any uncached offline player UUIDs off-thread so Main/Region Thread never blocks on Mojang HTTP
        if (!unresolvedOffline.isEmpty() && configManager.getMainConfig().isAsyncUuidLookup() && scheduler != null) {
            scheduler.runAsync(() -> {
                List<PendingReward> resolvedBatch = new ArrayList<>(unresolvedOffline.size());
                for (PodiumSnapshot.Entry entry : unresolvedOffline) {
                    UUID resolved = resolveUncachedOfflineUuid(entry.getPlayerName());
                    if (resolved != null) {
                        resolvedBatch.add(buildPendingReward(def, snapshot.getPayoutId(), entry, resolved, nowMs, expiresAtMs));
                    }
                }
                queueManager.enqueueOfflineBatch(resolvedBatch);
            });
        }

        if (feedbackTarget != null) {
            configManager.getMessages().send(feedbackTarget, "test.executed",
                    "{prefix}&aExecuted payout for &e{podium}&a: &e{online} online&a, &e{offline} queued offline&a.",
                    Map.of(
                            "podium", def.getId(),
                            "online", String.valueOf(onlineCount),
                            "offline", String.valueOf(offlineCount + unresolvedOffline.size())
                    ));
        }

        if (discordManager != null) {
            discordManager.notifyPayout(def, snapshot, onlineCount, offlineCount + unresolvedOffline.size());
        }
    }

    private PendingReward buildPendingReward(PodiumDefinition def,
                                             String payoutId,
                                             PodiumSnapshot.Entry entry,
                                             UUID uuid,
                                             long nowMs,
                                             long expiresAtMs) {
        return PendingReward.builder()
                .entryId(UUID.randomUUID().toString())
                .playerUuid(uuid)
                .lastKnownName(entry.getPlayerName())
                .podiumId(def.getId())
                .rank(entry.getRank())
                .value(entry.getValue())
                .payoutId(payoutId)
                .commands(entry.getCommands())
                .createdAtEpochMs(nowMs)
                .expiresAtEpochMs(expiresAtMs)
                .build();
    }

    private void processRank(SnapshotAccumulator acc, int rank) {
        String rawPlayer = placeholderResolver.resolveForRank(acc.def.getPlayerPlaceholder(), rank);
        if (placeholderResolver.isInvalidValue(rawPlayer, acc.invalidValues)) {
            return;
        }

        String rawValue = acc.def.getValuePlaceholder() == null || acc.def.getValuePlaceholder().isBlank()
                ? "0"
                : placeholderResolver.resolveForRank(acc.def.getValuePlaceholder(), rank);
        if (rawValue.isBlank()) {
            rawValue = "0";
        }

        String failureReason = evaluateConditions(acc.def, rawValue);
        if (failureReason != null) {
            acc.skipped.add(PodiumSnapshot.SkippedEntry.builder()
                    .rank(rank)
                    .playerName(rawPlayer)
                    .value(rawValue)
                    .reason(failureReason)
                    .build());
            return;
        }

        Player onlinePlayer = Bukkit.getPlayerExact(rawPlayer);
        boolean isOnline = onlinePlayer != null && onlinePlayer.isOnline();
        UUID uuid = isOnline ? onlinePlayer.getUniqueId() : resolveCachedUuid(rawPlayer);

        if (acc.def.getTiePolicy() == TiePolicy.SKIP_DUPLICATE_PLAYER) {
            if (uuid != null && !acc.seenUuids.add(uuid)) {
                return;
            }
            if (!acc.seenNames.add(rawPlayer.toLowerCase())) {
                return;
            }
        }

        List<String> templateCmds = acc.def.getCommandsForRank(rank);
        if (templateCmds.isEmpty()) {
            return;
        }

        String uuidStr = uuid != null ? uuid.toString() : "UNKNOWN";
        List<String> resolvedCmds = new ArrayList<>(templateCmds.size());
        for (String cmd : templateCmds) {
            resolvedCmds.add(cmd
                    .replace("{player}", rawPlayer)
                    .replace("{uuid}", uuidStr)
                    .replace("{rank}", String.valueOf(rank))
                    .replace("{podium}", acc.def.getId())
                    .replace("{podium_name}", acc.strippedName)
                    .replace("{value}", rawValue)
                    .replace("{date}", acc.dateStr)
                    .replace("{time}", acc.timeStr));
        }

        acc.entries.add(PodiumSnapshot.Entry.builder()
                .rank(rank)
                .playerName(rawPlayer)
                .playerUuid(uuid)
                .value(rawValue)
                .online(isOnline)
                .commands(resolvedCmds)
                .build());
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

    /**
     * Non-blocking O(1) UUID resolution using local QueueManager cache and Paper's usercache.
     */
    private UUID resolveCachedUuid(String playerName) {
        UUID fromQueue = queueManager.getCachedUuid(playerName).orElse(null);
        if (fromQueue != null) {
            return fromQueue;
        }
        try {
            OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(playerName);
            if (cached != null) {
                return cached.getUniqueId();
            }
        } catch (NoSuchMethodError ignored) {
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private UUID resolveUncachedOfflineUuid(String playerName) {
        try {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(playerName);
            return offline.getUniqueId();
        } catch (Exception ignored) {
            return null;
        }
    }

    private final class SnapshotAccumulator {
        final PodiumDefinition def;
        final String payoutId;
        final Set<String> invalidValues;
        final Set<UUID> seenUuids = new HashSet<>();
        final Set<String> seenNames = new HashSet<>();
        final String dateStr;
        final String timeStr;
        final String strippedName;
        final List<PodiumSnapshot.Entry> entries = new ArrayList<>();
        final List<PodiumSnapshot.SkippedEntry> skipped = new ArrayList<>();

        SnapshotAccumulator(PodiumDefinition def, String payoutId) {
            this.def = def;
            this.payoutId = payoutId;
            MainConfig mainCfg = configManager.getMainConfig();
            this.invalidValues = mainCfg.getInvalidPlaceholderValues();
            ScheduleSpec spec = configManager.getSchedules().get(def.getScheduleId());
            ZoneId zone = spec != null ? spec.getTimezone() : mainCfg.getDefaultTimezone();
            ZonedDateTime nowZdt = Instant.now().atZone(zone);
            this.dateStr = DATE_FMT.format(nowZdt);
            this.timeStr = TIME_FMT.format(nowZdt);
            this.strippedName = ColorUtil.strip(def.getDisplayName());
        }

        PodiumSnapshot build() {
            return PodiumSnapshot.builder()
                    .podiumId(def.getId())
                    .displayName(def.getDisplayName())
                    .payoutId(payoutId)
                    .capturedAt(Instant.now())
                    .entries(List.copyOf(entries))
                    .skippedEntries(List.copyOf(skipped))
                    .build();
        }
    }
}
