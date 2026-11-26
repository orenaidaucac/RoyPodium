package com.github.orenaidaucac.royPodium.discord;

import com.github.orenaidaucac.royPodium.util.ColorUtil;

/**
 * Strips Minecraft color/formatting codes and neutralizes Discord mentions and Markdown injection.
 */
public final class DiscordTextSanitizer {

    private DiscordTextSanitizer() {
    }

    /**
     * Strips all Minecraft Legacy, Hex, and MiniMessage tags.
     */
    public static String stripMinecraft(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return ColorUtil.strip(input);
    }

    /**
     * Sanitizes untrusted player names or PlaceholderAPI values for safe Discord Embed rendering.
     */
    public static String sanitizeUntrusted(String input) {
        String stripped = stripMinecraft(input);
        if (stripped.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(stripped.length() + 16);
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            switch (c) {
                case '@' -> sb.append("@\u200B");
                case '<' -> sb.append("<\u200B");
                case '\\', '*', '_', '~', '`', '|', '>' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Sanitizes a rendered template string: strips Minecraft colors and neutralizes mass mentions
     * while preserving intentional Discord Markdown in the admin's template.
     */
    public static String sanitizeTemplateOutput(String input) {
        String stripped = stripMinecraft(input);
        return stripped
                .replace("@everyone", "@\u200Beveryone")
                .replace("@here", "@\u200Bhere")
                .replace("<@", "<\u200B@");
    }
}
