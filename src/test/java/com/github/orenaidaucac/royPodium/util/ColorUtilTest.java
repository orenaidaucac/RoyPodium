package com.github.orenaidaucac.royPodium.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ColorUtilTest {

    @Test
    void mixesMiniMessageHexAndLegacyInSameLine() {
        String input = "&6[RoyPodium] &#ff5500Top <aqua><bold>1</bold></aqua> §aWon!";
        String rendered = ColorUtil.colorize(input);

        assertFalse(rendered.contains("<aqua>"), "Raw MiniMessage tag leaked: " + rendered);
        assertFalse(rendered.contains("&#ff5500"), "Raw hex tag leaked: " + rendered);
        assertFalse(rendered.contains("&6"), "Raw legacy code leaked: " + rendered);
        assertTrue(rendered.contains("§6"), "Expected legacy gold section code");
        assertTrue(rendered.contains("§x§f§f§5§5§0§0"), "Expected Bungee hex sequence");
        assertTrue(rendered.contains("Won!"));
    }

    @Test
    void escapesUntrustedPlayerAndPlaceholderInputsAgainstInjection() {
        String template = "&aWinner: &f{player} &7({value})";
        String maliciousPlayer = "<red><click:run_command:/op>Hacker</click>&c";
        String maliciousValue = "<rainbow>999</rainbow>§4";

        String rendered = ColorUtil.colorize(template, Map.of(
                "player", maliciousPlayer,
                "value", maliciousValue
        ));

        // Literal text is preserved without executing MiniMessage tags or &c color injection
        assertTrue(rendered.contains("<red>"));
        assertTrue(rendered.contains("&c"));
        assertTrue(rendered.contains("<rainbow>999</rainbow>"));
        assertFalse(rendered.contains("§4"));
    }

    @Test
    void stripsAllFormattingCleanly() {
        String stripped = ColorUtil.strip("<gradient:#ff0000:#00ff00>Hello</gradient> &lWorld &#123456!");
        assertEquals("Hello World !", stripped);
    }
}
