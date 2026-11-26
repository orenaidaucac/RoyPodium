package com.github.orenaidaucac.royPodium.discord;

import com.github.orenaidaucac.royPodium.util.ColorUtil;

/**
 * Strips Minecraft color/formatting codes and neutralizes Discord mentions and Markdown injection.
 */
public final class DiscordTextSanitizer {

    private DiscordTextSanitizer() {
    }

    /**
     * Strips all Minecraft Legacy, Hex, and MiniMessage tags while preserving Discord {@code <t:...>} timestamps.
     */
    public static String stripMinecraft(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        // O(1) fast-path when no Minecraft formatting characters are present
        if (input.indexOf('&') < 0 && input.indexOf('§') < 0 && input.indexOf('<') < 0) {
            return input;
        }
        // Protect Discord dynamic timestamps (<t:123456:R>) from MiniMessage tag parsing
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<t:(-?\\d+)(?::([tTdDfFR]))?>").matcher(input);
        java.util.List<String> preserved = new java.util.ArrayList<>();
        StringBuilder sb = new StringBuilder(input.length());
        while (m.find()) {
            String token = "ROYTS" + preserved.size() + "END";
            preserved.add(m.group(0));
            m.appendReplacement(sb, token);
        }
        m.appendTail(sb);

        String stripped = ColorUtil.strip(sb.toString());
        for (int i = 0; i < preserved.size(); i++) {
            stripped = stripped.replace("ROYTS" + i + "END", preserved.get(i));
        }
        return stripped;
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
