package com.github.orenaidaucac.royPodium.config;

import com.github.orenaidaucac.royPodium.util.ColorUtil;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;
import java.util.Map;

/**
 * Provides configurable GUI titles, slots, materials, lore, sounds, and labels from {@code gui.yml}.
 */
public final class GuiConfig {

    private final YamlConfiguration yaml;

    public GuiConfig(YamlConfiguration yaml) {
        this.yaml = yaml;
    }

    public String getTitle(String menuKey, String def, Map<String, String> placeholders) {
        String raw = yaml.getString("menus." + menuKey + ".title", def);
        return ColorUtil.colorize(apply(raw, placeholders));
    }

    public int getRows(String menuKey, int def) {
        int rows = yaml.getInt("menus." + menuKey + ".rows", def);
        return Math.max(1, Math.min(6, rows));
    }

    public int getInt(String path, int def) {
        return yaml.getInt(path, def);
    }

    public boolean getBoolean(String path, boolean def) {
        return yaml.getBoolean(path, def);
    }

    public String getString(String path, String def) {
        return ColorUtil.colorize(yaml.getString(path, def));
    }

    public String getRawString(String path, String def) {
        return yaml.getString(path, def);
    }

    public List<String> getStringList(String path) {
        return yaml.getStringList(path);
    }

    public Material getMaterial(String path, Material fallback) {
        String raw = yaml.getString(path);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Material mat = Material.matchMaterial(raw.trim());
        return mat != null ? mat : fallback;
    }

    public Sound getSound(String key, Sound fallback) {
        String raw = yaml.getString("sounds." + key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Sound.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    public ConfigurationSection getSection(String path) {
        return yaml.getConfigurationSection(path);
    }

    public static String apply(String input, Map<String, String> placeholders) {
        if (input == null) {
            return "";
        }
        String result = input;
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                result = result.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
            }
        }
        return result;
    }
}
