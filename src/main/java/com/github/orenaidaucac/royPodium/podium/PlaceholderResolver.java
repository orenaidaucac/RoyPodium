package com.github.orenaidaucac.royPodium.podium;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;

import java.util.Set;

/**
 * Safe PlaceholderAPI integration wrapper. Never crashes if PlaceholderAPI is absent.
 */
public final class PlaceholderResolver {

    public boolean isPlaceholderApiAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    /**
     * Resolves a rank-templated placeholder on the Global Region / Main Thread.
     */
    public String resolveForRank(String template, int rank) {
        if (template == null || template.isBlank() || !isPlaceholderApiAvailable()) {
            return "";
        }
        String prepared = template
                .replace("{rank}", String.valueOf(rank))
                .replace("%rank%", String.valueOf(rank));
        String resolved = PlaceholderAPI.setPlaceholders(null, prepared);
        if (resolved == null || resolved.equals(prepared)) {
            return "";
        }
        return resolved.trim();
    }

    /**
     * Checks whether a resolved placeholder value is invalid/empty.
     */
    public boolean isInvalidValue(String value, Set<String> invalidValues) {
        if (value == null) {
            return true;
        }
        String normalized = value.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return true;
        }
        return invalidValues != null && invalidValues.contains(normalized);
    }
}
