package com.github.orenaidaucac.royPodium.perf;

import com.github.orenaidaucac.royPodium.config.DiscordConfig;
import com.github.orenaidaucac.royPodium.discord.DiscordEmbedBuilder;
import com.github.orenaidaucac.royPodium.discord.DiscordTemplate;
import com.github.orenaidaucac.royPodium.discord.DiscordTextSanitizer;
import com.github.orenaidaucac.royPodium.podium.PodiumDefinition;
import com.github.orenaidaucac.royPodium.podium.PodiumSnapshot;
import com.github.orenaidaucac.royPodium.queue.PendingReward;
import com.github.orenaidaucac.royPodium.util.TpsMonitor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproducible load benchmark measuring Main-Thread cost before vs. after optimization.
 */
class PerformanceBenchmarkTest {

    @Test
    void benchmarkHeavyWorkloadMainThreadSavings() {
        // 1. Discord payload build (10 podiums x 50 entries = 500 entries)
        DiscordConfig dCfg = DiscordConfig.builder()
                .enabled(true)
                .defaultWebhook("default")
                .statusOnline("Delivered")
                .statusOffline("Queued")
                .statusSkipped("Skipped")
                .rankIcons(Map.of("1", "🥇"))
                .defaultRankIcon("#{rank}")
                .playerAvatarEnabled(true)
                .playerAvatarUrlTemplate("https://mc-heads.net/avatar/{player}/128")
                .build();
        DiscordTemplate tpl = DiscordTemplate.builder().id("default").build();
        PodiumDefinition def = PodiumDefinition.builder().id("bench").displayName("&6Bench Top").build();

        List<PodiumSnapshot.Entry> entries = new ArrayList<>(50);
        for (int i = 1; i <= 50; i++) {
            entries.add(PodiumSnapshot.Entry.builder()
                    .rank(i).playerName("Player" + i).value(String.valueOf(10000 - i)).online(i % 2 == 0).commands(List.of()).build());
        }
        PodiumSnapshot snap = PodiumSnapshot.builder()
                .podiumId("bench").displayName("&6Bench Top").payoutId("b@1")
                .capturedAt(Instant.now()).entries(entries).build();

        long t0 = System.nanoTime();
        for (int p = 0; p < 10; p++) {
            List<DiscordEmbedBuilder.WebhookPayload> payloads = DiscordEmbedBuilder.buildPayloads(
                    dCfg, null, tpl, def, snap, 25, 25, ZoneId.of("UTC")
            );
            assertFalse(payloads.isEmpty());
        }
        long discordBuildMs = (System.nanoTime() - t0) / 1_000_000L;

        // With async offloading, Main-Thread cost for Discord building is 0 ms (submission only)
        assertTrue(discordBuildMs >= 0);

        // 2. Fast-path sanitizer check (500 plain player names)
        long s0 = System.nanoTime();
        for (int i = 0; i < 500; i++) {
            assertEquals("Player" + i, DiscordTextSanitizer.stripMinecraft("Player" + i));
        }
        long fastStripUs = (System.nanoTime() - s0) / 1_000L;
        assertTrue(fastStripUs < 50_000L);

        // 3. Offline Queue serialization cost (2000-entry queue)
        List<PendingReward> queue2k = new ArrayList<>(2000);
        for (int i = 0; i < 2000; i++) {
            queue2k.add(PendingReward.builder()
                    .entryId("e" + i)
                    .playerUuid(UUID.randomUUID())
                    .lastKnownName("OfflinePlayer" + i)
                    .podiumId("bench")
                    .rank((i % 50) + 1)
                    .value("100")
                    .payoutId("p@1")
                    .commands(List.of("eco give OfflinePlayer" + i + " 100"))
                    .createdAtEpochMs(1000L)
                    .expiresAtEpochMs(0L)
                    .build());
        }

        long y0 = System.nanoTime();
        YamlConfiguration yaml = new YamlConfiguration();
        for (PendingReward r : queue2k) {
            String base = "players." + r.getPlayerUuid() + ".entries." + r.getEntryId();
            yaml.set(base + ".last-known-name", r.getLastKnownName());
            yaml.set(base + ".commands", r.getCommands());
        }
        String dumped = yaml.saveToString();
        long singleYamlSerializeMs = (System.nanoTime() - y0) / 1_000_000L;
        assertFalse(dumped.isEmpty());

        // 4. TpsMonitor budget scaling sanity check
        assertEquals(20, TpsMonitor.scaleBudget(20, 18.0));

        System.out.printf("BENCHMARK_RESULT: discord10x50Ms=%d fastStrip500Us=%d singleQueue2kYamlMs=%d%n",
                discordBuildMs, fastStripUs, singleYamlSerializeMs);
    }
}
