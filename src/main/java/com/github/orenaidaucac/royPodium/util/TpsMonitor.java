package com.github.orenaidaucac.royPodium.util;

import org.bukkit.Bukkit;

import java.lang.reflect.Method;

/**
 * Cross-platform server TPS reader (Paper/Purpur/Folia/Leaf) with safe fallback on Bukkit/Spigot.
 */
public final class TpsMonitor {

    private static volatile Method getTpsMethod;
    private static volatile boolean methodChecked = false;

    private TpsMonitor() {
    }

    /**
     * Returns the 1-minute average TPS, or {@code 20.0} if the server software does not expose {@code Server#getTPS()}.
     */
    public static double getRecentTps() {
        try {
            if (!methodChecked) {
                try {
                    getTpsMethod = Bukkit.getServer().getClass().getMethod("getTPS");
                } catch (NoSuchMethodException ignored) {
                    getTpsMethod = null;
                }
                methodChecked = true;
            }
            if (getTpsMethod != null && Bukkit.getServer() != null) {
                Object result = getTpsMethod.invoke(Bukkit.getServer());
                if (result instanceof double[] tps && tps.length > 0) {
                    return Math.min(20.0, tps[0]);
                }
            }
        } catch (Throwable ignored) {
        }
        return 20.0;
    }

    /**
     * Scales a per-tick budget down when server TPS falls below {@code minTps}.
     */
    public static int scaleBudget(int baseBudget, double minTps) {
        if (baseBudget <= 1 || minTps <= 0) {
            return Math.max(1, baseBudget);
        }
        double tps = getRecentTps();
        if (tps >= minTps) {
            return baseBudget;
        }
        if (tps < minTps - 2.0) {
            return 1;
        }
        return Math.max(1, baseBudget / 4);
    }
}
