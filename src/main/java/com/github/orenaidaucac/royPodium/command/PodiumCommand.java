package com.github.orenaidaucac.royPodium.command;

import com.github.orenaidaucac.royPodium.RoyPodium;
import com.github.orenaidaucac.royPodium.config.ConfigManager;
import com.github.orenaidaucac.royPodium.config.MainConfig;
import com.github.orenaidaucac.royPodium.gui.GuiManager;
import com.github.orenaidaucac.royPodium.podium.PlaceholderResolver;
import com.github.orenaidaucac.royPodium.podium.PodiumDefinition;
import com.github.orenaidaucac.royPodium.podium.PodiumManager;
import com.github.orenaidaucac.royPodium.podium.PodiumSnapshot;
import com.github.orenaidaucac.royPodium.queue.PendingReward;
import com.github.orenaidaucac.royPodium.queue.QueueManager;
import com.github.orenaidaucac.royPodium.scheduler.ScheduleSpec;
import com.github.orenaidaucac.royPodium.scheduler.SchedulerAdapter;
import com.github.orenaidaucac.royPodium.util.ColorUtil;
import com.github.orenaidaucac.royPodium.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Main command executor and TabCompleter for {@code /roypodium} and {@code /podium},
 * supporting built-in admin commands and unlimited custom subcommands from {@code config.yml}.
 */
public final class PodiumCommand implements CommandExecutor, TabCompleter {

    private final RoyPodium plugin;
    private final ConfigManager configManager;
    private final SchedulerAdapter scheduler;
    private final PlaceholderResolver placeholderResolver;
    private final PodiumManager podiumManager;
    private final QueueManager queueManager;
    private final GuiManager guiManager;

    public PodiumCommand(RoyPodium plugin,
                         ConfigManager configManager,
                         SchedulerAdapter scheduler,
                         PlaceholderResolver placeholderResolver,
                         PodiumManager podiumManager,
                         QueueManager queueManager,
                         GuiManager guiManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.scheduler = scheduler;
        this.placeholderResolver = placeholderResolver;
        this.podiumManager = podiumManager;
        this.queueManager = queueManager;
        this.guiManager = guiManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reload" -> handleReload(sender);
            case "status" -> handleStatus(sender);
            case "editor", "gui" -> handleEditor(sender);
            case "test" -> handleTest(sender, label, args);
            case "queue" -> handleQueue(sender, label, args);
            case "webhook" -> handleWebhook(sender, label, args);
            default -> {
                MainConfig.CustomSubcommand custom = configManager.getMainConfig().getCustomSubcommands().get(sub);
                if (custom != null) {
                    handleCustomSubcommand(sender, custom);
                } else {
                    configManager.getMessages().send(sender, "general.unknown-subcommand",
                            "{prefix}&cUnknown subcommand. Use &e/{label} help &cfor a list of commands.",
                            Map.of("label", label));
                }
            }
        }
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        var msgs = configManager.getMessages();
        sender.sendMessage(msgs.format("help.header", "&8&m----------&r &6&lRoyPodium Help &8&m----------"));
        if (sender.hasPermission("roypodium.admin")) {
            for (String line : msgs.getStringList("help.lines")) {
                sender.sendMessage(ColorUtil.colorize(line.replace("{label}", label)));
            }
        }
        for (MainConfig.CustomSubcommand custom : configManager.getMainConfig().getCustomSubcommands().values()) {
            if (custom.getPermission() == null || custom.getPermission().isBlank() || sender.hasPermission(custom.getPermission())) {
                sender.sendMessage(msgs.format("help.custom-line", "&e/{label} {name} &7- {description}",
                        Map.of("label", label, "name", custom.getName(), "description", custom.getDescription())));
            }
        }
        sender.sendMessage(msgs.format("help.footer", "&8&m----------------------------------------"));
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("roypodium.reload")) {
            configManager.getMessages().send(sender, "general.no-permission", "{prefix}&cYou do not have permission to use this command.");
            return;
        }
        long start = System.currentTimeMillis();
        boolean clean = plugin.reloadPlugin();
        long elapsed = System.currentTimeMillis() - start;
        if (clean) {
            configManager.getMessages().send(sender, "general.reloaded",
                    "{prefix}&aConfiguration reloaded successfully in &e{ms}ms&a.",
                    Map.of("ms", String.valueOf(elapsed)));
        } else {
            configManager.getMessages().send(sender, "general.reload-failed",
                    "{prefix}&cReload completed with configuration warnings. Check console for details.");
        }
    }

    private void handleStatus(CommandSender sender) {
        if (!sender.hasPermission("roypodium.status")) {
            configManager.getMessages().send(sender, "general.no-permission", "{prefix}&cYou do not have permission to use this command.");
            return;
        }
        var msgs = configManager.getMessages();
        boolean papiOk = placeholderResolver.isPlaceholderApiAvailable();
        long enabledCount = configManager.getPodiums().values().stream().filter(PodiumDefinition::isEnabled).count();
        var pending = queueManager.snapshotAll();

        sender.sendMessage(msgs.format("status.header", "&8&m----------&r &6&lRoyPodium Status &8&m----------"));
        sender.sendMessage(msgs.format("status.papi-line", "&7PlaceholderAPI: {state}",
                Map.of("state", papiOk ? "&aConnected" : "&cMissing")));
        sender.sendMessage(msgs.format("status.podiums-line", "&7Loaded Podiums: &e{enabled}&7/&e{total}",
                Map.of("enabled", String.valueOf(enabledCount), "total", String.valueOf(configManager.getPodiums().size()))));
        sender.sendMessage(msgs.format("status.schedules-line", "&7Loaded Schedules: &e{total}",
                Map.of("total", String.valueOf(configManager.getSchedules().size()))));
        sender.sendMessage(msgs.format("status.queue-line", "&7Pending Offline Rewards: &e{players} players &7(&e{entries} entries&7)",
                Map.of("players", String.valueOf(pending.size()), "entries", String.valueOf(queueManager.getTotalEntryCount()))));

        Instant now = Instant.now();
        for (PodiumDefinition def : configManager.getPodiums().values()) {
            if (!def.isEnabled()) continue;
            ScheduleSpec spec = configManager.getSchedules().get(def.getScheduleId());
            String nextStr;
            if (spec == null) {
                nextStr = "No schedule";
            } else {
                Instant next = spec.nextExecutionAfter(now);
                nextStr = next == null
                        ? configManager.getGuiConfig().getRawString("labels.no-upcoming-run", "No upcoming run")
                        : TimeUtil.formatDuration(Duration.between(now, next));
            }
            sender.sendMessage(msgs.format("status.schedule-entry", "&7- &f{podium} &8(&7{schedule}&8): &eNext in {next}",
                    Map.of("podium", def.getId(), "schedule", def.getScheduleId(), "next", nextStr)));
        }
        sender.sendMessage(msgs.format("status.footer", "&8&m----------------------------------------"));
    }

    private void handleEditor(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            configManager.getMessages().send(sender, "general.players-only", "{prefix}&cOnly in-game players can use this command.");
            return;
        }
        if (!player.hasPermission("roypodium.editor")) {
            configManager.getMessages().send(player, "general.no-permission", "{prefix}&cYou do not have permission to use this command.");
            return;
        }
        guiManager.openMainMenu(player);
    }

    private void handleTest(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("roypodium.test")) {
            configManager.getMessages().send(sender, "general.no-permission", "{prefix}&cYou do not have permission to use this command.");
            return;
        }
        if (args.length < 2) {
            configManager.getMessages().send(sender, "test.usage", "{prefix}&cUsage: /{label} test <podium|all> [simulate|execute]",
                    Map.of("label", label));
            return;
        }
        String target = args[1];
        boolean simulate = args.length < 3 || !"execute".equalsIgnoreCase(args[2]);

        List<PodiumDefinition> targets = new ArrayList<>();
        if ("all".equalsIgnoreCase(target)) {
            targets.addAll(configManager.getPodiums().values());
        } else {
            PodiumDefinition def = configManager.getPodiums().get(target);
            if (def == null) {
                configManager.getMessages().send(sender, "test.podium-not-found",
                        "{prefix}&cPodium '&e{id}&c' does not exist.", Map.of("id", target));
                return;
            }
            targets.add(def);
        }

        configManager.getMessages().send(sender, "test.started",
                "{prefix}&7Running &e{mode}&7 payout for &e{target}&7...",
                Map.of("mode", simulate ? "SIMULATE" : "EXECUTE", "target", target));

        scheduler.runGlobal(() -> {
            for (PodiumDefinition def : targets) {
                String payoutId = (simulate ? "sim@" : "test@") + def.getId() + "@" + System.currentTimeMillis();
                podiumManager.runPayout(def, payoutId, simulate, sender);
            }
        });
    }

    private void handleQueue(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("roypodium.queue")) {
            configManager.getMessages().send(sender, "general.no-permission", "{prefix}&cYou do not have permission to use this command.");
            return;
        }
        if (args.length < 2) {
            configManager.getMessages().send(sender, "queue.usage", "{prefix}&cUsage: /{label} queue <list|inspect|clear> ...",
                    Map.of("label", label));
            return;
        }
        var msgs = configManager.getMessages();
        String action = args[1].toLowerCase();

        switch (action) {
            case "list" -> {
                var all = new ArrayList<>(queueManager.snapshotAll().entrySet());
                if (all.isEmpty()) {
                    msgs.send(sender, "queue.empty", "{prefix}&7There are currently no pending offline rewards.");
                    return;
                }
                int page = 1;
                if (args.length >= 3) {
                    try { page = Math.max(1, Integer.parseInt(args[2])); } catch (NumberFormatException ignored) {}
                }
                int perPage = 10;
                int pages = Math.max(1, (int) Math.ceil(all.size() / (double) perPage));
                page = Math.min(page, pages);

                sender.sendMessage(msgs.format("queue.list-header", "&8&m----&r &6Pending Offline Queue (Page {page}/{pages}) &8&m----",
                        Map.of("page", String.valueOf(page), "pages", String.valueOf(pages))));
                int start = (page - 1) * perPage;
                int end = Math.min(start + perPage, all.size());
                for (int i = start; i < end; i++) {
                    var entry = all.get(i);
                    String name = entry.getValue().get(0).getLastKnownName();
                    sender.sendMessage(msgs.format("queue.list-entry", "&e{player} &8(&7{uuid}&8) &7- &f{count} reward(s)",
                            Map.of("player", name, "uuid", entry.getKey().toString(), "count", String.valueOf(entry.getValue().size()))));
                }
            }
            case "inspect" -> {
                if (args.length < 3) {
                    msgs.send(sender, "queue.usage", "{prefix}&cUsage: /{label} queue <list|inspect|clear> ...", Map.of("label", label));
                    return;
                }
                Optional<UUID> uuidOpt = queueManager.findUuidByNameOrUuid(args[2]);
                if (uuidOpt.isEmpty()) {
                    msgs.send(sender, "queue.player-not-found", "{prefix}&cNo pending rewards found for '&e{target}&c'.",
                            Map.of("target", args[2]));
                    return;
                }
                List<PendingReward> rewards = queueManager.getPending(uuidOpt.get());
                long now = System.currentTimeMillis();
                sender.sendMessage(msgs.format("queue.inspect-header", "&8&m----&r &6Queue for {player} &8&m----",
                        Map.of("player", args[2])));
                for (PendingReward r : rewards) {
                    String exp = r.getExpiresAtEpochMs() <= 0
                            ? "Never"
                            : TimeUtil.formatDuration(Duration.ofMillis(Math.max(0, r.getExpiresAtEpochMs() - now)));
                    sender.sendMessage(msgs.format("queue.inspect-entry", "&7- &e{podium} &7#{rank} &8(&7{commands} cmds, expires in {expires}&8)",
                            Map.of("podium", r.getPodiumId(), "rank", String.valueOf(r.getRank()),
                                    "commands", String.valueOf(r.getCommands().size()), "expires", exp)));
                }
            }
            case "clear" -> {
                if (args.length < 3) {
                    msgs.send(sender, "queue.usage", "{prefix}&cUsage: /{label} queue <list|inspect|clear> ...", Map.of("label", label));
                    return;
                }
                if ("all".equalsIgnoreCase(args[2])) {
                    int cleared = queueManager.clearAll();
                    msgs.send(sender, "queue.cleared-all", "{prefix}&aCleared all &e{count}&a pending reward(s) from the offline queue.",
                            Map.of("count", String.valueOf(cleared)));
                    return;
                }
                Optional<UUID> uuidOpt = queueManager.findUuidByNameOrUuid(args[2]);
                if (uuidOpt.isEmpty()) {
                    msgs.send(sender, "queue.player-not-found", "{prefix}&cNo pending rewards found for '&e{target}&c'.",
                            Map.of("target", args[2]));
                    return;
                }
                int cleared = queueManager.clearPlayer(uuidOpt.get());
                msgs.send(sender, "queue.cleared-player", "{prefix}&aCleared &e{count}&a pending reward(s) for &e{target}&a.",
                        Map.of("count", String.valueOf(cleared), "target", args[2]));
            }
            default -> msgs.send(sender, "queue.usage", "{prefix}&cUsage: /{label} queue <list|inspect|clear> ...",
                    Map.of("label", label));
        }
    }

    private void handleWebhook(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("roypodium.webhook")) {
            configManager.getMessages().send(sender, "general.no-permission", "{prefix}&cYou do not have permission to use this command.");
            return;
        }
        if (args.length < 2) {
            configManager.getMessages().send(sender, "webhook.usage",
                    "{prefix}&cUsage: /{label} webhook <preview|send|validate> ...",
                    Map.of("label", label));
            return;
        }
        String mode = args[1].toLowerCase();
        if ("validate".equals(mode)) {
            String tplOpt = args.length >= 3 ? args[2] : "all";
            plugin.getDiscordManager().validateTemplates(sender, tplOpt);
            return;
        }
        if (args.length < 3 || (!"preview".equals(mode) && !"send".equals(mode))) {
            configManager.getMessages().send(sender, "webhook.usage",
                    "{prefix}&cUsage: /{label} webhook <preview|send> <podium> [webhook] [template] | validate [template|all]",
                    Map.of("label", label));
            return;
        }
        String podiumId = args[2];
        PodiumDefinition def = configManager.getPodiums().get(podiumId);
        if (def == null) {
            configManager.getMessages().send(sender, "test.podium-not-found",
                    "{prefix}&cPodium '&e{id}&c' does not exist.", Map.of("id", podiumId));
            return;
        }
        String webhookOpt = args.length >= 4 ? args[3] : null;
        String templateOpt = args.length >= 5 ? args[4] : null;
        boolean sendLive = "send".equals(mode);

        scheduler.runGlobal(() -> {
            PodiumSnapshot snapshot = placeholderResolver.isPlaceholderApiAvailable()
                    ? podiumManager.captureSnapshot(def, "webhook-test@" + System.currentTimeMillis())
                    : createSampleSnapshot(def);
            if (snapshot.getEntries().isEmpty() && snapshot.getSkippedEntries().isEmpty()) {
                snapshot = createSampleSnapshot(def);
            }
            plugin.getDiscordManager().runTest(sender, def, snapshot, webhookOpt, templateOpt, sendLive);
        });
    }

    private PodiumSnapshot createSampleSnapshot(PodiumDefinition def) {
        return PodiumSnapshot.builder()
                .podiumId(def.getId())
                .displayName(def.getDisplayName())
                .payoutId("sample@" + System.currentTimeMillis())
                .capturedAt(Instant.now())
                .entries(List.of(
                        PodiumSnapshot.Entry.builder().rank(1).playerName("SampleTop1").value("1250").online(true).commands(List.of()).build(),
                        PodiumSnapshot.Entry.builder().rank(2).playerName("SampleTop2").value("840").online(false).commands(List.of()).build()
                ))
                .build();
    }

    private void handleCustomSubcommand(CommandSender sender, MainConfig.CustomSubcommand custom) {
        if (custom.getPermission() != null && !custom.getPermission().isBlank() && !sender.hasPermission(custom.getPermission())) {
            configManager.getMessages().send(sender, "general.no-permission", "{prefix}&cYou do not have permission to use this command.");
            return;
        }
        String senderName = sender.getName();
        String uuidStr = sender instanceof Player p ? p.getUniqueId().toString() : "CONSOLE";

        for (String line : custom.getMessages()) {
            sender.sendMessage(ColorUtil.colorize(line.replace("{sender}", senderName).replace("{uuid}", uuidStr)));
        }
        if (!custom.getConsoleCommands().isEmpty()) {
            scheduler.runGlobal(() -> {
                for (String cmd : custom.getConsoleCommands()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{sender}", senderName).replace("{uuid}", uuidStr));
                }
            });
        }
        if (sender instanceof Player player && !custom.getPlayerCommands().isEmpty()) {
            scheduler.runAtEntity(player, () -> {
                for (String cmd : custom.getPlayerCommands()) {
                    player.performCommand(cmd.replace("{sender}", senderName).replace("{uuid}", uuidStr));
                }
            });
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            subs.add("help");
            if (sender.hasPermission("roypodium.status")) subs.add("status");
            if (sender.hasPermission("roypodium.editor")) subs.add("editor");
            if (sender.hasPermission("roypodium.test")) subs.add("test");
            if (sender.hasPermission("roypodium.queue")) subs.add("queue");
            if (sender.hasPermission("roypodium.webhook")) subs.add("webhook");
            if (sender.hasPermission("roypodium.reload")) subs.add("reload");
            for (MainConfig.CustomSubcommand custom : configManager.getMainConfig().getCustomSubcommands().values()) {
                if (custom.getPermission() == null || custom.getPermission().isBlank() || sender.hasPermission(custom.getPermission())) {
                    subs.add(custom.getName());
                }
            }
            return filterPrefix(subs, args[0]);
        }

        if (args.length == 2) {
            if ("test".equalsIgnoreCase(args[0]) && sender.hasPermission("roypodium.test")) {
                List<String> opts = new ArrayList<>(configManager.getPodiums().keySet());
                opts.add("all");
                return filterPrefix(opts, args[1]);
            }
            if ("queue".equalsIgnoreCase(args[0]) && sender.hasPermission("roypodium.queue")) {
                return filterPrefix(List.of("list", "inspect", "clear"), args[1]);
            }
            if ("webhook".equalsIgnoreCase(args[0]) && sender.hasPermission("roypodium.webhook")) {
                return filterPrefix(List.of("preview", "send", "validate"), args[1]);
            }
        }

        if (args.length == 3) {
            if ("test".equalsIgnoreCase(args[0]) && sender.hasPermission("roypodium.test")) {
                return filterPrefix(List.of("simulate", "execute"), args[2]);
            }
            if ("webhook".equalsIgnoreCase(args[0]) && sender.hasPermission("roypodium.webhook")) {
                if ("validate".equalsIgnoreCase(args[1])) {
                    List<String> tpls = new ArrayList<>();
                    tpls.add("all");
                    if (configManager.getDiscordConfig() != null) {
                        tpls.addAll(configManager.getDiscordConfig().getTemplates().keySet());
                    }
                    return filterPrefix(tpls, args[2]);
                }
                return filterPrefix(new ArrayList<>(configManager.getPodiums().keySet()), args[2]);
            }
            if ("queue".equalsIgnoreCase(args[0]) && sender.hasPermission("roypodium.queue")) {
                if ("inspect".equalsIgnoreCase(args[1]) || "clear".equalsIgnoreCase(args[1])) {
                    List<String> names = new ArrayList<>();
                    if ("clear".equalsIgnoreCase(args[1])) {
                        names.add("all");
                    }
                    queueManager.snapshotAll().forEach((uuid, list) -> {
                        if (!list.isEmpty()) names.add(list.get(0).getLastKnownName());
                    });
                    return filterPrefix(names, args[2]);
                }
            }
        }

        if (args.length == 4 && "webhook".equalsIgnoreCase(args[0]) && sender.hasPermission("roypodium.webhook")
                && !"validate".equalsIgnoreCase(args[1])) {
            var discordCfg = configManager.getDiscordConfig();
            if (discordCfg != null) {
                return filterPrefix(new ArrayList<>(discordCfg.getWebhooks().keySet()), args[3]);
            }
        }
        if (args.length == 5 && "webhook".equalsIgnoreCase(args[0]) && sender.hasPermission("roypodium.webhook")
                && !"validate".equalsIgnoreCase(args[1])) {
            var discordCfg = configManager.getDiscordConfig();
            if (discordCfg != null) {
                return filterPrefix(new ArrayList<>(discordCfg.getTemplates().keySet()), args[4]);
            }
        }
        return List.of();
    }

    private List<String> filterPrefix(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase();
        return options.stream().filter(o -> o.toLowerCase().startsWith(lower)).toList();
    }
}
