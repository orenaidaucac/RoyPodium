package com.github.orenaidaucac.royPodium.scheduler;

import com.github.orenaidaucac.royPodium.config.ConfigManager;
import com.github.orenaidaucac.royPodium.podium.PodiumDefinition;
import com.github.orenaidaucac.royPodium.podium.PodiumManager;
import com.github.orenaidaucac.royPodium.util.AtomicFileUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives scheduled leaderboard payouts, missed-schedule recovery, and duplicate-payout protection via {@code data/state.yml}.
 */
public final class ScheduleManager {

    private final Plugin plugin;
    private final ConfigManager configManager;
    private final SchedulerAdapter scheduler;
    private final PodiumManager podiumManager;
    private final Path statePath;

    private final Map<String, Long> lastExecutedEpochMs = new ConcurrentHashMap<>();
    private final Map<String, String> lastPayoutIds = new ConcurrentHashMap<>();
    private final Object saveLock = new Object();

    private SchedulerAdapter.ScheduledHandle tickerHandle;

    public ScheduleManager(Plugin plugin, ConfigManager configManager, SchedulerAdapter scheduler, PodiumManager podiumManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.scheduler = scheduler;
        this.podiumManager = podiumManager;
        this.statePath = plugin.getDataFolder().toPath().resolve("data").resolve("state.yml");
    }

    public void start() {
        loadState();
        evaluateMissedSchedules();
        // Check schedules every 20 ticks (1 second) on Global Region / Main Thread
        this.tickerHandle = scheduler.runGlobalTimer(this::tick, 20L, 20L);
    }

    public void stop() {
        if (tickerHandle != null) {
            tickerHandle.cancel();
            tickerHandle = null;
        }
        saveStateSync();
    }

    private void loadState() {
        lastExecutedEpochMs.clear();
        lastPayoutIds.clear();
        File file = statePath.toFile();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
            ConfigurationSection sec = yaml.getConfigurationSection("podiums");
            if (sec != null) {
                for (String id : sec.getKeys(false)) {
                    lastExecutedEpochMs.put(id, sec.getLong(id + ".last-executed-ms", 0L));
                    lastPayoutIds.put(id, sec.getString(id + ".last-payout-id", ""));
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to load data/state.yml: " + ex.getMessage());
        }
    }

    private void evaluateMissedSchedules() {
        Instant now = Instant.now();
        int maxCatchup = configManager.getMainConfig().getMaxCatchupRuns();
        for (PodiumDefinition podium : configManager.getPodiums().values()) {
            if (!podium.isEnabled()) {
                continue;
            }
            ScheduleSpec spec = configManager.getSchedules().get(podium.getScheduleId());
            if (spec == null) {
                continue;
            }
            long lastMs = lastExecutedEpochMs.getOrDefault(podium.getId(), 0L);
            if (lastMs <= 0L) {
                // First time seeing this podium: initialize anchor to now so we don't fire retroactively
                lastExecutedEpochMs.put(podium.getId(), now.toEpochMilli());
                continue;
            }
            MissedPolicy policy = spec.getMissedPolicy() != null
                    ? spec.getMissedPolicy()
                    : configManager.getMainConfig().getMissedSchedulePolicy();
            if (policy == MissedPolicy.SKIP) {
                lastExecutedEpochMs.put(podium.getId(), now.toEpochMilli());
                continue;
            }

            List<Instant> missed = spec.missedExecutionsBetween(Instant.ofEpochMilli(lastMs), now, maxCatchup + 10);
            if (missed.isEmpty()) {
                continue;
            }
            if (policy == MissedPolicy.RUN_LATEST) {
                Instant latest = missed.get(missed.size() - 1);
                triggerPayout(podium, latest);
            } else if (policy == MissedPolicy.RUN_ALL) {
                int start = Math.max(0, missed.size() - maxCatchup);
                for (int i = start; i < missed.size(); i++) {
                    triggerPayout(podium, missed.get(i));
                }
            }
        }
        saveStateAsync();
    }

    private void tick() {
        Instant now = Instant.now();
        boolean changed = false;
        for (PodiumDefinition podium : configManager.getPodiums().values()) {
            if (!podium.isEnabled()) {
                continue;
            }
            ScheduleSpec spec = configManager.getSchedules().get(podium.getScheduleId());
            if (spec == null) {
                continue;
            }
            long lastMs = lastExecutedEpochMs.computeIfAbsent(podium.getId(), k -> now.toEpochMilli());
            Instant next = spec.nextExecutionAfter(Instant.ofEpochMilli(lastMs));
            if (next != null && !next.isAfter(now)) {
                triggerPayout(podium, next);
                changed = true;
            }
        }
        if (changed) {
            saveStateAsync();
        }
    }

    private void triggerPayout(PodiumDefinition podium, Instant scheduledTime) {
        String payoutId = podium.getId() + "@" + scheduledTime.toEpochMilli();
        String prevPayoutId = lastPayoutIds.get(podium.getId());
        if (payoutId.equals(prevPayoutId)) {
            return;
        }
        // Mark state before execution to prevent duplicate payouts if interrupted
        lastExecutedEpochMs.put(podium.getId(), scheduledTime.toEpochMilli());
        lastPayoutIds.put(podium.getId(), payoutId);
        podiumManager.runPayout(podium, payoutId, false, null);
    }

    private void saveStateAsync() {
        String content = serializeState();
        if (plugin.isEnabled()) {
            scheduler.runAsync(() -> writeState(content));
        } else {
            writeState(content);
        }
    }

    private void saveStateSync() {
        writeState(serializeState());
    }

    private String serializeState() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, Long> entry : lastExecutedEpochMs.entrySet()) {
            String id = entry.getKey();
            yaml.set("podiums." + id + ".last-executed-ms", entry.getValue());
            yaml.set("podiums." + id + ".last-payout-id", lastPayoutIds.getOrDefault(id, ""));
        }
        return yaml.saveToString();
    }

    private void writeState(String content) {
        synchronized (saveLock) {
            try {
                AtomicFileUtil.writeAtomic(statePath, content);
            } catch (IOException ex) {
                plugin.getLogger().warning("Failed to save data/state.yml: " + ex.getMessage());
            }
        }
    }
}
