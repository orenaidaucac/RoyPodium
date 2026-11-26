package com.github.orenaidaucac.royPodium.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Dynamically registers and unregisters custom command aliases for {@code /roypodium} at runtime
 * without requiring a server restart, while protecting other plugins' commands from collisions.
 */
public final class CommandAliasManager {

    private static final Pattern VALID_ALIAS = Pattern.compile("^[a-z0-9_\\-]+$");

    private final Plugin plugin;
    private final List<RoyPodiumAliasCommand> registeredAliases = new ArrayList<>();

    public CommandAliasManager(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Unregisters previous dynamic aliases and registers the updated list from {@code config.yml}.
     */
    public void reloadAliases(List<String> aliases, PodiumCommand executor) {
        CommandMap commandMap = resolveCommandMap();
        if (commandMap == null) {
            plugin.getLogger().warning("[config.yml] Unable to access Bukkit CommandMap; dynamic 'command-aliases' are unavailable on this server.");
            return;
        }

        unregisterFromMap(commandMap);

        if (aliases == null || aliases.isEmpty()) {
            syncCommands();
            return;
        }

        Map<String, Command> known = getKnownCommands(commandMap);
        for (String raw : aliases) {
            if (raw == null || raw.isBlank()) {
                plugin.getLogger().warning("[config.yml] Key 'command-aliases' contains an empty alias. Fix: Remove the blank entry or provide a valid command name.");
                continue;
            }
            String alias = raw.trim().toLowerCase(Locale.ROOT);
            if (alias.startsWith("/")) {
                alias = alias.substring(1);
            }
            if (!VALID_ALIAS.matcher(alias).matches()) {
                plugin.getLogger().warning("[config.yml] Key 'command-aliases' has invalid alias '" + raw
                        + "'. Fix: Use only lowercase letters, numbers, '-' or '_' (no spaces or colons).");
                continue;
            }
            if ("roypodium".equals(alias) || "podium".equals(alias)) {
                continue;
            }

            if (known != null) {
                Command existing = known.get(alias);
                if (existing != null && !(existing instanceof RoyPodiumAliasCommand)) {
                    plugin.getLogger().warning("[config.yml] Alias '" + alias + "' conflicts with an existing command from another plugin ("
                            + existing.getClass().getSimpleName() + "). Skipping to avoid breaking that plugin.");
                    continue;
                }
            }

            RoyPodiumAliasCommand cmd = new RoyPodiumAliasCommand(alias, plugin, executor);
            commandMap.register("roypodium", cmd);
            registeredAliases.add(cmd);
        }

        syncCommands();
    }

    public void unregisterAll() {
        CommandMap commandMap = resolveCommandMap();
        if (commandMap != null) {
            unregisterFromMap(commandMap);
            syncCommands();
        }
        registeredAliases.clear();
    }

    private void unregisterFromMap(CommandMap commandMap) {
        Map<String, Command> known = getKnownCommands(commandMap);
        if (known != null) {
            Iterator<Map.Entry<String, Command>> it = known.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Command> entry = it.next();
                if (entry.getValue() instanceof RoyPodiumAliasCommand) {
                    it.remove();
                }
            }
        }
        for (RoyPodiumAliasCommand cmd : registeredAliases) {
            cmd.unregister(commandMap);
        }
        registeredAliases.clear();
    }

    private CommandMap resolveCommandMap() {
        try {
            Method m = Bukkit.getServer().getClass().getMethod("getCommandMap");
            if (m.invoke(Bukkit.getServer()) instanceof CommandMap cm) {
                return cm;
            }
        } catch (Exception ignored) {
        }
        try {
            Field f = Bukkit.getPluginManager().getClass().getDeclaredField("commandMap");
            f.setAccessible(true);
            if (f.get(Bukkit.getPluginManager()) instanceof CommandMap cm) {
                return cm;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Command> getKnownCommands(CommandMap commandMap) {
        try {
            Method m = commandMap.getClass().getMethod("getKnownCommands");
            if (m.invoke(commandMap) instanceof Map<?, ?> map) {
                return (Map<String, Command>) map;
            }
        } catch (Exception ignored) {
        }
        try {
            Field f = commandMap.getClass().getDeclaredField("knownCommands");
            f.setAccessible(true);
            if (f.get(commandMap) instanceof Map<?, ?> map) {
                return (Map<String, Command>) map;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void syncCommands() {
        try {
            Method sync = Bukkit.getServer().getClass().getMethod("syncCommands");
            sync.invoke(Bukkit.getServer());
        } catch (Exception ignored) {
        }
    }

    private static final class RoyPodiumAliasCommand extends Command implements PluginIdentifiableCommand {

        private final Plugin plugin;
        private final PodiumCommand delegate;

        RoyPodiumAliasCommand(String name, Plugin plugin, PodiumCommand delegate) {
            super(name, "Custom alias for /roypodium", "/" + name + " help", List.of());
            this.plugin = plugin;
            this.delegate = delegate;
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            return delegate.onCommand(sender, this, commandLabel, args);
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return delegate.onTabComplete(sender, this, alias, args);
        }

        @Override
        public Plugin getPlugin() {
            return plugin;
        }
    }
}
