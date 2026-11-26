package com.github.orenaidaucac.royPodium.config;

import com.github.orenaidaucac.royPodium.scheduler.MissedPolicy;
import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parsed representation of {@code config.yml}.
 */
@Getter
@Builder
public final class MainConfig {

    private final ZoneId defaultTimezone;
    private final MissedPolicy missedSchedulePolicy;
    private final int maxCatchupRuns;
    private final Set<String> invalidPlaceholderValues;

    private final int commandsPerTick;
    private final int intervalTicks;

    private final int snapshotRanksPerTick;
    private final double tickBudgetMs;
    private final double minTps;
    private final boolean asyncUuidLookup;

    private final Duration queueExpiration;
    private final int joinDelayTicks;
    private final boolean notifyOnJoin;

    private final List<String> commandAliases;
    private final Map<String, CustomSubcommand> customSubcommands;

    @Getter
    @Builder
    public static final class CustomSubcommand {
        private final String name;
        private final String permission;
        private final String description;
        private final String usage;
        private final List<String> messages;
        private final List<String> consoleCommands;
        private final List<String> playerCommands;
    }
}
