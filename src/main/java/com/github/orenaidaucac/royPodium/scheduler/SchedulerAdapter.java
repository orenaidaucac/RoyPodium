package com.github.orenaidaucac.royPodium.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Unified Folia-safe scheduler adapter supporting Bukkit, Spigot, Paper, Purpur, Folia, and Leaf.
 */
public final class SchedulerAdapter {

    private static final boolean FOLIA = detectFolia();

    private final Plugin plugin;
    private final Set<ScheduledHandle> activeHandles = ConcurrentHashMap.newKeySet();

    public SchedulerAdapter(Plugin plugin) {
        this.plugin = plugin;
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    /**
     * Executes a task on the Global Region (Folia) or Main Thread (Bukkit/Spigot/Paper).
     */
    public void runGlobal(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        if (FOLIA) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Executes a task on the Global Region / Main Thread after {@code delayTicks}.
     */
    public ScheduledHandle runGlobalLater(Runnable task, long delayTicks) {
        long safeDelay = Math.max(1L, delayTicks);
        if (!plugin.isEnabled()) {
            return ScheduledHandle.NOOP;
        }
        ScheduledHandle handle;
        if (FOLIA) {
            ScheduledTask st = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), safeDelay);
            handle = new FoliaHandle(st);
        } else {
            BukkitTask bt = Bukkit.getScheduler().runTaskLater(plugin, task, safeDelay);
            handle = new BukkitHandle(bt);
        }
        activeHandles.add(handle);
        return handle;
    }

    /**
     * Schedules a repeating task on the Global Region / Main Thread.
     */
    public ScheduledHandle runGlobalTimer(Runnable task, long delayTicks, long periodTicks) {
        long safeDelay = Math.max(1L, delayTicks);
        long safePeriod = Math.max(1L, periodTicks);
        if (!plugin.isEnabled()) {
            return ScheduledHandle.NOOP;
        }
        ScheduledHandle handle;
        if (FOLIA) {
            ScheduledTask st = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> task.run(), safeDelay, safePeriod);
            handle = new FoliaHandle(st);
        } else {
            BukkitTask bt = Bukkit.getScheduler().runTaskTimer(plugin, task, safeDelay, safePeriod);
            handle = new BukkitHandle(bt);
        }
        activeHandles.add(handle);
        return handle;
    }

    /**
     * Executes a task on an entity's owning region thread (Folia) or Main Thread (non-Folia).
     */
    public void runAtEntity(Entity entity, Runnable task) {
        if (!plugin.isEnabled() || entity == null) {
            return;
        }
        if (FOLIA) {
            entity.getScheduler().run(plugin, t -> task.run(), null);
        } else {
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        }
    }

    /**
     * Executes a task on an entity's owning region thread after {@code delayTicks}.
     */
    public void runAtEntityLater(Entity entity, Runnable task, long delayTicks) {
        long safeDelay = Math.max(1L, delayTicks);
        if (!plugin.isEnabled() || entity == null) {
            return;
        }
        if (FOLIA) {
            entity.getScheduler().runDelayed(plugin, t -> task.run(), null, safeDelay);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, safeDelay);
        }
    }

    /**
     * Executes an asynchronous task.
     */
    public void runAsync(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        if (FOLIA) {
            Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    /**
     * Schedules a repeating asynchronous task (seconds-based for Folia AsyncScheduler compatibility).
     */
    public ScheduledHandle runAsyncTimerSeconds(Runnable task, long delaySeconds, long periodSeconds) {
        long safeDelay = Math.max(1L, delaySeconds);
        long safePeriod = Math.max(1L, periodSeconds);
        if (!plugin.isEnabled()) {
            return ScheduledHandle.NOOP;
        }
        ScheduledHandle handle;
        if (FOLIA) {
            ScheduledTask st = Bukkit.getAsyncScheduler().runAtFixedRate(
                    plugin, t -> task.run(), safeDelay, safePeriod, TimeUnit.SECONDS
            );
            handle = new FoliaHandle(st);
        } else {
            BukkitTask bt = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, safeDelay * 20L, safePeriod * 20L);
            handle = new BukkitHandle(bt);
        }
        activeHandles.add(handle);
        return handle;
    }

    /**
     * Cancels all tracked tasks (called during reload and onDisable).
     */
    public void cancelAll() {
        for (ScheduledHandle handle : activeHandles) {
            try {
                handle.cancel();
            } catch (Exception ignored) {
            }
        }
        activeHandles.clear();
        if (FOLIA) {
            try {
                Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
                Bukkit.getAsyncScheduler().cancelTasks(plugin);
            } catch (Exception ignored) {
            }
        } else {
            try {
                Bukkit.getScheduler().cancelTasks(plugin);
            } catch (Exception ignored) {
            }
        }
    }

    public interface ScheduledHandle {
        ScheduledHandle NOOP = () -> {};
        void cancel();
    }

    private record BukkitHandle(BukkitTask task) implements ScheduledHandle {
        @Override
        public void cancel() {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
    }

    private record FoliaHandle(ScheduledTask task) implements ScheduledHandle {
        @Override
        public void cancel() {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
    }
}
