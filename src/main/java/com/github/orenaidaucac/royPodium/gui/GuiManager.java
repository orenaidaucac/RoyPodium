package com.github.orenaidaucac.royPodium.gui;

import com.github.orenaidaucac.royPodium.config.ConfigManager;
import com.github.orenaidaucac.royPodium.config.GuiConfig;
import com.github.orenaidaucac.royPodium.podium.ConditionSpec;
import com.github.orenaidaucac.royPodium.podium.PodiumDefinition;
import com.github.orenaidaucac.royPodium.podium.PodiumManager;
import com.github.orenaidaucac.royPodium.podium.TiePolicy;
import com.github.orenaidaucac.royPodium.queue.PendingReward;
import com.github.orenaidaucac.royPodium.queue.QueueManager;
import com.github.orenaidaucac.royPodium.scheduler.MissedPolicy;
import com.github.orenaidaucac.royPodium.scheduler.ScheduleSpec;
import com.github.orenaidaucac.royPodium.scheduler.SchedulerAdapter;
import com.github.orenaidaucac.royPodium.util.ColorUtil;
import com.github.orenaidaucac.royPodium.util.TimeUtil;
import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TriumphGUI-powered in-game editor with 100% configurable layout/text and reload-safe session lifecycle.
 */
public final class GuiManager {

    private final ConfigManager configManager;
    private final SchedulerAdapter scheduler;
    private final PodiumManager podiumManager;
    private final QueueManager queueManager;
    private final ChatInputManager chatInput;

    private final Map<UUID, EditorSession> sessions = new ConcurrentHashMap<>();
    private final Set<Inventory> trackedInventories = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public GuiManager(ConfigManager configManager,
                      SchedulerAdapter scheduler,
                      PodiumManager podiumManager,
                      QueueManager queueManager,
                      ChatInputManager chatInput) {
        this.configManager = configManager;
        this.scheduler = scheduler;
        this.podiumManager = podiumManager;
        this.queueManager = queueManager;
        this.chatInput = chatInput;
    }

    public boolean isTrackedInventory(Inventory inv) {
        return inv != null && trackedInventories.contains(inv);
    }

    public boolean isValidSession(Player player) {
        return player != null
                && player.isOnline()
                && player.hasPermission("roypodium.editor")
                && sessions.containsKey(player.getUniqueId());
    }

    public void invalidateSession(UUID uuid) {
        sessions.remove(uuid);
    }

    public void handleClose(Player player, Inventory inv) {
        trackedInventories.remove(inv);
        EditorSession session = sessions.get(player.getUniqueId());
        if (session != null && !session.isTransitioning() && !chatInput.hasPending(player.getUniqueId())) {
            sessions.remove(player.getUniqueId());
        }
    }

    /**
     * Safely closes all open RoyPodium GUIs and releases all session state (used on reload/onDisable/PlugmanX).
     */
    public void closeAll(boolean notifyReload) {
        chatInput.clearAll();
        for (UUID uuid : List.copyOf(sessions.keySet())) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null && p.isOnline()) {
                scheduler.runAtEntity(p, () -> {
                    if (isTrackedInventory(p.getOpenInventory().getTopInventory())) {
                        p.closeInventory();
                        if (notifyReload) {
                            configManager.getMessages().send(p, "editor.closed-on-reload",
                                    "{prefix}&eYour RoyPodium editor was closed because the plugin was reloaded.");
                        }
                    }
                });
            }
        }
        sessions.clear();
        trackedInventories.clear();
    }

    public void openMainMenu(Player player) {
        if (!checkAccess(player)) return;
        GuiConfig cfg = configManager.getGuiConfig();
        int rows = cfg.getRows("main", 3);
        Gui gui = createBaseGui(cfg.getTitle("main", "&6&lRoyPodium &8| &7Editor", Map.of()), rows);

        setConfiguredItem(gui, "menus.main.items.podiums", 11, Material.GOLDEN_HELMET, "&6&lManage Podiums", Map.of(),
                e -> { playSound(player, "click"); openPodiumList(player); });
        setConfiguredItem(gui, "menus.main.items.schedules", 13, Material.CLOCK, "&b&lManage Schedules", Map.of(),
                e -> { playSound(player, "click"); openScheduleList(player); });
        setConfiguredItem(gui, "menus.main.items.queue", 15, Material.CHEST, "&a&lOffline Reward Queue", Map.of(),
                e -> { playSound(player, "click"); openQueueMenu(player); });
        setConfiguredItem(gui, "menus.main.items.settings", 22, Material.COMPARATOR, "&d&lGlobal Settings", Map.of(),
                e -> { playSound(player, "click"); openSettingsMenu(player); });

        openGui(player, gui);
    }

    public void openPodiumList(Player player) {
        if (!checkAccess(player)) return;
        GuiConfig cfg = configManager.getGuiConfig();
        int rows = cfg.getRows("podium-list", 6);
        Gui gui = createBaseGui(cfg.getTitle("podium-list", "&6&lRoyPodium &8| &7Podiums", Map.of()), rows);

        Material entryMat = cfg.getMaterial("menus.podium-list.entry-material", Material.NETHER_STAR);
        String nameTpl = cfg.getRawString("menus.podium-list.entry-name", "&6{display_name} &8(&7{id}&8)");
        List<String> loreTpl = cfg.getStringList("menus.podium-list.entry-lore");

        int slot = 0;
        int maxSlot = (rows - 1) * 9;
        for (PodiumDefinition def : configManager.getPodiums().values()) {
            if (slot >= maxSlot) break;
            Map<String, String> ph = podiumPlaceholders(def);
            String itemName = ColorUtil.colorize(GuiConfig.apply(nameTpl, ph));
            List<String> itemLore = loreTpl.stream().map(l -> ColorUtil.colorize(GuiConfig.apply(l, ph))).toList();

            gui.setItem(slot++, buildGuiItem(entryMat, itemName, itemLore, event -> {
                        if (event.getClick() == ClickType.SHIFT_RIGHT) {
                            configManager.deletePodium(def.getId());
                            playSound(player, "delete");
                            openPodiumList(player);
                        } else if (event.getClick() == ClickType.RIGHT) {
                            playSound(player, "click");
                            scheduler.runAtEntity(player, player::closeInventory);
                            scheduler.runGlobal(() -> podiumManager.runPayout(def, "sim@" + System.currentTimeMillis(), true, player));
                        } else {
                            playSound(player, "click");
                            openPodiumEdit(player, def.getId());
                        }
                    }));
        }

        int createSlot = cfg.getInt("menus.podium-list.create-slot", 49);
        setConfiguredItem(gui, "menus.podium-list.create-item", createSlot, Material.EMERALD, "&a&l+ Create New Podium", Map.of(),
                e -> {
                    playSound(player, "click");
                    promptInput(player, input -> {
                        String id = input.toLowerCase().replaceAll("[^a-z0-9_\\-]", "");
                        if (id.isEmpty()) {
                            playSound(player, "error");
                            openPodiumList(player);
                            return;
                        }
                        PodiumDefinition created = PodiumDefinition.builder()
                                .id(id)
                                .enabled(false)
                                .displayName("&6" + id)
                                .scheduleId(configManager.getSchedules().keySet().stream().findFirst().orElse(""))
                                .playerPlaceholder("%ajlb_lb_kills_{rank}_alltime_name%")
                                .valuePlaceholder("%ajlb_lb_kills_{rank}_alltime_value%")
                                .topSize(5)
                                .tiePolicy(TiePolicy.ALLOW_TIES)
                                .rankRules(new LinkedHashMap<>(Map.of("1", List.of("eco give {player} 1000"))))
                                .build();
                        configManager.savePodium(created);
                        playSound(player, "save");
                        openPodiumEdit(player, id);
                    }, () -> openPodiumList(player));
                });

        int backSlot = cfg.getInt("menus.podium-list.back-slot", 45);
        setConfiguredItem(gui, "menus.podium-list.back-item", backSlot, Material.ARROW, "&cBack", Map.of(),
                e -> { playSound(player, "click"); openMainMenu(player); });

        openGui(player, gui);
    }

    public void openPodiumEdit(Player player, String podiumId) {
        if (!checkAccess(player)) return;
        PodiumDefinition def = configManager.getPodiums().get(podiumId);
        if (def == null) {
            openPodiumList(player);
            return;
        }
        GuiConfig cfg = configManager.getGuiConfig();
        int rows = cfg.getRows("podium-edit", 4);
        Map<String, String> ph = podiumPlaceholders(def);
        Gui gui = createBaseGui(cfg.getTitle("podium-edit", "&6&lEdit Podium: &f{id}", ph), rows);

        setConfiguredItem(gui, "menus.podium-edit.items.toggle-enabled", 10, Material.LEVER, "&eToggle Status", ph,
                e -> {
                    configManager.savePodium(copyPodium(def, !def.isEnabled(), def.getDisplayName(), def.getScheduleId(),
                            def.getPlayerPlaceholder(), def.getValuePlaceholder(), def.getTopSize(), def.getRankRules()));
                    playSound(player, "save");
                    openPodiumEdit(player, podiumId);
                });

        setConfiguredItem(gui, "menus.podium-edit.items.display-name", 11, Material.NAME_TAG, "&eDisplay Name", ph,
                e -> promptInput(player, val -> {
                    configManager.savePodium(copyPodium(def, def.isEnabled(), val, def.getScheduleId(),
                            def.getPlayerPlaceholder(), def.getValuePlaceholder(), def.getTopSize(), def.getRankRules()));
                    playSound(player, "save");
                    openPodiumEdit(player, podiumId);
                }, () -> openPodiumEdit(player, podiumId)));

        setConfiguredItem(gui, "menus.podium-edit.items.schedule", 12, Material.CLOCK, "&eSchedule Link", ph,
                e -> {
                    List<String> ids = new ArrayList<>(configManager.getSchedules().keySet());
                    String next = ids.isEmpty() ? "" : ids.get((ids.indexOf(def.getScheduleId()) + 1) % ids.size());
                    configManager.savePodium(copyPodium(def, def.isEnabled(), def.getDisplayName(), next,
                            def.getPlayerPlaceholder(), def.getValuePlaceholder(), def.getTopSize(), def.getRankRules()));
                    playSound(player, "save");
                    openPodiumEdit(player, podiumId);
                });

        setConfiguredItem(gui, "menus.podium-edit.items.top-size", 13, Material.GOLD_INGOT, "&eTop Size", ph,
                e -> {
                    int delta = e.getClick() == ClickType.RIGHT ? -1 : 1;
                    int nextSize = Math.max(1, Math.min(100, def.getTopSize() + delta));
                    configManager.savePodium(copyPodium(def, def.isEnabled(), def.getDisplayName(), def.getScheduleId(),
                            def.getPlayerPlaceholder(), def.getValuePlaceholder(), nextSize, def.getRankRules()));
                    playSound(player, "save");
                    openPodiumEdit(player, podiumId);
                });

        setConfiguredItem(gui, "menus.podium-edit.items.player-placeholder", 14, Material.PLAYER_HEAD, "&ePlayer Placeholder", ph,
                e -> promptInput(player, val -> {
                    configManager.savePodium(copyPodium(def, def.isEnabled(), def.getDisplayName(), def.getScheduleId(),
                            val, def.getValuePlaceholder(), def.getTopSize(), def.getRankRules()));
                    playSound(player, "save");
                    openPodiumEdit(player, podiumId);
                }, () -> openPodiumEdit(player, podiumId)));

        setConfiguredItem(gui, "menus.podium-edit.items.value-placeholder", 15, Material.PAPER, "&eValue Placeholder", ph,
                e -> promptInput(player, val -> {
                    configManager.savePodium(copyPodium(def, def.isEnabled(), def.getDisplayName(), def.getScheduleId(),
                            def.getPlayerPlaceholder(), val, def.getTopSize(), def.getRankRules()));
                    playSound(player, "save");
                    openPodiumEdit(player, podiumId);
                }, () -> openPodiumEdit(player, podiumId)));

        setConfiguredItem(gui, "menus.podium-edit.items.rewards", 16, Material.DIAMOND, "&bEdit Rank Rewards", ph,
                e -> { playSound(player, "click"); openRewardEdit(player, podiumId); });

        setConfiguredItem(gui, "menus.podium-edit.items.conditions", 20, Material.COMPARATOR, "&eReward Conditions", ph,
                e -> { playSound(player, "click"); openConditionEdit(player, podiumId); });

        setConfiguredItem(gui, "menus.podium-edit.items.test-live", 29, Material.BLAZE_POWDER, "&6Run Live Payout Now", ph,
                e -> {
                    playSound(player, "save");
                    scheduler.runAtEntity(player, player::closeInventory);
                    scheduler.runGlobal(() -> podiumManager.runPayout(def, "manual@" + System.currentTimeMillis(), false, player));
                });

        setConfiguredItem(gui, "menus.podium-edit.items.back", 31, Material.ARROW, "&cBack", ph,
                e -> { playSound(player, "click"); openPodiumList(player); });

        openGui(player, gui);
    }

    public void openRewardEdit(Player player, String podiumId) {
        if (!checkAccess(player)) return;
        PodiumDefinition def = configManager.getPodiums().get(podiumId);
        if (def == null) {
            openPodiumList(player);
            return;
        }
        GuiConfig cfg = configManager.getGuiConfig();
        int rows = cfg.getRows("reward-edit", 6);
        Gui gui = createBaseGui(cfg.getTitle("reward-edit", "&b&lRewards: &f{id}", Map.of("id", podiumId)), rows);

        Material mat = cfg.getMaterial("menus.reward-edit.entry-material", Material.PAPER);
        String nameTpl = cfg.getRawString("menus.reward-edit.entry-name", "&eRank(s): &f{rank_key}");
        List<String> loreTpl = cfg.getStringList("menus.reward-edit.entry-lore");

        int slot = 0;
        int maxSlot = (rows - 1) * 9;
        for (Map.Entry<String, List<String>> entry : def.getRankRules().entrySet()) {
            if (slot >= maxSlot) break;
            String rankKey = entry.getKey();
            List<String> cmds = entry.getValue();
            String cmdBlock = cmds.isEmpty() ? "&7(none)" : String.join("\n", cmds.stream().map(c -> "&7- &f" + c).toList());
            Map<String, String> ph = Map.of("rank_key", rankKey, "commands", cmdBlock);

            List<String> lore = new ArrayList<>();
            for (String line : loreTpl) {
                if (line.contains("{commands}")) {
                    for (String c : cmds) {
                        lore.add(ColorUtil.colorize("&7- &f" + c));
                    }
                } else {
                    lore.add(ColorUtil.colorize(GuiConfig.apply(line, ph)));
                }
            }

            gui.setItem(slot++, buildGuiItem(mat, ColorUtil.colorize(GuiConfig.apply(nameTpl, ph)), lore, e -> {
                        Map<String, List<String>> updated = new LinkedHashMap<>(def.getRankRules());
                        if (e.getClick() == ClickType.SHIFT_RIGHT) {
                            updated.remove(rankKey);
                            configManager.savePodium(copyPodium(def, def.isEnabled(), def.getDisplayName(), def.getScheduleId(),
                                    def.getPlayerPlaceholder(), def.getValuePlaceholder(), def.getTopSize(), updated));
                            playSound(player, "delete");
                            openRewardEdit(player, podiumId);
                        } else if (e.getClick() == ClickType.RIGHT) {
                            List<String> list = new ArrayList<>(cmds);
                            if (!list.isEmpty()) list.remove(list.size() - 1);
                            updated.put(rankKey, list);
                            configManager.savePodium(copyPodium(def, def.isEnabled(), def.getDisplayName(), def.getScheduleId(),
                                    def.getPlayerPlaceholder(), def.getValuePlaceholder(), def.getTopSize(), updated));
                            playSound(player, "delete");
                            openRewardEdit(player, podiumId);
                        } else {
                            promptInput(player, newCmd -> {
                                List<String> list = new ArrayList<>(cmds);
                                list.add(newCmd);
                                updated.put(rankKey, list);
                                configManager.savePodium(copyPodium(def, def.isEnabled(), def.getDisplayName(), def.getScheduleId(),
                                        def.getPlayerPlaceholder(), def.getValuePlaceholder(), def.getTopSize(), updated));
                                playSound(player, "save");
                                openRewardEdit(player, podiumId);
                            }, () -> openRewardEdit(player, podiumId));
                        }
                    }));
        }

        int addSlot = cfg.getInt("menus.reward-edit.add-slot", 49);
        setConfiguredItem(gui, "menus.reward-edit.add-item", addSlot, Material.EMERALD, "&a&l+ Add Rank Rule", Map.of(),
                e -> promptInput(player, rankKey -> {
                    Map<String, List<String>> updated = new LinkedHashMap<>(def.getRankRules());
                    updated.putIfAbsent(rankKey.trim(), new ArrayList<>());
                    configManager.savePodium(copyPodium(def, def.isEnabled(), def.getDisplayName(), def.getScheduleId(),
                            def.getPlayerPlaceholder(), def.getValuePlaceholder(), def.getTopSize(), updated));
                    playSound(player, "save");
                    openRewardEdit(player, podiumId);
                }, () -> openRewardEdit(player, podiumId)));

        int backSlot = cfg.getInt("menus.reward-edit.back-slot", 45);
        setConfiguredItem(gui, "menus.reward-edit.back-item", backSlot, Material.ARROW, "&cBack", Map.of(),
                e -> { playSound(player, "click"); openPodiumEdit(player, podiumId); });

        openGui(player, gui);
    }

    public void openConditionEdit(Player player, String podiumId) {
        if (!checkAccess(player)) return;
        PodiumDefinition def = configManager.getPodiums().get(podiumId);
        if (def == null) {
            openPodiumList(player);
            return;
        }
        GuiConfig cfg = configManager.getGuiConfig();
        int rows = cfg.getRows("condition-edit", 6);
        Gui gui = createBaseGui(cfg.getTitle("condition-edit", "&e&lConditions: &f{id}", Map.of("id", podiumId)), rows);

        Material mat = cfg.getMaterial("menus.condition-edit.entry-material", Material.REDSTONE_TORCH);
        String nameTpl = cfg.getRawString("menus.condition-edit.entry-name", "&eCondition: &f{condition}");
        List<String> loreTpl = cfg.getStringList("menus.condition-edit.entry-lore");

        int slot = 0;
        int maxSlot = (rows - 1) * 9;
        List<ConditionSpec> current = def.getConditions();
        for (int i = 0; i < current.size(); i++) {
            if (slot >= maxSlot) break;
            final int index = i;
            ConditionSpec cond = current.get(i);
            Map<String, String> ph = Map.of("condition", cond.getRaw());
            gui.setItem(slot++, buildGuiItem(mat,
                    ColorUtil.colorize(GuiConfig.apply(nameTpl, ph)),
                    loreTpl.stream().map(l -> ColorUtil.colorize(GuiConfig.apply(l, ph))).toList(),
                    e -> {
                        if (e.getClick() == ClickType.SHIFT_RIGHT) {
                            List<ConditionSpec> updated = new ArrayList<>(current);
                            updated.remove(index);
                            configManager.savePodium(copyPodiumWithConditions(def, updated));
                            playSound(player, "delete");
                            openConditionEdit(player, podiumId);
                        }
                    }));
        }

        int addSlot = cfg.getInt("menus.condition-edit.add-slot", 49);
        setConfiguredItem(gui, "menus.condition-edit.add-item", addSlot, Material.EMERALD, "&a&l+ Add Condition", Map.of(),
                e -> promptInput(player, raw -> {
                    var parsed = ConditionSpec.parse(raw);
                    if (parsed.isEmpty()) {
                        playSound(player, "error");
                        openConditionEdit(player, podiumId);
                        return;
                    }
                    List<ConditionSpec> updated = new ArrayList<>(current);
                    updated.add(parsed.get());
                    configManager.savePodium(copyPodiumWithConditions(def, updated));
                    playSound(player, "save");
                    openConditionEdit(player, podiumId);
                }, () -> openConditionEdit(player, podiumId)));

        int backSlot = cfg.getInt("menus.condition-edit.back-slot", 45);
        setConfiguredItem(gui, "menus.condition-edit.back-item", backSlot, Material.ARROW, "&cBack", Map.of(),
                e -> { playSound(player, "click"); openPodiumEdit(player, podiumId); });

        openGui(player, gui);
    }

    public void openScheduleList(Player player) {
        if (!checkAccess(player)) return;
        GuiConfig cfg = configManager.getGuiConfig();
        int rows = cfg.getRows("schedule-list", 6);
        Gui gui = createBaseGui(cfg.getTitle("schedule-list", "&b&lRoyPodium &8| &7Schedules", Map.of()), rows);

        Material mat = cfg.getMaterial("menus.schedule-list.entry-material", Material.CLOCK);
        String nameTpl = cfg.getRawString("menus.schedule-list.entry-name", "&b{id}");
        List<String> loreTpl = cfg.getStringList("menus.schedule-list.entry-lore");

        int slot = 0;
        int maxSlot = (rows - 1) * 9;
        Instant now = Instant.now();
        for (ScheduleSpec spec : configManager.getSchedules().values()) {
            if (slot >= maxSlot) break;
            Instant next = spec.nextExecutionAfter(now);
            Map<String, String> ph = schedulePlaceholders(spec, next, now);
            gui.setItem(slot++, buildGuiItem(mat,
                    ColorUtil.colorize(GuiConfig.apply(nameTpl, ph)),
                    loreTpl.stream().map(l -> ColorUtil.colorize(GuiConfig.apply(l, ph))).toList(),
                    e -> {
                        if (e.getClick() == ClickType.SHIFT_RIGHT) {
                            configManager.deleteSchedule(spec.getId());
                            playSound(player, "delete");
                            openScheduleList(player);
                        } else {
                            playSound(player, "click");
                            openScheduleEdit(player, spec.getId());
                        }
                    }));
        }

        int createSlot = cfg.getInt("menus.schedule-list.create-slot", 49);
        setConfiguredItem(gui, "menus.schedule-list.create-item", createSlot, Material.EMERALD, "&a&l+ Create New Schedule", Map.of(),
                e -> promptInput(player, input -> {
                    String id = input.toLowerCase().replaceAll("[^a-z0-9_\\-]", "");
                    if (id.isEmpty()) {
                        playSound(player, "error");
                        openScheduleList(player);
                        return;
                    }
                    ScheduleSpec created = ScheduleSpec.builder()
                            .id(id)
                            .timezone(configManager.getMainConfig().getDefaultTimezone())
                            .frequency(ScheduleSpec.Frequency.DAILY)
                            .timeStart(LocalTime.MIDNIGHT)
                            .build();
                    configManager.saveSchedule(created);
                    playSound(player, "save");
                    openScheduleEdit(player, id);
                }, () -> openScheduleList(player)));

        int backSlot = cfg.getInt("menus.schedule-list.back-slot", 45);
        setConfiguredItem(gui, "menus.schedule-list.back-item", backSlot, Material.ARROW, "&cBack", Map.of(),
                e -> { playSound(player, "click"); openMainMenu(player); });

        openGui(player, gui);
    }

    public void openScheduleEdit(Player player, String scheduleId) {
        if (!checkAccess(player)) return;
        ScheduleSpec spec = configManager.getSchedules().get(scheduleId);
        if (spec == null) {
            openScheduleList(player);
            return;
        }
        GuiConfig cfg = configManager.getGuiConfig();
        int rows = cfg.getRows("schedule-edit", 3);
        Map<String, String> ph = schedulePlaceholders(spec, spec.nextExecutionAfter(Instant.now()), Instant.now());
        Gui gui = createBaseGui(cfg.getTitle("schedule-edit", "&b&lEdit Schedule: &f{id}", ph), rows);

        setConfiguredItem(gui, "menus.schedule-edit.items.timezone", 10, Material.COMPASS, "&eTimezone", ph,
                e -> promptInput(player, val -> {
                    ZoneId tz = TimeUtil.parseZoneId(val).orElse(null);
                    if (tz == null) {
                        playSound(player, "error");
                    } else {
                        configManager.saveSchedule(copySchedule(spec, tz, spec.getFrequency(), spec.getTimeStart()));
                        playSound(player, "save");
                    }
                    openScheduleEdit(player, scheduleId);
                }, () -> openScheduleEdit(player, scheduleId)));

        setConfiguredItem(gui, "menus.schedule-edit.items.frequency", 12, Material.REPEATER, "&eFrequency", ph,
                e -> {
                    ScheduleSpec.Frequency[] values = ScheduleSpec.Frequency.values();
                    ScheduleSpec.Frequency next = values[(spec.getFrequency().ordinal() + 1) % values.length];
                    configManager.saveSchedule(copySchedule(spec, spec.getTimezone(), next, spec.getTimeStart()));
                    playSound(player, "save");
                    openScheduleEdit(player, scheduleId);
                });

        setConfiguredItem(gui, "menus.schedule-edit.items.time-start", 14, Material.CLOCK, "&eStart Time", ph,
                e -> promptInput(player, val -> {
                    LocalTime lt = TimeUtil.parseLocalTime(val).orElse(null);
                    if (lt == null) {
                        playSound(player, "error");
                    } else {
                        configManager.saveSchedule(copySchedule(spec, spec.getTimezone(), spec.getFrequency(), lt));
                        playSound(player, "save");
                    }
                    openScheduleEdit(player, scheduleId);
                }, () -> openScheduleEdit(player, scheduleId)));

        setConfiguredItem(gui, "menus.schedule-edit.items.back", 22, Material.ARROW, "&cBack", ph,
                e -> { playSound(player, "click"); openScheduleList(player); });

        openGui(player, gui);
    }

    public void openQueueMenu(Player player) {
        if (!checkAccess(player)) return;
        GuiConfig cfg = configManager.getGuiConfig();
        int rows = cfg.getRows("queue-menu", 6);
        Gui gui = createBaseGui(cfg.getTitle("queue-menu", "&a&lRoyPodium &8| &7Offline Queue", Map.of()), rows);

        Material mat = cfg.getMaterial("menus.queue-menu.entry-material", Material.PLAYER_HEAD);
        String nameTpl = cfg.getRawString("menus.queue-menu.entry-name", "&e{player}");
        List<String> loreTpl = cfg.getStringList("menus.queue-menu.entry-lore");

        int slot = 0;
        int maxSlot = (rows - 1) * 9;
        for (Map.Entry<UUID, List<PendingReward>> entry : queueManager.snapshotAll().entrySet()) {
            if (slot >= maxSlot) break;
            UUID uuid = entry.getKey();
            List<PendingReward> list = entry.getValue();
            String name = list.isEmpty() ? uuid.toString() : list.get(0).getLastKnownName();
            int cmdCount = list.stream().mapToInt(r -> r.getCommands().size()).sum();
            Map<String, String> ph = Map.of(
                    "player", name,
                    "uuid", uuid.toString(),
                    "count", String.valueOf(list.size()),
                    "commands", String.valueOf(cmdCount)
            );
            gui.setItem(slot++, buildGuiItem(mat,
                    ColorUtil.colorize(GuiConfig.apply(nameTpl, ph)),
                    loreTpl.stream().map(l -> ColorUtil.colorize(GuiConfig.apply(l, ph))).toList(),
                    e -> {
                        if (e.getClick() == ClickType.SHIFT_RIGHT) {
                            queueManager.clearPlayer(uuid);
                            playSound(player, "delete");
                            openQueueMenu(player);
                        }
                    }));
        }

        int clearSlot = cfg.getInt("menus.queue-menu.clear-all-slot", 49);
        setConfiguredItem(gui, "menus.queue-menu.clear-all-item", clearSlot, Material.BARRIER, "&c&lClear All", Map.of(),
                e -> {
                    if (e.isShiftClick()) {
                        queueManager.clearAll();
                        playSound(player, "delete");
                        openQueueMenu(player);
                    }
                });

        int backSlot = cfg.getInt("menus.queue-menu.back-slot", 45);
        setConfiguredItem(gui, "menus.queue-menu.back-item", backSlot, Material.ARROW, "&cBack", Map.of(),
                e -> { playSound(player, "click"); openMainMenu(player); });

        openGui(player, gui);
    }

    public void openSettingsMenu(Player player) {
        if (!checkAccess(player)) return;
        GuiConfig cfg = configManager.getGuiConfig();
        int rows = cfg.getRows("settings-menu", 3);
        var main = configManager.getMainConfig();
        Map<String, String> ph = Map.of(
                "commands_per_tick", String.valueOf(main.getCommandsPerTick()),
                "missed_policy", main.getMissedSchedulePolicy().name(),
                "expiration", main.getQueueExpiration().isZero() ? "0" : TimeUtil.formatDuration(main.getQueueExpiration()).replace(" ", "")
        );
        Gui gui = createBaseGui(cfg.getTitle("settings-menu", "&d&lRoyPodium &8| &7Settings", ph), rows);

        setConfiguredItem(gui, "menus.settings-menu.items.commands-per-tick", 11, Material.REDSTONE, "&eCommands Per Tick", ph,
                e -> {
                    int delta = e.getClick() == ClickType.RIGHT ? -1 : 1;
                    int next = Math.max(1, Math.min(100, main.getCommandsPerTick() + delta));
                    configManager.updateMainSetting("dispatch.commands-per-tick", next);
                    playSound(player, "save");
                    openSettingsMenu(player);
                });

        setConfiguredItem(gui, "menus.settings-menu.items.missed-policy", 13, Material.HOPPER, "&eMissed Schedule Policy", ph,
                e -> {
                    MissedPolicy[] vals = MissedPolicy.values();
                    MissedPolicy next = vals[(main.getMissedSchedulePolicy().ordinal() + 1) % vals.length];
                    configManager.updateMainSetting("settings.missed-schedule-policy", next.name());
                    playSound(player, "save");
                    openSettingsMenu(player);
                });

        setConfiguredItem(gui, "menus.settings-menu.items.queue-expiration", 15, Material.CLOCK, "&eQueue Expiration", ph,
                e -> promptInput(player, val -> {
                    if (TimeUtil.parseDuration(val).isEmpty()) {
                        playSound(player, "error");
                    } else {
                        configManager.updateMainSetting("queue.expiration", val.trim());
                        playSound(player, "save");
                    }
                    openSettingsMenu(player);
                }, () -> openSettingsMenu(player)));

        setConfiguredItem(gui, "menus.settings-menu.items.back", 22, Material.ARROW, "&cBack", ph,
                e -> { playSound(player, "click"); openMainMenu(player); });

        openGui(player, gui);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean checkAccess(Player player) {
        if (!player.hasPermission("roypodium.editor")) {
            configManager.getMessages().send(player, "general.no-permission",
                    "{prefix}&cYou do not have permission to use this command.");
            return false;
        }
        return true;
    }

    private Gui createBaseGui(String title, int rows) {
        Gui gui = Gui.gui()
                .title(Component.text(title))
                .rows(rows)
                .disableAllInteractions()
                .create();

        GuiConfig cfg = configManager.getGuiConfig();
        if (cfg.getBoolean("filler.enabled", true)) {
            Material fillerMat = cfg.getMaterial("filler.material", Material.GRAY_STAINED_GLASS_PANE);
            String fillerName = cfg.getString("filler.name", " ");
            GuiItem filler = buildGuiItem(fillerMat, fillerName, List.of(), null);
            gui.getFiller().fill(filler);
        }
        return gui;
    }

    private void openGui(Player player, Gui gui) {
        EditorSession session = sessions.computeIfAbsent(player.getUniqueId(), EditorSession::new);
        session.setTransitioning(true);
        scheduler.runAtEntity(player, () -> {
            if (!player.isOnline()) return;
            trackedInventories.add(gui.getInventory());
            gui.open(player);
            session.setTransitioning(false);
        });
    }

    private void promptInput(Player player, java.util.function.Consumer<String> onInput, Runnable onCancel) {
        EditorSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.setTransitioning(true);
        }
        scheduler.runAtEntity(player, () -> {
            player.closeInventory();
            chatInput.prompt(player, onInput, onCancel);
        });
    }

    private void setConfiguredItem(Gui gui, String path, int defaultSlot, Material defaultMat, String defaultName,
                                   Map<String, String> ph, dev.triumphteam.gui.components.GuiAction<org.bukkit.event.inventory.InventoryClickEvent> action) {
        GuiConfig cfg = configManager.getGuiConfig();
        ConfigurationSection sec = cfg.getSection(path);
        int slot = sec != null ? sec.getInt("slot", defaultSlot) : defaultSlot;
        Material mat = cfg.getMaterial(path + ".material", defaultMat);
        String name = ColorUtil.colorize(GuiConfig.apply(cfg.getRawString(path + ".name", defaultName), ph));
        List<String> lore = cfg.getStringList(path + ".lore").stream()
                .map(l -> ColorUtil.colorize(GuiConfig.apply(l, ph)))
                .toList();
        gui.setItem(slot, buildGuiItem(mat, name, lore, action));
    }

    @SuppressWarnings("deprecation")
    private GuiItem buildGuiItem(Material mat, String displayName, List<String> loreLines,
                                 dev.triumphteam.gui.components.GuiAction<org.bukkit.event.inventory.InventoryClickEvent> action) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(displayName);
            if (loreLines != null && !loreLines.isEmpty()) {
                meta.setLore(loreLines);
            }
            stack.setItemMeta(meta);
        }
        return action == null ? ItemBuilder.from(stack).asGuiItem() : ItemBuilder.from(stack).asGuiItem(action);
    }

    private void playSound(Player player, String key) {
        Sound sound = configManager.getGuiConfig().getSound(key, Sound.UI_BUTTON_CLICK);
        player.playSound(player.getLocation(), sound, 0.7f, 1.0f);
    }

    private Map<String, String> podiumPlaceholders(PodiumDefinition def) {
        GuiConfig cfg = configManager.getGuiConfig();
        String status = def.isEnabled()
                ? cfg.getString("labels.enabled-true", "&aEnabled")
                : cfg.getString("labels.enabled-false", "&cDisabled");
        String sched = (def.getScheduleId() == null || def.getScheduleId().isBlank())
                ? cfg.getString("labels.no-schedule", "&cNone")
                : def.getScheduleId();
        return Map.of(
                "id", def.getId(),
                "display_name", ColorUtil.colorize(def.getDisplayName()),
                "status", status,
                "schedule", sched,
                "top_size", String.valueOf(def.getTopSize()),
                "player_papi", def.getPlayerPlaceholder(),
                "value_papi", def.getValuePlaceholder(),
                "rank_count", String.valueOf(def.getRankRules().size()),
                "condition_count", String.valueOf(def.getConditions() == null ? 0 : def.getConditions().size())
        );
    }

    private Map<String, String> schedulePlaceholders(ScheduleSpec spec, Instant next, Instant now) {
        String extra = switch (spec.getFrequency()) {
            case DAILY -> "Daily";
            case WEEKLY -> String.valueOf(spec.getDaysOfWeek());
            case MONTHLY -> spec.getDayOfMonth() < 0 ? "LAST" : String.valueOf(spec.getDayOfMonth());
            case INTERVAL -> TimeUtil.formatDuration(spec.getInterval());
        };
        return Map.of(
                "id", spec.getId(),
                "timezone", spec.getTimezone().getId(),
                "frequency", spec.getFrequency().name(),
                "time_start", spec.getTimeStart().toString(),
                "next_run", TimeUtil.formatDuration(Duration.between(now, next)),
                "extra", extra
        );
    }

    private PodiumDefinition copyPodium(PodiumDefinition src, boolean enabled, String displayName, String scheduleId,
                                        String playerPapi, String valuePapi, int topSize, Map<String, List<String>> rankRules) {
        return PodiumDefinition.builder()
                .id(src.getId())
                .enabled(enabled)
                .displayName(displayName)
                .scheduleId(scheduleId)
                .playerPlaceholder(playerPapi)
                .valuePlaceholder(valuePapi)
                .topSize(topSize)
                .tiePolicy(src.getTiePolicy())
                .conditions(src.getConditions())
                .rankRules(rankRules)
                .build();
    }

    private PodiumDefinition copyPodiumWithConditions(PodiumDefinition src, List<ConditionSpec> conditions) {
        return PodiumDefinition.builder()
                .id(src.getId())
                .enabled(src.isEnabled())
                .displayName(src.getDisplayName())
                .scheduleId(src.getScheduleId())
                .playerPlaceholder(src.getPlayerPlaceholder())
                .valuePlaceholder(src.getValuePlaceholder())
                .topSize(src.getTopSize())
                .tiePolicy(src.getTiePolicy())
                .conditions(List.copyOf(conditions))
                .rankRules(src.getRankRules())
                .build();
    }

    private ScheduleSpec copySchedule(ScheduleSpec src, ZoneId tz, ScheduleSpec.Frequency freq, LocalTime timeStart) {
        return ScheduleSpec.builder()
                .id(src.getId())
                .timezone(tz)
                .frequency(freq)
                .timeStart(timeStart)
                .daysOfWeek(src.getDaysOfWeek())
                .dayOfMonth(src.getDayOfMonth())
                .interval(src.getInterval())
                .missedPolicy(src.getMissedPolicy())
                .build();
    }
}
