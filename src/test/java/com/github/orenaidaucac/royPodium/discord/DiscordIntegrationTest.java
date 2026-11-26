package com.github.orenaidaucac.royPodium.discord;

import com.github.orenaidaucac.royPodium.config.DiscordConfig;
import com.github.orenaidaucac.royPodium.podium.PodiumDefinition;
import com.github.orenaidaucac.royPodium.podium.PodiumSnapshot;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class DiscordIntegrationTest {

    @Test
    void valueFormatSpecHandlesNumbersCompactNegativeHugeAndNonNumeric() {
        ValueFormatSpec standard = ValueFormatSpec.builder()
                .grouping(true).decimals(2).prefix("$").suffix(" USD").build();
        assertEquals("$1,250.50 USD", standard.format("1250.5"));
        assertEquals("$-42.00 USD", standard.format("-42"));
        assertEquals("$999,999,999,999.00 USD", standard.format("999999999999"));

        ValueFormatSpec compact = ValueFormatSpec.builder().compact(true).decimals(1).suffix(" pts").build();
        assertEquals("1.5M pts", compact.format("1500000"));
        assertEquals("-2.3K pts", compact.format("-2340"));

        ValueFormatSpec nonNumKeep = ValueFormatSpec.builder().keepNonNumeric(true).prefix("[").suffix("]").build();
        assertEquals("[VIP]", nonNumKeep.format("VIP"));
        assertEquals("N/A", nonNumKeep.format("  "));

        ValueFormatSpec nonNumDrop = ValueFormatSpec.builder().keepNonNumeric(false).fallback("Unknown").build();
        assertEquals("Unknown", nonNumDrop.format("abc"));
    }

    @Test
    void sanitizerStripsMinecraftColorsAndBlocksMentions() {
        String raw = "&6<gradient:#ff0000:#00ff00>@everyone</gradient> &#123456*Hacker* <@123456>";
        String sanitized = DiscordTextSanitizer.sanitizeUntrusted(raw);
        assertFalse(sanitized.contains("&6"));
        assertFalse(sanitized.contains("<gradient"));
        assertFalse(sanitized.contains("@everyone"));
        assertFalse(sanitized.contains("<@123456>"));
        assertTrue(sanitized.contains("\\*Hacker\\*"));
    }

    @Test
    void validatorEnforcesHttpsDiscordHostSsrfAndMasking() throws Exception {
        assertTrue(DiscordWebhookValidator.validate("https://discord.com/api/webhooks/1234567890123/abcdefghijklmnop", false).valid());
        assertFalse(DiscordWebhookValidator.validate("http://discord.com/api/webhooks/1234567890123/abcdefghijklmnop", false).valid());
        assertFalse(DiscordWebhookValidator.validate("https://evil.example.com/api/webhooks/1234567890123/abcdefghijklmnop", false).valid());
        assertTrue(DiscordWebhookValidator.isPrivateOrReserved(InetAddress.getByName("127.0.0.1")));
        assertTrue(DiscordWebhookValidator.isPrivateOrReserved(InetAddress.getByName("10.0.0.5")));

        String masked = DiscordWebhookValidator.maskUrl("https://discord.com/api/webhooks/1234567890123/SuperSecretTokenValue123");
        assertEquals("https://discord.com/api/webhooks/123456***/***", masked);
        assertFalse(masked.contains("SuperSecretTokenValue123"));
    }

    @Test
    void embedBuilderSplitsLongLeaderboardsAndEnforcesAllowedMentions() {
        DiscordConfig cfg = DiscordConfig.builder()
                .enabled(true)
                .defaultWebhook("default")
                .webhooks(Map.of())
                .statusOnline("Delivered")
                .statusOffline("Queued")
                .statusSkipped("Skipped ({reason})")
                .rankIcons(Map.of("1", "🥇"))
                .defaultRankIcon("#{rank}")
                .embedColor(0xFFD700)
                .embedTitle("&6{podium_name}")
                .embedDescription("<aqua>Date: {date}</aqua>")
                .includeTimestamp(true)
                .thumbnailMode(DiscordConfig.ThumbnailMode.NONE)
                .fieldTitle("Leaderboard")
                .entryFormat("{rank_icon} **#{rank}** {player} - {value} ({status})")
                .skippedFormat("{rank_icon} **#{rank}** {player} - {value} (Skipped: {reason})")
                .includeSkipped(true)
                .emptyMessage("Empty")
                .footer("Footer {total_count}")
                .build();

        PodiumDefinition def = PodiumDefinition.builder()
                .id("long_top")
                .displayName("&c&lLong Top")
                .valueFormat(ValueFormatSpec.builder().grouping(true).build())
                .build();

        List<PodiumSnapshot.Entry> entries = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            entries.add(PodiumSnapshot.Entry.builder()
                    .rank(i).playerName("Player_" + i + "_@everyone").value(String.valueOf(10000 - i)).online(i % 2 == 0).commands(List.of()).build());
        }
        PodiumSnapshot snapshot = PodiumSnapshot.builder()
                .podiumId("long_top")
                .displayName("&c&lLong Top")
                .payoutId("p@1")
                .capturedAt(Instant.now())
                .entries(entries)
                .skippedEntries(List.of(PodiumSnapshot.SkippedEntry.builder()
                        .rank(61).playerName("LowPlayer").value("1").reason("Failed >= 5").build()))
                .build();

        List<String> payloads = DiscordEmbedBuilder.buildPayloads(cfg, null, def, snapshot, 30, 30, ZoneId.of("UTC"));
        assertFalse(payloads.isEmpty());
        for (String json : payloads) {
            assertTrue(json.contains("\"allowed_mentions\":{\"parse\":[]}"));
            assertFalse(json.contains("&c"));
            assertFalse(json.contains("<aqua>"));
            assertFalse(json.contains("@everyone"));
        }
    }

    @Test
    void senderHandlesSuccess404And429RetryViaLocalHttpServer() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> {
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.createContext("/notfound", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.createContext("/ratelimit", ex -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                ex.getResponseHeaders().add("Retry-After", "0.1");
                ex.sendResponseHeaders(429, -1);
            } else {
                ex.sendResponseHeaders(204, -1);
            }
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            DiscordWebhookSender sender = new DiscordWebhookSender(Logger.getLogger("Test"));

            var okRes = sender.sendBlocking("http://127.0.0.1:" + port + "/ok", "masked", "{}", 5, 1, 10);
            assertTrue(okRes.success());

            var nfRes = sender.sendBlocking("http://127.0.0.1:" + port + "/notfound", "https://discord.com/api/webhooks/123456***/***", "{}", 5, 1, 10);
            assertFalse(nfRes.success());
            assertEquals(404, nfRes.httpCode());
            assertTrue(nfRes.errorMessage().contains("123456***/***"));

            var rlRes = sender.sendBlocking("http://127.0.0.1:" + port + "/ratelimit", "masked", "{}", 5, 2, 10);
            assertTrue(rlRes.success());
            assertEquals(2, calls.get());

            sender.shutdown(2);
        } finally {
            server.stop(0);
        }
    }
}
