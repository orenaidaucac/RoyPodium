package com.github.orenaidaucac.royPodium.queue;

import com.github.orenaidaucac.royPodium.config.ConfigManager;
import com.github.orenaidaucac.royPodium.config.MainConfig;
import com.github.orenaidaucac.royPodium.scheduler.SchedulerAdapter;
import com.github.orenaidaucac.royPodium.util.AtomicFileUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages persistent offline reward queues in {@code data/pending-rewards.yml}.
 * Supports safe manual Admin edits, friendly defaults, reload without overwriting edits,
 * and preservation of malformed entries with clear English warnings.
 */
public final class QueueManager implements Listener {

    private static final String FILE_HEADER = """
            # ==============================================================================
            # RoyPodium - Pending Offline Rewards (data/pending-rewards.yml)
            # ==============================================================================
            # You may add, edit, or delete entries here manually, then run /roypodium reload.
            #
            # Structure:
            #   players:
            #     <player-uuid>:
            #       entries:
            #         <any-unique-key>:
            #           commands:                 # REQUIRED: non-empty list of console commands
            #             - "eco give Steve 1000"
            #           last-known-name: "Steve"  # Optional (defaults to "Unknown")
            #           podium-id: "manual"       # Optional (defaults to "manual")
            #           rank: 1                   # Optional (defaults to 1)
            #           value: "0"                # Optional (defaults to "0")
            #           payout-id: "manual:gift1" # Optional (auto-generated from entry key)
            #           expires-at: 0             # Optional (epoch ms, 0 = never expires)
            # ==============================================================================
            """;

    private final Plugin plugin;
    private final ConfigManager configManager;
    private final SchedulerAdapter scheduler;
    private final CommandDispatchQueue dispatcher;
    private final Path dataPath;

    private final Map<UUID, List<PendingReward>> pendingByUuid = new ConcurrentHashMap<>();
    /** Fast O(1) name -> UUID cache populated on load and join to avoid blocking Mojang lookups. */
    private final Map<String, UUID> knownNameToUuid = new ConcurrentHashMap<>();
    /** Raw YAML paths -> values for malformed manual entries so they are never silently deleted. */
    private final Map<String, Object> preservedInvalidEntries = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Object saveLock = new Object();
    private final AtomicBoolean saveScheduled = new AtomicBoolean(false);

    public QueueManager(Plugin plugin, ConfigManager configManager, SchedulerAdapter scheduler, CommandDispatchQueue dispatcher) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.scheduler = scheduler;
        this.dispatcher = dispatcher;
        this.dataPath = plugin.getDataFolder().toPath().resolve("data").resolve("pending-rewards.yml");
    }

    public void load() {
        pendingByUuid.clear();
        preservedInvalidEntries.clear();

        File file = dataPath.toFile();
        if (!file.exists()) {
            writeSerialized(FILE_HEADER + "players: {}\n");
            return;
        }

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception ex) {
            plugin.getLogger().severe("[data/pending-rewards.yml] Failed to parse YAML syntax: " + ex.getMessage()
                    + ". Fix: Check indentation and quotes in data/pending-rewards.yml. File was NOT overwritten.");
            return;
        }

        ConfigurationSection playersSec = yaml.getConfigurationSection("players");
        if (playersSec == null) {
            return;
        }

        long now = System.currentTimeMillis();
        boolean pruned = false;
        for (String uuidStr : playersSec.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidStr);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("[data/pending-rewards.yml] Key 'players." + uuidStr
                        + "' is not a valid player UUID. Fix: Replace with a valid UUID (e.g. 069a79f4-44e9-4726-a5be-fca90e38aaf5). Entry preserved untouched.");
                preservedInvalidEntries.put("players." + uuidStr, playersSec.get(uuidStr));
                continue;
            }

            ConfigurationSection entriesSec = playersSec.getConfigurationSection(uuidStr + ".entries");
            if (entriesSec == null) {
                plugin.getLogger().warning("[data/pending-rewards.yml] Key 'players." + uuidStr
                        + "' is missing an 'entries' section. Fix: Nest reward keys under 'players." + uuidStr + ".entries.<key>'. Entry preserved untouched.");
                preservedInvalidEntries.put("players." + uuidStr, playersSec.get(uuidStr));
                continue;
            }

            List<PendingReward> list = Collections.synchronizedList(new ArrayList<>());
            for (String entryId : entriesSec.getKeys(false)) {
                ConfigurationSection e = entriesSec.getConfigurationSection(entryId);
                if (e == null) {
                    plugin.getLogger().warning("[data/pending-rewards.yml] Entry 'players." + uuidStr + ".entries." + entryId
                            + "' must be a YAML section. Entry preserved untouched.");
                    preservedInvalidEntries.put("players." + uuidStr + ".entries." + entryId, entriesSec.get(entryId));
                    continue;
                }

                List<String> cmds = e.getStringList("commands");
                if (cmds.isEmpty()) {
                    plugin.getLogger().warning("[data/pending-rewards.yml] Entry 'players." + uuidStr + ".entries." + entryId
                            + ".commands' is empty or missing. Fix: Add at least one console command under 'commands:'. Entry preserved untouched.");
                    preservedInvalidEntries.put("players." + uuidStr + ".entries." + entryId, e);
                    continue;
                }

                String payoutId = e.getString("payout-id");
                if (payoutId == null || payoutId.isBlank()) {
                    payoutId = "manual:" + entryId;
                }

                PendingReward reward = PendingReward.builder()
                        .entryId(entryId)
                        .playerUuid(uuid)
                        .lastKnownName(e.getString("last-known-name", "Unknown"))
                        .podiumId(e.getString("podium-id", "manual"))
                        .rank(Math.max(1, e.getInt("rank", 1)))
                        .value(e.getString("value", "0"))
                        .payoutId(payoutId)
                        .commands(new ArrayList<>(cmds))
                        .createdAtEpochMs(e.getLong("created-at", now))
                        .expiresAtEpochMs(e.getLong("expires-at", 0L))
                        .build();

                if (reward.isExpired(now)) {
                    pruned = true;
                } else {
                    list.add(reward);
                    if (reward.getLastKnownName() != null && !reward.getLastKnownName().isBlank()) {
                        knownNameToUuid.put(reward.getLastKnownName().toLowerCase(Locale.ROOT), uuid);
                    }
                }
            }
            if (!list.isEmpty()) {
                pendingByUuid.put(uuid, list);
            }
        }
        if (pruned) {
            saveAsync();
        }
    }

    /**
     * Enqueues a reward for an offline player, preventing duplicate payoutId+rank entries.
     */
    public boolean enqueueOffline(PendingReward reward) {
        boolean added = insertOfflineInternal(reward);
        if (added) {
            saveAsync();
        }
        return added;
    }

    /**
     * Enqueues a batch of offline rewards and triggers a single coalesced async YAML save.
     */
    public int enqueueOfflineBatch(Collection<PendingReward> rewards) {
        if (rewards == null || rewards.isEmpty()) {
            return 0;
        }
        int added = 0;
        for (PendingReward reward : rewards) {
            if (insertOfflineInternal(reward)) {
                added++;
            }
        }
        if (added > 0) {
            saveAsync();
        }
        return added;
    }

    private boolean insertOfflineInternal(PendingReward reward) {
        if (reward.getLastKnownName() != null && !reward.getLastKnownName().isBlank()) {
            knownNameToUuid.put(reward.getLastKnownName().toLowerCase(Locale.ROOT), reward.getPlayerUuid());
        }
        List<PendingReward> list = pendingByUuid.computeIfAbsent(
                reward.getPlayerUuid(), k -> Collections.synchronizedList(new ArrayList<>())
        );
        synchronized (list) {
            boolean duplicate = list.stream().anyMatch(existing ->
                    existing.getPayoutId() != null
                            && !existing.getPayoutId().isEmpty()
                            && existing.getPayoutId().equals(reward.getPayoutId())
                            && existing.getRank() == reward.getRank());
            if (duplicate) {
                return false;
            }
            list.add(reward);
            return true;
        }
    }

    public Optional<UUID> getCachedUuid(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(knownNameToUuid.get(playerName.toLowerCase(Locale.ROOT)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        knownNameToUuid.put(player.getName().toLowerCase(Locale.ROOT), uuid);
        List<PendingReward> list = pendingByUuid.get(uuid);
        if (list == null || list.isEmpty()) {
            return;
        }
        MainConfig cfg = configManager.getMainConfig();
        scheduler.runAtEntityLater(player, () -> deliverForOnlinePlayer(player), cfg.getJoinDelayTicks());
    }

    private void deliverForOnlinePlayer(Player player) {
        if (!player.isOnline()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        List<PendingReward> list = pendingByUuid.remove(uuid);
        if (list == null || list.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        int delivered = 0;
        List<String> commandsToRun = new ArrayList<>();

        synchronized (list) {
            for (PendingReward reward : list) {
                if (reward.isExpired(now)) {
                    continue;
                }
                delivered++;
                for (String cmd : reward.getCommands()) {
                    String updated = cmd;
                    if (reward.getLastKnownName() != null
                            && !reward.getLastKnownName().isEmpty()
                            && !"Unknown".equalsIgnoreCase(reward.getLastKnownName())) {
                        updated = updated.replace(reward.getLastKnownName(), player.getName());
                    }
                    commandsToRun.add(updated);
                }
            }
        }
        saveAsync();

        if (!commandsToRun.isEmpty()) {
            dispatcher.enqueueAll(commandsToRun);
        }
        if (delivered > 0 && configManager.getMainConfig().isNotifyOnJoin()) {
            configManager.getMessages().send(player, "queue.delivered-on-join",
                    "{prefix}&aYou received &e{count}&a pending leaderboard reward(s)!",
                    Map.of("count", String.valueOf(delivered)));
        }
    }

    public Map<UUID, List<PendingReward>> snapshotAll() {
        long now = System.currentTimeMillis();
        Map<UUID, List<PendingReward>> result = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<PendingReward>> entry : pendingByUuid.entrySet()) {
            List<PendingReward> valid = new ArrayList<>();
            synchronized (entry.getValue()) {
                for (PendingReward r : entry.getValue()) {
                    if (!r.isExpired(now)) {
                        valid.add(r);
                    }
                }
            }
            if (!valid.isEmpty()) {
                result.put(entry.getKey(), valid);
            }
        }
        return result;
    }

    public List<PendingReward> getPending(UUID uuid) {
        List<PendingReward> list = pendingByUuid.get(uuid);
        if (list == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        synchronized (list) {
            return list.stream().filter(r -> !r.isExpired(now)).toList();
        }
    }

    public Optional<UUID> findUuidByNameOrUuid(String target) {
        if (target == null || target.isBlank()) {
            return Optional.empty();
        }
        try {
            UUID parsed = UUID.fromString(target.trim());
            if (pendingByUuid.containsKey(parsed)) {
                return Optional.of(parsed);
            }
        } catch (IllegalArgumentException ignored) {
        }
        for (Map.Entry<UUID, List<PendingReward>> entry : pendingByUuid.entrySet()) {
            synchronized (entry.getValue()) {
                for (PendingReward r : entry.getValue()) {
                    if (r.getLastKnownName() != null && r.getLastKnownName().equalsIgnoreCase(target.trim())) {
                        return Optional.of(entry.getKey());
                    }
                }
            }
        }
        return Optional.empty();
    }

    public int clearPlayer(UUID uuid) {
        List<PendingReward> removed = pendingByUuid.remove(uuid);
        if (removed == null || removed.isEmpty()) {
            return 0;
        }
        saveAsync();
        return removed.size();
    }

    public int clearAll() {
        int total = 0;
        for (List<PendingReward> list : pendingByUuid.values()) {
            total += list.size();
        }
        pendingByUuid.clear();
        saveAsync();
        return total;
    }

    public int getTotalEntryCount() {
        int total = 0;
        for (List<PendingReward> list : pendingByUuid.values()) {
            total += list.size();
        }
        return total;
    }

    public int getPreservedInvalidCount() {
        return preservedInvalidEntries.size();
    }

    /**
     * Coalesces concurrent save requests and runs YAML serialization + atomic file write 100% off the Main Thread.
     */
    public void saveAsync() {
        if (!plugin.isEnabled()) {
            writeSerialized(serializeToYaml());
            return;
        }
        if (saveScheduled.compareAndSet(false, true)) {
            scheduler.runAsync(() -> {
                saveScheduled.set(false);
                writeSerialized(serializeToYaml());
            });
        }
    }

    public void saveSync() {
        saveScheduled.set(false);
        writeSerialized(serializeToYaml());
    }

    String serializeToYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, List<PendingReward>> entry : pendingByUuid.entrySet()) {
            String uuidKey = entry.getKey().toString();
            List<PendingReward> copy;
            synchronized (entry.getValue()) {
                copy = new ArrayList<>(entry.getValue());
            }
            for (PendingReward r : copy) {
                String base = "players." + uuidKey + ".entries." + r.getEntryId();
                yaml.set(base + ".last-known-name", r.getLastKnownName());
                yaml.set(base + ".podium-id", r.getPodiumId());
                yaml.set(base + ".rank", r.getRank());
                yaml.set(base + ".value", r.getValue());
                yaml.set(base + ".payout-id", r.getPayoutId() != null ? r.getPayoutId() : "");
                yaml.set(base + ".commands", r.getCommands());
                yaml.set(base + ".created-at", r.getCreatedAtEpochMs());
                yaml.set(base + ".expires-at", r.getExpiresAtEpochMs());
            }
        }
        synchronized (preservedInvalidEntries) {
            for (Map.Entry<String, Object> invalid : preservedInvalidEntries.entrySet()) {
                yaml.set(invalid.getKey(), invalid.getValue());
            }
        }
        String body = yaml.saveToString();
        if (body.isBlank()) {
            body = "players: {}\n";
        }
        return FILE_HEADER + body;
    }

    private void writeSerialized(String content) {
        synchronized (saveLock) {
            try {
                AtomicFileUtil.writeAtomic(dataPath, content);
            } catch (IOException ex) {
                plugin.getLogger().severe("Failed to save data/pending-rewards.yml: " + ex.getMessage());
            }
        }
    }
}
