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

    private DiscordConfig baseConfig() {
        return DiscordConfig.builder()
                .enabled(true)
                .defaultWebhook("default")
                .defaultTemplate("default")
                .fallbackTemplate("minimal")
                .webhooks(Map.of())
                .templates(Map.of())
                .statusOnline("Delivered")
                .statusOffline("Queued")
                .statusSkipped("Skipped ({reason})")
                .rankIcons(Map.of("1", "🥇"))
                .defaultRankIcon("#{rank}")
                .playerAvatarEnabled(true)
                .playerAvatarUrlTemplate("https://mc-heads.net/avatar/{player}/128")
                .build();
    }

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
        DiscordConfig cfg = baseConfig();
        DiscordTemplate tpl = DiscordTemplate.builder()
                .id("default")
                .layoutMode(DiscordTemplate.LayoutMode.LIST_FIELD)
                .overflowPolicy(DiscordTemplate.OverflowPolicy.SPLIT_MESSAGE)
                .title("&6{podium_name}")
                .description("<aqua>Date: {date} {timestamp_relative}</aqua>")
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

        List<DiscordEmbedBuilder.WebhookPayload> payloads = DiscordEmbedBuilder.buildPayloads(
                cfg, null, tpl, def, snapshot, 30, 30, ZoneId.of("UTC")
        );
        assertFalse(payloads.isEmpty());
        for (var p : payloads) {
            String json = p.json();
            assertTrue(json.contains("\"allowed_mentions\":{\"parse\":[]}"));
            assertFalse(json.contains("&c"));
            assertFalse(json.contains("<aqua>"));
            assertFalse(json.contains("@everyone"));
        }
        assertTrue(payloads.get(0).json().contains("<t:"));
    }

    @Test
    void supportsFullCapabilitiesThreadsForumButtonsFlagsAndOmitsInvalidUrls() {
        DiscordConfig cfg = baseConfig();
        DiscordTemplate tpl = DiscordTemplate.builder()
                .id("full")
                .layoutMode(DiscordTemplate.LayoutMode.PER_ENTRY_FIELD)
                .inlineEntryFields(true)
                .content("Payout alert!")
                .tts(true)
                .suppressNotifications(true)
                .mentionRoles(List.of("123456789012345678"))
                .authorName("RoyMC")
                .authorUrl("not-a-valid-url") // should be omitted safely
                .titleUrl("https://example.com/leaderboards")
                .imageUrl("https://example.com/banner.png")
                .footerIconUrl("invalid-url") // should be omitted safely
                .linkButtons(List.of(
                        DiscordTemplate.LinkButton.builder().label("View Web").url("https://example.com").emoji("🏆").build(),
                        DiscordTemplate.LinkButton.builder().label("Bad").url("ftp://bad").build() // omitted
                ))
                .build();

        PodiumDefinition def = PodiumDefinition.builder()
                .id("kills")
                .displayName("Top Kills")
                .discordThreadName("Weekly Kills - {date}")
                .discordAppliedTags(List.of("987654321098765432"))
                .build();

        PodiumSnapshot snap = PodiumSnapshot.builder()
                .podiumId("kills")
                .displayName("Top Kills")
                .payoutId("p@2")
                .capturedAt(Instant.now())
                .entries(List.of(
                        PodiumSnapshot.Entry.builder().rank(1).playerName("Steve").value("50").online(true).commands(List.of()).build()
                ))
                .build();

        List<DiscordEmbedBuilder.WebhookPayload> payloads = DiscordEmbedBuilder.buildPayloads(
                cfg, null, tpl, def, snap, 1, 0, ZoneId.of("UTC")
        );
        assertEquals(1, payloads.size());
        var p = payloads.get(0);
        assertTrue(p.hasComponents());
        assertTrue(p.json().contains("\"tts\":true"));
        assertTrue(p.json().contains("\"flags\":4096"));
        assertTrue(p.json().contains("\"thread_name\":\"Weekly Kills"));
        assertTrue(p.json().contains("\"applied_tags\":[\"987654321098765432\"]"));
        assertTrue(p.json().contains("\"roles\":[\"123456789012345678\"]"));
        assertTrue(p.json().contains("\"inline\":true"));
        assertTrue(p.json().contains("\"style\":5"));
        assertFalse(p.json().contains("not-a-valid-url"));
        assertFalse(p.json().contains("ftp://bad"));

        String targetUrl = DiscordWebhookSender.buildTargetUrl("https://discord.com/api/webhooks/1/tok", "555666", p.hasComponents());
        assertTrue(targetUrl.contains("thread_id=555666"));
        assertTrue(targetUrl.contains("with_components=true"));
    }

    @Test
    void supportsPerEntryEmbedAndTemplateLimitValidation() {
        DiscordConfig cfg = baseConfig();
        DiscordTemplate showcase = DiscordTemplate.builder()
                .id("showcase")
                .layoutMode(DiscordTemplate.LayoutMode.PER_ENTRY_EMBED)
                .rankColors(Map.of("1", 0xFFD700))
                .build();

        PodiumDefinition def = PodiumDefinition.builder().id("bal").displayName("Top Balance").build();
        PodiumSnapshot snap = PodiumSnapshot.builder()
                .podiumId("bal").displayName("Top Balance").payoutId("p@3").capturedAt(Instant.now())
                .entries(List.of(
                        PodiumSnapshot.Entry.builder().rank(1).playerName("Alice").value("500").online(true).commands(List.of()).build(),
                        PodiumSnapshot.Entry.builder().rank(2).playerName("Bob").value("200").online(false).commands(List.of()).build()
                )).build();

        var payloads = DiscordEmbedBuilder.buildPayloads(cfg, null, showcase, def, snap, 1, 1, ZoneId.of("UTC"));
        assertEquals(1, payloads.size());
        assertTrue(payloads.get(0).json().contains("Rank #1"));
        assertTrue(payloads.get(0).json().contains("Rank #2"));

        DiscordTemplate oversized = DiscordTemplate.builder()
                .id("bad")
                .title("x".repeat(300))
                .build();
        assertFalse(DiscordEmbedBuilder.validateTemplate(oversized).isEmpty());
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
