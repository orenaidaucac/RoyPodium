package com.github.orenaidaucac.royPodium.gui;

import com.github.orenaidaucac.royPodium.config.ConfigManager;
import com.github.orenaidaucac.royPodium.scheduler.SchedulerAdapter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Folia-safe chat input prompt handler for editing strings, placeholders, and commands in-game.
 */
public final class ChatInputManager implements Listener {

    private final ConfigManager configManager;
    private final SchedulerAdapter scheduler;
    private final Map<UUID, PendingPrompt> pendingPrompts = new ConcurrentHashMap<>();

    public ChatInputManager(ConfigManager configManager, SchedulerAdapter scheduler) {
        this.configManager = configManager;
        this.scheduler = scheduler;
    }

    public void prompt(Player player, Consumer<String> onInput, Runnable onCancel) {
        pendingPrompts.put(player.getUniqueId(), new PendingPrompt(onInput, onCancel));
        configManager.getMessages().send(player, "editor.chat-prompt",
                "{prefix}&eType the new value in chat (or type &ccancel&e to abort):");
    }

    public boolean hasPending(UUID uuid) {
        return pendingPrompts.containsKey(uuid);
    }

    public void clearAll() {
        pendingPrompts.clear();
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PendingPrompt prompt = pendingPrompts.remove(player.getUniqueId());
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        String msg = event.getMessage().trim();

        scheduler.runAtEntity(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (!player.hasPermission("roypodium.editor")) {
                configManager.getMessages().send(player, "general.no-permission",
                        "{prefix}&cYou do not have permission to use this command.");
                return;
            }
            if ("cancel".equalsIgnoreCase(msg)) {
                configManager.getMessages().send(player, "editor.chat-cancelled", "{prefix}&cInput cancelled.");
                if (prompt.onCancel() != null) {
                    prompt.onCancel().run();
                }
                return;
            }
            prompt.onInput().accept(msg);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pendingPrompts.remove(event.getPlayer().getUniqueId());
    }

    private record PendingPrompt(Consumer<String> onInput, Runnable onCancel) {
    }
}
