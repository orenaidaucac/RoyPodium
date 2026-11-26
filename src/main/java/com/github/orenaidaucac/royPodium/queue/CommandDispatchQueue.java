package com.github.orenaidaucac.royPodium.queue;

import com.github.orenaidaucac.royPodium.scheduler.SchedulerAdapter;
import com.github.orenaidaucac.royPodium.util.TpsMonitor;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

/**
 * Tick-budgeted, TPS-aware console command dispatcher that spreads large reward bursts
 * across ticks and yields CPU when server TPS is low or MSPT budget is reached.
 */
public final class CommandDispatchQueue {

    private final Plugin plugin;
    private final SchedulerAdapter scheduler;
    private final Queue<String> queue = new ConcurrentLinkedQueue<>();

    private SchedulerAdapter.ScheduledHandle taskHandle;
    private volatile int commandsPerTick = 5;
    private volatile int intervalTicks = 1;
    private volatile double tickBudgetMs = 12.0;
    private volatile double minTps = 18.0;

    public CommandDispatchQueue(Plugin plugin, SchedulerAdapter scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
    }

    public void start(int commandsPerTick, int intervalTicks) {
        start(commandsPerTick, intervalTicks, 12.0, 18.0);
    }

    public void start(int commandsPerTick, int intervalTicks, double tickBudgetMs, double minTps) {
        stop();
        this.commandsPerTick = Math.max(1, commandsPerTick);
        this.intervalTicks = Math.max(1, intervalTicks);
        this.tickBudgetMs = Math.max(1.0, tickBudgetMs);
        this.minTps = Math.max(1.0, minTps);
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
     * Executes up to scaled {@code commandsPerTick} commands within {@code tickBudgetMs} on the Global/Main Thread.
     */
    public void tick() {
        if (queue.isEmpty() || !plugin.isEnabled()) {
            return;
        }
        int budget = TpsMonitor.scaleBudget(commandsPerTick, minTps);
        long deadlineNs = System.nanoTime() + (long) (tickBudgetMs * 1_000_000.0);

        for (int i = 0; i < budget; i++) {
            if (i > 0 && System.nanoTime() >= deadlineNs) {
                break;
            }
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
