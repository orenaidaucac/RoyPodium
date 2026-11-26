package com.github.orenaidaucac.royPodium.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

/**
 * Strict anti-exploit listener preventing item duplication, drag, shift-click, double-click,
 * number-key swaps, and unauthorized session access in RoyPodium GUIs.
 */
public final class GuiSecurityListener implements Listener {

    private final GuiManager guiManager;

    public GuiSecurityListener(GuiManager guiManager) {
        this.guiManager = guiManager;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!guiManager.isTrackedInventory(top)) {
            return;
        }
        // Always cancel any interaction when a RoyPodium GUI is open
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!guiManager.isValidSession(player)) {
            player.closeInventory();
            return;
        }
        ClickType click = event.getClick();
        if (click == ClickType.DOUBLE_CLICK
                || click == ClickType.NUMBER_KEY
                || click == ClickType.SWAP_OFFHAND
                || click == ClickType.DROP
                || click == ClickType.CONTROL_DROP
                || click == ClickType.CREATIVE) {
            return;
        }
        // Block bottom inventory clicks to prevent item manipulation
        if (event.getClickedInventory() != null && !event.getClickedInventory().equals(top)) {
            return;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (guiManager.isTrackedInventory(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player && guiManager.isTrackedInventory(event.getInventory())) {
            guiManager.handleClose(player, event.getInventory());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        guiManager.invalidateSession(event.getPlayer().getUniqueId());
    }
}
