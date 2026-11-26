package com.github.orenaidaucac.royPodium.metrics;

import com.github.orenaidaucac.royPodium.config.MainConfig;
import com.github.orenaidaucac.royPodium.podium.ConditionSpec;
import com.github.orenaidaucac.royPodium.podium.PodiumDefinition;
import com.github.orenaidaucac.royPodium.scheduler.MissedPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetricsManagerTest {

    @Test
    void pluginIdMatchesConfigured35686() {
        assertEquals(35686, MetricsManager.BSTATS_PLUGIN_ID);
    }

    @Test
    void bucketCountProducesExpectedLowCardinalityRanges() {
        assertEquals("0", MetricsManager.bucketCount(0));
        assertEquals("1-3", MetricsManager.bucketCount(1));
        assertEquals("1-3", MetricsManager.bucketCount(3));
        assertEquals("4-10", MetricsManager.bucketCount(4));
        assertEquals("4-10", MetricsManager.bucketCount(10));
        assertEquals("11+", MetricsManager.bucketCount(11));
    }

    @Test
    void snapshotChartValuesAreLowCardinalityAndAnonymous() {
        PodiumDefinition withCond = PodiumDefinition.builder()
                .id("secret_podium_name")
                .conditions(List.of(ConditionSpec.parse(">= 5").orElseThrow()))
                .build();
        MainConfig mainCfg = MainConfig.builder()
                .missedSchedulePolicy(MissedPolicy.RUN_LATEST)
                .commandAliases(List.of("toprewards"))
                .build();

        Map<String, String> charts = MetricsManager.snapshotChartValues(
                2, 4, List.of(withCond), mainCfg, true, false
        );

        assertEquals("1-3", charts.get("podium_count_bucket"));
        assertEquals("4-10", charts.get("schedule_count_bucket"));
        assertEquals("Yes", charts.get("uses_conditions"));
        assertEquals("Yes", charts.get("uses_custom_aliases"));
        assertEquals("RUN_LATEST", charts.get("missed_schedule_policy"));
        assertEquals("Yes", charts.get("placeholderapi_present"));
        assertEquals("Bukkit/Paper", charts.get("server_scheduler_mode"));

        // Ensure no identifying names or aliases leak into chart values
        assertFalse(charts.values().stream().anyMatch(v -> v.contains("secret_podium_name") || v.contains("toprewards")));
    }
}
