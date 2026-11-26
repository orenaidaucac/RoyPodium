package com.github.orenaidaucac.royPodium.queue;

import com.github.orenaidaucac.royPodium.scheduler.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

/**
 * Tick-budgeted console command dispatcher that spreads large reward bursts across ticks to protect TPS.
 */
public final class CommandDispatchQueue {

    private final Plugin plugin;
    private final SchedulerAdapter scheduler;
    private final Queue<String> queue = new ConcurrentLinkedQueue<>();

    private SchedulerAdapter.ScheduledHandle taskHandle;
    private volatile int commandsPerTick = 5;
    private volatile int intervalTicks = 1;

    public CommandDispatchQueue(Plugin plugin, SchedulerAdapter scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
    }

    public void start(int commandsPerTick, int intervalTicks) {
        stop();
        this.commandsPerTick = Math.max(1, commandsPerTick);
        this.intervalTicks = Math.max(1, intervalTicks);
        this.taskHandle = scheduler.runGlobalTimer(this::tick, this.intervalTicks, this.intervalTicks);
    }

    public void stop() {
        if (taskHandle != null) {
            taskHandle.cancel();
            taskHandle = null;
        }
    }

    public void enqueue(String command) {
        if (command != null && !command.isBlank()) {
            queue.add(command.startsWith("/") ? command.substring(1) : command);
        }
    }

    public void enqueueAll(Collection<String> commands) {
        if (commands != null) {
            for (String cmd : commands) {
                enqueue(cmd);
            }
        }
    }

    public int pendingCount() {
        return queue.size();
    }

    public void clear() {
        queue.clear();
    }

    /**
     * Executes up to {@code commandsPerTick} commands on the Global Region / Main Thread.
     */
    public void tick() {
        if (queue.isEmpty() || !plugin.isEnabled()) {
            return;
        }
        int budget = commandsPerTick;
        for (int i = 0; i < budget; i++) {
            String cmd = queue.poll();
            if (cmd == null) {
                break;
            }
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "Failed to dispatch console command '" + cmd + "': " + ex.getMessage(), ex);
            }
        }
    }
}
