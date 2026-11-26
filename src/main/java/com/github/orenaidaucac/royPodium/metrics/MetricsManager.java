package com.github.orenaidaucac.royPodium.metrics;

import com.github.orenaidaucac.royPodium.config.ConfigManager;
import com.github.orenaidaucac.royPodium.config.MainConfig;
import com.github.orenaidaucac.royPodium.podium.PodiumDefinition;
import com.github.orenaidaucac.royPodium.scheduler.SchedulerAdapter;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages anonymous bStats metrics collection (Plugin ID 35686) with Folia-safe,
 * low-cardinality custom charts and clean lifecycle shutdown.
 */
public final class MetricsManager {

    public static final int BSTATS_PLUGIN_ID = 35686;

    private final JavaPlugin plugin;
    private final ConfigManager configManager;
    private final AtomicBoolean papiAvailable = new AtomicBoolean(false);
    private Metrics metrics;

    public MetricsManager(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
    }

    public void setPapiAvailable(boolean available) {
        this.papiAvailable.set(available);
    }

    /**
     * Initializes bStats once. Respects global bStats opt-out and handles any errors gracefully.
     */
    public void start() {
        if (metrics != null) {
            return;
        }
        try {
            metrics = new Metrics(plugin, BSTATS_PLUGIN_ID);
            metrics.addCustomChart(new SimplePie("podium_count_bucket",
                    () -> bucketCount(configManager.getPodiums().size())));
            metrics.addCustomChart(new SimplePie("schedule_count_bucket",
                    () -> bucketCount(configManager.getSchedules().size())));
            metrics.addCustomChart(new SimplePie("uses_conditions",
                    () -> hasAnyConditions(configManager.getPodiums().values()) ? "Yes" : "No"));
            metrics.addCustomChart(new SimplePie("uses_custom_aliases",
                    () -> hasCustomAliases(configManager.getMainConfig()) ? "Yes" : "No"));
            metrics.addCustomChart(new SimplePie("missed_schedule_policy",
                    () -> {
                        MainConfig cfg = configManager.getMainConfig();
                        return cfg != null && cfg.getMissedSchedulePolicy() != null
                                ? cfg.getMissedSchedulePolicy().name()
                                : "RUN_LATEST";
                    }));
            metrics.addCustomChart(new SimplePie("placeholderapi_present",
                    () -> papiAvailable.get() ? "Yes" : "No"));
            metrics.addCustomChart(new SimplePie("server_scheduler_mode",
                    () -> SchedulerAdapter.isFolia() ? "Folia" : "Bukkit/Paper"));
        } catch (Throwable ex) {
            plugin.getLogger().warning("bStats metrics could not be initialized (" + ex.getMessage()
                    + "). RoyPodium will continue running normally without metrics.");
            metrics = null;
        }
    }

    /**
     * Shuts down bStats background tasks cleanly on plugin disable or PlugmanX unload.
     */
    public void shutdown() {
        if (metrics != null) {
            try {
                metrics.shutdown();
            } catch (Throwable ignored) {
            }
            metrics = null;
        }
    }

    public static String bucketCount(int count) {
        if (count <= 0) {
            return "0";
        }
        if (count <= 3) {
            return "1-3";
        }
        if (count <= 10) {
            return "4-10";
        }
        return "11+";
    }

    public static boolean hasAnyConditions(Collection<PodiumDefinition> podiums) {
        if (podiums == null) {
            return false;
        }
        for (PodiumDefinition def : podiums) {
            if (def.getConditions() != null && !def.getConditions().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasCustomAliases(MainConfig mainConfig) {
        return mainConfig != null
                && mainConfig.getCommandAliases() != null
                && !mainConfig.getCommandAliases().isEmpty();
    }

    /**
     * Produces a snapshot map of all 7 chart values (used for verification and testing).
     */
    public static Map<String, String> snapshotChartValues(int podiumCount,
                                                            int scheduleCount,
                                                            Collection<PodiumDefinition> podiums,
                                                            MainConfig mainConfig,
                                                            boolean papiPresent,
                                                            boolean folia) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("podium_count_bucket", bucketCount(podiumCount));
        values.put("schedule_count_bucket", bucketCount(scheduleCount));
        values.put("uses_conditions", hasAnyConditions(podiums) ? "Yes" : "No");
        values.put("uses_custom_aliases", hasCustomAliases(mainConfig) ? "Yes" : "No");
        values.put("missed_schedule_policy", mainConfig != null && mainConfig.getMissedSchedulePolicy() != null
                ? mainConfig.getMissedSchedulePolicy().name()
                : "RUN_LATEST");
        values.put("placeholderapi_present", papiPresent ? "Yes" : "No");
        values.put("server_scheduler_mode", folia ? "Folia" : "Bukkit/Paper");
        return values;
    }
}
