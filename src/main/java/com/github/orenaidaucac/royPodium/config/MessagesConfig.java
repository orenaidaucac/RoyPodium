package com.github.orenaidaucac.royPodium.config;

import com.github.orenaidaucac.royPodium.util.ColorUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;
import java.util.Map;

/**
 * Provides colorized messages and placeholder replacement from {@code messages.yml}.
 */
public final class MessagesConfig {

    private final YamlConfiguration yaml;
    private final String prefix;

    public MessagesConfig(YamlConfiguration yaml) {
        this.yaml = yaml;
        this.prefix = yaml.getString("prefix", "&8[&6RoyPodium&8] &r");
    }

    public String getRaw(String path, String def) {
        return yaml.getString(path, def);
    }

    public String format(String path, String def, Map<String, String> placeholders) {
        return format(path, def, placeholders, Map.of());
    }

    /**
     * Formats a message with {@code trustedPlaceholders} (may contain color tags)
     * and {@code untrustedPlaceholders} (player names, PAPI values) safely escaped.
     */
    public String format(String path, String def, Map<String, String> trustedPlaceholders, Map<String, String> untrustedPlaceholders) {
        String msg = yaml.getString(path, def).replace("{prefix}", prefix);
        if (trustedPlaceholders != null) {
            for (Map.Entry<String, String> entry : trustedPlaceholders.entrySet()) {
                msg = msg.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
            }
        }
        return ColorUtil.colorize(msg, untrustedPlaceholders);
    }

    public String format(String path, String def) {
        return format(path, def, Map.of());
    }

    public List<String> getStringList(String path) {
        return yaml.getStringList(path);
    }

    public void send(CommandSender sender, String path, String def, Map<String, String> placeholders) {
        String formatted = format(path, def, placeholders);
        if (!formatted.isEmpty()) {
            sender.sendMessage(formatted);
        }
    }

    public void send(CommandSender sender, String path, String def,
                     Map<String, String> trustedPlaceholders, Map<String, String> untrustedPlaceholders) {
        String formatted = format(path, def, trustedPlaceholders, untrustedPlaceholders);
        if (!formatted.isEmpty()) {
            sender.sendMessage(formatted);
        }
    }

    public void send(CommandSender sender, String path, String def) {
        send(sender, path, def, Map.of());
    }
}
