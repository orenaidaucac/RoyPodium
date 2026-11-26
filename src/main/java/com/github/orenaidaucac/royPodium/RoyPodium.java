package com.github.orenaidaucac.royPodium;

import com.github.orenaidaucac.royPodium.command.CommandAliasManager;
import com.github.orenaidaucac.royPodium.command.PodiumCommand;
import com.github.orenaidaucac.royPodium.config.ConfigManager;
import com.github.orenaidaucac.royPodium.discord.DiscordManager;
import com.github.orenaidaucac.royPodium.gui.ChatInputManager;
import com.github.orenaidaucac.royPodium.gui.GuiManager;
import com.github.orenaidaucac.royPodium.gui.GuiSecurityListener;
import com.github.orenaidaucac.royPodium.metrics.MetricsManager;
import com.github.orenaidaucac.royPodium.podium.PlaceholderResolver;
import com.github.orenaidaucac.royPodium.podium.PodiumManager;
import com.github.orenaidaucac.royPodium.queue.CommandDispatchQueue;
import com.github.orenaidaucac.royPodium.queue.QueueManager;
import com.github.orenaidaucac.royPodium.scheduler.ScheduleManager;
import com.github.orenaidaucac.royPodium.scheduler.SchedulerAdapter;
import lombok.Getter;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main plugin entry point for RoyPodium (v2026.1).
 */
public final class RoyPodium extends JavaPlugin {

    @Getter private SchedulerAdapter schedulerAdapter;
    @Getter private ConfigManager configManager;
    @Getter private PlaceholderResolver placeholderResolver;
    @Getter private CommandDispatchQueue dispatchQueue;
    @Getter private QueueManager queueManager;
    @Getter private PodiumManager podiumManager;
    @Getter private ScheduleManager scheduleManager;
    @Getter private ChatInputManager chatInputManager;
    @Getter private GuiManager guiManager;
    @Getter private CommandAliasManager aliasManager;
    @Getter private MetricsManager metricsManager;
    @Getter private DiscordManager discordManager;
    private PodiumCommand podiumCommand;

    @Override
    public void onEnable() {
        this.schedulerAdapter = new SchedulerAdapter(this);
        this.configManager = new ConfigManager(this);
        this.configManager.loadAll();

        this.placeholderResolver = new PlaceholderResolver();
        if (!placeholderResolver.isPlaceholderApiAvailable()) {
            getLogger().severe("==================================================================");
            getLogger().severe("PlaceholderAPI was NOT detected on this server!");
            getLogger().severe("RoyPodium requires PlaceholderAPI to resolve leaderboard rankings.");
            getLogger().severe("Scheduled payouts are paused until PlaceholderAPI is installed.");
            getLogger().severe("==================================================================");
        }

        this.dispatchQueue = new CommandDispatchQueue(this, schedulerAdapter);
        this.queueManager = new QueueManager(this, configManager, schedulerAdapter, dispatchQueue);
        this.queueManager.load();

        this.podiumManager = new PodiumManager(configManager, placeholderResolver, dispatchQueue, queueManager);
        this.discordManager = new DiscordManager(configManager, schedulerAdapter, getLogger());
        this.podiumManager.setDiscordManager(discordManager);
        this.scheduleManager = new ScheduleManager(this, configManager, schedulerAdapter, podiumManager);
        this.chatInputManager = new ChatInputManager(configManager, schedulerAdapter);
        this.guiManager = new GuiManager(configManager, schedulerAdapter, podiumManager, queueManager, chatInputManager);
        this.guiManager.setDiscordManager(discordManager);
        this.aliasManager = new CommandAliasManager(this);
        this.metricsManager = new MetricsManager(this, configManager);
        this.metricsManager.setPapiAvailable(placeholderResolver.isPlaceholderApiAvailable());

        registerListenersAndCommands();
        startServices();
        this.metricsManager.start();

        getLogger().info("RoyPodium v" + getDescription().getVersion() + " enabled ("
                + (SchedulerAdapter.isFolia() ? "Folia" : "Bukkit/Paper") + " mode, "
                + configManager.getPodiums().size() + " podiums, "
                + configManager.getSchedules().size() + " schedules).");
    }

    @Override
    public void onDisable() {
        stopServices(false);
        if (discordManager != null) {
            discordManager.shutdown();
        }
        if (metricsManager != null) {
            metricsManager.shutdown();
        }
        if (aliasManager != null) {
            aliasManager.unregisterAll();
        }
        HandlerList.unregisterAll(this);
        if (queueManager != null) {
            queueManager.saveSync();
        }
        getLogger().info("RoyPodium disabled cleanly.");
    }

    /**
     * Reloads all configuration files and manual pending-rewards edits without losing queues, closing open GUIs safely.
     */
    public boolean reloadPlugin() {
        stopServices(true);
        boolean clean = configManager.loadAll();
        queueManager.load();
        if (discordManager != null) {
            discordManager.reload();
        }
        if (metricsManager != null && placeholderResolver != null) {
            metricsManager.setPapiAvailable(placeholderResolver.isPlaceholderApiAvailable());
        }
        if (aliasManager != null && podiumCommand != null) {
            aliasManager.reloadAliases(configManager.getMainConfig().getCommandAliases(), podiumCommand);
        }
        startServices();
        return clean;
    }

    private void registerListenersAndCommands() {
        HandlerList.unregisterAll(this);
        getServer().getPluginManager().registerEvents(queueManager, this);
        getServer().getPluginManager().registerEvents(chatInputManager, this);
        getServer().getPluginManager().registerEvents(new GuiSecurityListener(guiManager), this);

        this.podiumCommand = new PodiumCommand(
                this, configManager, schedulerAdapter, placeholderResolver, podiumManager, queueManager, guiManager
        );
        PluginCommand mainCmd = getCommand("roypodium");
        if (mainCmd != null) {
            mainCmd.setExecutor(podiumCommand);
            mainCmd.setTabCompleter(podiumCommand);
        }
        aliasManager.reloadAliases(configManager.getMainConfig().getCommandAliases(), podiumCommand);
    }

    private void startServices() {
        var mainCfg = configManager.getMainConfig();
        dispatchQueue.start(
                mainCfg.getCommandsPerTick(),
                mainCfg.getIntervalTicks(),
                mainCfg.getTickBudgetMs(),
                mainCfg.getMinTps()
        );
        scheduleManager.start();
    }

    private void stopServices(boolean notifyGuiReload) {
        if (guiManager != null) {
            guiManager.closeAll(notifyGuiReload);
        }
        if (scheduleManager != null) {
            scheduleManager.stop();
        }
        if (dispatchQueue != null) {
            dispatchQueue.stop();
        }
        if (schedulerAdapter != null) {
            schedulerAdapter.cancelAll();
        }
    }
}
