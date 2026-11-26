package com.github.orenaidaucac.royPodium.podium;

import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Definition of a configurable leaderboard podium and its per-rank reward commands.
 */
@Getter
@Builder
public final class PodiumDefinition {

    private final String id;
    private final boolean enabled;
    private final String displayName;
    private final String scheduleId;
    private final String playerPlaceholder;
    private final String valuePlaceholder;
    private final int topSize;
    private final TiePolicy tiePolicy;
    @Builder.Default
    private final List<ConditionSpec> conditions = List.of();
    /** Ordered map of rank expression ("1", "2", "4-10") -> list of console commands. */
    @Builder.Default
    private final Map<String, List<String>> rankRules = new LinkedHashMap<>();

    /**
     * Resolves all reward commands configured for {@code rank}.
     */
    public List<String> getCommandsForRank(int rank) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : rankRules.entrySet()) {
            if (matchesRankExpression(entry.getKey(), rank)) {
                result.addAll(entry.getValue());
            }
        }
        return result;
    }

    /**
     * Checks if a rank expression like {@code "1"} or {@code "4-10"} matches {@code rank}.
     */
    public static boolean matchesRankExpression(String expr, int rank) {
        if (expr == null || expr.isBlank()) {
            return false;
        }
        String trimmed = expr.trim();
        if (trimmed.contains("-")) {
            String[] parts = trimmed.split("-", 2);
            try {
                int start = Integer.parseInt(parts[0].trim());
                int end = Integer.parseInt(parts[1].trim());
                return rank >= Math.min(start, end) && rank <= Math.max(start, end);
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        try {
            return Integer.parseInt(trimmed) == rank;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
