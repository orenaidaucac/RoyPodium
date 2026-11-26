package com.github.orenaidaucac.royPodium.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Unified color formatter supporting MiniMessage, Hex ({@code &#RRGGBB}, {@code <#RRGGBB>}),
 * and Legacy ({@code &}, {@code §}) mixed in the same line, with anti-injection support for untrusted inputs.
 */
public final class ColorUtil {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_OUT = LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private static final Pattern BUNGEE_HEX_PATTERN = Pattern.compile("&x((?:&[A-Fa-f0-9]){6})");
    private static final Pattern HASH_HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Pattern LEGACY_CODE_PATTERN = Pattern.compile("&([0-9a-fk-orA-FK-OR])");

    private ColorUtil() {
    }

    /**
     * Parses a string that may mix MiniMessage, Hex, and Legacy codes into a Bukkit-compatible section-colored string.
     */
    public static String colorize(String input) {
        return colorize(input, Map.of());
    }

    /**
     * Parses a template with {@code untrustedPlaceholders} (such as player names or PlaceholderAPI values)
     * safely escaped so they cannot inject MiniMessage tags or legacy color codes.
     */
    public static String colorize(String input, Map<String, String> untrustedPlaceholders) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String normalized = normalizeLegacyToMiniMessage(input);

        List<TagResolver> resolvers = new ArrayList<>();
        if (untrustedPlaceholders != null && !untrustedPlaceholders.isEmpty()) {
            int idx = 0;
            for (Map.Entry<String, String> entry : untrustedPlaceholders.entrySet()) {
                String token = "{" + entry.getKey() + "}";
                if (normalized.contains(token)) {
                    String tagKey = "u_" + (idx++);
                    normalized = normalized.replace(token, "<" + tagKey + ">");
                    String safeVal = entry.getValue() == null ? "" : entry.getValue().replace("§", "");
                    resolvers.add(Placeholder.unparsed(tagKey, safeVal));
                }
            }
        }

        try {
            Component component = resolvers.isEmpty()
                    ? MINI_MESSAGE.deserialize(normalized)
                    : MINI_MESSAGE.deserialize(normalized, TagResolver.resolver(resolvers));
            return LEGACY_OUT.serialize(component);
        } catch (Exception ex) {
            // Fallback to legacy translation if an admin writes malformed MiniMessage syntax
            return net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', input.replace('§', '&'));
        }
    }

    /**
     * Colorizes every line in a list.
     */
    public static List<String> colorize(List<String> lines) {
        if (lines == null) {
            return List.of();
        }
        return lines.stream().map(ColorUtil::colorize).collect(Collectors.toList());
    }

    /**
     * Escapes MiniMessage tags and legacy codes in untrusted text.
     */
    public static String escapeUntrusted(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return MINI_MESSAGE.escapeTags(value.replace('§', '&'));
    }

    /**
     * Strips all MiniMessage, Hex, and Legacy formatting from a string.
     */
    public static String strip(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String colored = colorize(input);
        return net.md_5.bungee.api.ChatColor.stripColor(colored);
    }

    static String normalizeLegacyToMiniMessage(String input) {
        String s = input.replace('§', '&');

        // Convert &x&R&R&G&G&B&B -> <#RRGGBB>
        Matcher bungeeMatcher = BUNGEE_HEX_PATTERN.matcher(s);
        StringBuilder bungeeSb = new StringBuilder(s.length());
        while (bungeeMatcher.find()) {
            String hex = bungeeMatcher.group(1).replace("&", "");
            bungeeMatcher.appendReplacement(bungeeSb, Matcher.quoteReplacement("<#" + hex + ">"));
        }
        bungeeMatcher.appendTail(bungeeSb);
        s = bungeeSb.toString();

        // Convert &#RRGGBB -> <#RRGGBB>
        Matcher hexMatcher = HASH_HEX_PATTERN.matcher(s);
        StringBuilder hexSb = new StringBuilder(s.length());
        while (hexMatcher.find()) {
            hexMatcher.appendReplacement(hexSb, Matcher.quoteReplacement("<#" + hexMatcher.group(1) + ">"));
        }
        hexMatcher.appendTail(hexSb);
        s = hexSb.toString();

        // Convert &0-9a-fk-or -> MiniMessage tags
        Matcher legacyMatcher = LEGACY_CODE_PATTERN.matcher(s);
        StringBuilder legacySb = new StringBuilder(s.length() + 16);
        while (legacyMatcher.find()) {
            char code = Character.toLowerCase(legacyMatcher.group(1).charAt(0));
            String tag = legacyCharToTag(code);
            legacyMatcher.appendReplacement(legacySb, Matcher.quoteReplacement(tag));
        }
        legacyMatcher.appendTail(legacySb);
        return legacySb.toString();
    }

    private static String legacyCharToTag(char code) {
        return switch (code) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> "";
        };
    }
}
