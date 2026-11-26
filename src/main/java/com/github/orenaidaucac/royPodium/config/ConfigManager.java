package com.github.orenaidaucac.royPodium.config;

import com.github.orenaidaucac.royPodium.podium.ConditionSpec;
import com.github.orenaidaucac.royPodium.podium.PodiumDefinition;
import com.github.orenaidaucac.royPodium.podium.TiePolicy;
import com.github.orenaidaucac.royPodium.scheduler.MissedPolicy;
import com.github.orenaidaucac.royPodium.scheduler.ScheduleSpec;
import com.github.orenaidaucac.royPodium.util.AtomicFileUtil;
import com.github.orenaidaucac.royPodium.util.TimeUtil;
import lombok.Getter;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Loads, validates, migrates, and persists all split YAML configuration files.
 */
public final class ConfigManager {

    private static final int CURRENT_CONFIG_VERSION = 2;
    private static final List<String> CONFIG_FILES = List.of(
            "config.yml", "messages.yml", "schedules.yml", "podiums.yml", "gui.yml"
    );

    private final Plugin plugin;
    private final Logger logger;

    @Getter private MainConfig mainConfig;
    @Getter private MessagesConfig messages;
    @Getter private GuiConfig guiConfig;
    @Getter private Map<String, ScheduleSpec> schedules = new LinkedHashMap<>();
    @Getter private Map<String, PodiumDefinition> podiums = new LinkedHashMap<>();
    @Getter private List<String> lastValidationWarnings = List.of();

    private YamlConfiguration rawMain;
    private YamlConfiguration rawMessages;
    private YamlConfiguration rawSchedules;
    private YamlConfiguration rawPodiums;
    private YamlConfiguration rawGui;

    public ConfigManager(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Loads and validates all configuration files, returning {@code true} if no validation warnings occurred.
     */
    public boolean loadAll() {
        List<String> warnings = new ArrayList<>();
        ensureDefaultsExist();

        rawMain = loadAndMigrate("config.yml", true, warnings);
        rawMessages = loadAndMigrate("messages.yml", true, warnings);
        rawSchedules = loadAndMigrate("schedules.yml", false, warnings);
        rawPodiums = loadAndMigrate("podiums.yml", false, warnings);
        rawGui = loadAndMigrate("gui.yml", true, warnings);

        this.mainConfig = parseMainConfig(rawMain, warnings);
        this.messages = new MessagesConfig(rawMessages);
        this.guiConfig = new GuiConfig(rawGui);
        validateGuiConfig(rawGui, warnings);
        this.schedules = parseSchedules(rawSchedules, mainConfig.getDefaultTimezone(), warnings);
        this.podiums = parsePodiums(rawPodiums, schedules, warnings);

        this.lastValidationWarnings = List.copyOf(warnings);
        if (!warnings.isEmpty()) {
            logger.warning("Configuration loaded with " + warnings.size() + " warning(s):");
            for (String w : warnings) {
                logger.warning("  - " + w);
            }
        }
        return warnings.isEmpty();
    }

    private void ensureDefaultsExist() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        for (String fileName : CONFIG_FILES) {
            File target = new File(plugin.getDataFolder(), fileName);
            if (!target.exists()) {
                plugin.saveResource(fileName, false);
            }
        }
    }

    private YamlConfiguration loadAndMigrate(String fileName, boolean mergeMissingKeys, List<String> warnings) {
        File file = new File(plugin.getDataFolder(), fileName);
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception ex) {
            warnings.add("[" + fileName + "] Failed to parse YAML syntax: " + ex.getMessage()
                    + ". Fix: Check indentation and quotes in " + fileName + ".");
            return loadDefaultYaml(fileName);
        }

        YamlConfiguration defaults = loadDefaultYaml(fileName);
        int fileVersion = yaml.getInt("config-version", 0);
        boolean modified = false;

        if (mergeMissingKeys) {
            for (String key : defaults.getKeys(true)) {
                if (!yaml.contains(key)) {
                    yaml.set(key, defaults.get(key));
                    modified = true;
                }
            }
        }
        if (fileVersion < CURRENT_CONFIG_VERSION) {
            yaml.set("config-version", CURRENT_CONFIG_VERSION);
            modified = true;
        }

        if (modified) {
            try {
                AtomicFileUtil.writeAtomic(file.toPath(), yaml.saveToString());
                logger.info("[" + fileName + "] Automatically migrated configuration to version " + CURRENT_CONFIG_VERSION + ".");
            } catch (IOException ex) {
                warnings.add("[" + fileName + "] Could not save migrated configuration: " + ex.getMessage());
            }
        }
        return yaml;
    }

    private YamlConfiguration loadDefaultYaml(String fileName) {
        YamlConfiguration defaults = new YamlConfiguration();
        try (InputStream in = plugin.getResource(fileName)) {
            if (in != null) {
                defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
        return defaults;
    }

    private MainConfig parseMainConfig(YamlConfiguration yaml, List<String> warnings) {
        String tzRaw = yaml.getString("settings.default-timezone", "UTC");
        ZoneId defaultTz = TimeUtil.parseZoneId(tzRaw).orElseGet(() -> {
            warnings.add("[config.yml] Key 'settings.default-timezone' has invalid timezone '" + tzRaw
                    + "'. Fix: Use a valid IANA timezone ID such as 'UTC', 'America/New_York', or 'Asia/Ho_Chi_Minh'. Using 'UTC'.");
            return ZoneId.of("UTC");
        });

        String policyRaw = yaml.getString("settings.missed-schedule-policy", "RUN_LATEST");
        MissedPolicy missedPolicy = parseMissedPolicy(policyRaw, "config.yml", "settings.missed-schedule-policy", warnings)
                .orElse(MissedPolicy.RUN_LATEST);

        int maxCatchup = yaml.getInt("settings.max-catchup-runs", 3);
        if (maxCatchup < 1) {
            warnings.add("[config.yml] Key 'settings.max-catchup-runs' must be >= 1 (got " + maxCatchup
                    + "). Fix: Set a positive integer such as 3. Using 3.");
            maxCatchup = 3;
        }

        Set<String> invalidValues = new HashSet<>();
        for (String val : yaml.getStringList("settings.invalid-placeholder-values")) {
            invalidValues.add(val == null ? "" : val.trim().toLowerCase());
        }
        if (invalidValues.isEmpty()) {
            invalidValues.addAll(Set.of("", "null", "none", "n/a", "---", "?"));
        }

        int commandsPerTick = yaml.getInt("dispatch.commands-per-tick", 5);
        if (commandsPerTick < 1) {
            warnings.add("[config.yml] Key 'dispatch.commands-per-tick' must be >= 1 (got " + commandsPerTick
                    + "). Fix: Set a positive integer (e.g. 5). Using 5.");
            commandsPerTick = 5;
        }

        int intervalTicks = yaml.getInt("dispatch.interval-ticks", 1);
        if (intervalTicks < 1) {
            warnings.add("[config.yml] Key 'dispatch.interval-ticks' must be >= 1 (got " + intervalTicks
                    + "). Fix: Set >= 1 (20 ticks = 1 second). Using 1.");
            intervalTicks = 1;
        }

        String expRaw = yaml.getString("queue.expiration", "7d");
        Duration queueExpiration = TimeUtil.parseDuration(expRaw).orElseGet(() -> {
            warnings.add("[config.yml] Key 'queue.expiration' has invalid duration '" + expRaw
                    + "'. Fix: Use formats like '7d', '12h', '30m', or '0' for never. Using '7d'.");
            return Duration.ofDays(7);
        });

        int joinDelay = Math.max(1, yaml.getInt("queue.join-delay-ticks", 20));
        boolean notifyOnJoin = yaml.getBoolean("queue.notify-on-join", true);
        List<String> commandAliases = yaml.getStringList("command-aliases");

        Map<String, MainConfig.CustomSubcommand> customSubs = new LinkedHashMap<>();
        ConfigurationSection subSec = yaml.getConfigurationSection("custom-subcommands");
        if (subSec != null) {
            for (String key : subSec.getKeys(false)) {
                ConfigurationSection entry = subSec.getConfigurationSection(key);
                if (entry == null) {
                    warnings.add("[config.yml] Key 'custom-subcommands." + key + "' must be a YAML section.");
                    continue;
                }
                String lower = key.toLowerCase();
                customSubs.put(lower, MainConfig.CustomSubcommand.builder()
                        .name(lower)
                        .permission(entry.getString("permission", "roypodium.use"))
                        .description(entry.getString("description", "Custom RoyPodium command."))
                        .usage(entry.getString("usage", "/podium " + lower))
                        .messages(entry.getStringList("messages"))
                        .consoleCommands(entry.getStringList("console-commands"))
                        .playerCommands(entry.getStringList("player-commands"))
                        .build());
            }
        }

        return MainConfig.builder()
                .defaultTimezone(defaultTz)
                .missedSchedulePolicy(missedPolicy)
                .maxCatchupRuns(maxCatchup)
                .invalidPlaceholderValues(Set.copyOf(invalidValues))
                .commandsPerTick(commandsPerTick)
                .intervalTicks(intervalTicks)
                .queueExpiration(queueExpiration)
                .joinDelayTicks(joinDelay)
                .notifyOnJoin(notifyOnJoin)
                .commandAliases(List.copyOf(commandAliases))
                .customSubcommands(Map.copyOf(customSubs))
                .build();
    }

    private Map<String, ScheduleSpec> parseSchedules(YamlConfiguration yaml, ZoneId defaultTz, List<String> warnings) {
        Map<String, ScheduleSpec> map = new LinkedHashMap<>();
        ConfigurationSection sec = yaml.getConfigurationSection("schedules");
        if (sec == null) {
            return map;
        }
        for (String id : sec.getKeys(false)) {
            ConfigurationSection entry = sec.getConfigurationSection(id);
            if (entry == null) {
                warnings.add("[schedules.yml] Key 'schedules." + id + "' must be a YAML section.");
                continue;
            }
            String tzRaw = entry.getString("timezone");
            ZoneId tz = defaultTz;
            if (tzRaw != null && !tzRaw.isBlank()) {
                Optional<ZoneId> parsedTz = TimeUtil.parseZoneId(tzRaw);
                if (parsedTz.isPresent()) {
                    tz = parsedTz.get();
                } else {
                    warnings.add("[schedules.yml] Key 'schedules." + id + ".timezone' has invalid timezone '" + tzRaw
                            + "'. Fix: Use a valid IANA timezone (e.g. 'UTC', 'America/New_York'). Using default '" + defaultTz + "'.");
                }
            }

            String freqRaw = entry.getString("frequency", "DAILY").toUpperCase();
            ScheduleSpec.Frequency freq;
            try {
                freq = ScheduleSpec.Frequency.valueOf(freqRaw);
            } catch (IllegalArgumentException ex) {
                warnings.add("[schedules.yml] Key 'schedules." + id + ".frequency' has invalid value '" + freqRaw
                        + "'. Fix: Use DAILY, WEEKLY, MONTHLY, or INTERVAL. Skipping schedule '" + id + "'.");
                continue;
            }

            String timeRaw = entry.getString("time-start", "00:00:00");
            Optional<LocalTime> parsedTime = TimeUtil.parseLocalTime(timeRaw);
            if (parsedTime.isEmpty()) {
                warnings.add("[schedules.yml] Key 'schedules." + id + ".time-start' has invalid time '" + timeRaw
                        + "'. Fix: Use 'HH:mm' or 'HH:mm:ss' (24-hour format, e.g. '20:00:00'). Skipping schedule '" + id + "'.");
                continue;
            }

            Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            if (freq == ScheduleSpec.Frequency.WEEKLY) {
                for (String dayStr : entry.getStringList("days")) {
                    try {
                        days.add(DayOfWeek.valueOf(dayStr.trim().toUpperCase()));
                    } catch (IllegalArgumentException ex) {
                        warnings.add("[schedules.yml] Key 'schedules." + id + ".days' contains invalid day '" + dayStr
                                + "'. Fix: Use MONDAY..SUNDAY.");
                    }
                }
                if (days.isEmpty()) {
                    warnings.add("[schedules.yml] Key 'schedules." + id + ".days' is empty for WEEKLY frequency. Defaulting to SUNDAY.");
                    days.add(DayOfWeek.SUNDAY);
                }
            }

            int dayOfMonth = 1;
            if (freq == ScheduleSpec.Frequency.MONTHLY) {
                String domRaw = entry.getString("day-of-month", "1").trim();
                if ("LAST".equalsIgnoreCase(domRaw)) {
                    dayOfMonth = -1;
                } else {
                    try {
                        int d = Integer.parseInt(domRaw);
                        if (d < 1 || d > 31) {
                            throw new NumberFormatException();
                        }
                        dayOfMonth = d;
                    } catch (NumberFormatException ex) {
                        warnings.add("[schedules.yml] Key 'schedules." + id + ".day-of-month' must be 1..31 or 'LAST' (got '" + domRaw + "'). Using 1.");
                        dayOfMonth = 1;
                    }
                }
            }

            Duration interval = Duration.ofHours(1);
            if (freq == ScheduleSpec.Frequency.INTERVAL) {
                String intRaw = entry.getString("interval", "1h");
                Optional<Duration> parsedInt = TimeUtil.parseDuration(intRaw);
                if (parsedInt.isEmpty() || parsedInt.get().isZero() || parsedInt.get().isNegative()) {
                    warnings.add("[schedules.yml] Key 'schedules." + id + ".interval' has invalid duration '" + intRaw
                            + "'. Fix: Use positive duration like '30m' or '1h'. Using '1h'.");
                } else {
                    interval = parsedInt.get();
                }
            }

            MissedPolicy mp = null;
            if (entry.contains("missed-policy")) {
                mp = parseMissedPolicy(entry.getString("missed-policy"),
                        "schedules.yml", "schedules." + id + ".missed-policy", warnings).orElse(null);
            }

            map.put(id, ScheduleSpec.builder()
                    .id(id)
                    .timezone(tz)
                    .frequency(freq)
                    .timeStart(parsedTime.get())
                    .daysOfWeek(days)
                    .dayOfMonth(dayOfMonth)
                    .interval(interval)
                    .missedPolicy(mp)
                    .build());
        }
        return map;
    }

    private Map<String, PodiumDefinition> parsePodiums(YamlConfiguration yaml, Map<String, ScheduleSpec> loadedSchedules, List<String> warnings) {
        Map<String, PodiumDefinition> map = new LinkedHashMap<>();
        ConfigurationSection sec = yaml.getConfigurationSection("podiums");
        if (sec == null) {
            return map;
        }
        for (String id : sec.getKeys(false)) {
            ConfigurationSection entry = sec.getConfigurationSection(id);
            if (entry == null) {
                warnings.add("[podiums.yml] Key 'podiums." + id + "' must be a YAML section.");
                continue;
            }
            boolean enabled = entry.getBoolean("enabled", true);
            String displayName = entry.getString("display-name", id);
            String scheduleId = entry.getString("schedule", "");
            if (enabled && !scheduleId.isBlank() && !loadedSchedules.containsKey(scheduleId)) {
                warnings.add("[podiums.yml] Key 'podiums." + id + ".schedule' references unknown schedule '" + scheduleId
                        + "'. Fix: Define '" + scheduleId + "' in schedules.yml or update the schedule key.");
            }

            String playerPapi = entry.getString("player-placeholder", "");
            if (playerPapi.isBlank()) {
                warnings.add("[podiums.yml] Key 'podiums." + id + ".player-placeholder' is empty. Fix: Provide a PlaceholderAPI placeholder containing {rank}.");
            }
            String valuePapi = entry.getString("value-placeholder", "");

            int topSize = entry.getInt("top-size", 5);
            if (topSize < 1) {
                warnings.add("[podiums.yml] Key 'podiums." + id + ".top-size' must be >= 1 (got " + topSize + "). Using 5.");
                topSize = 5;
            }

            String tieRaw = entry.getString("tie-policy", "ALLOW_TIES").toUpperCase();
            TiePolicy tiePolicy;
            try {
                tiePolicy = TiePolicy.valueOf(tieRaw);
            } catch (IllegalArgumentException ex) {
                warnings.add("[podiums.yml] Key 'podiums." + id + ".tie-policy' has invalid value '" + tieRaw
                        + "'. Fix: Use ALLOW_TIES or SKIP_DUPLICATE_PLAYER. Using ALLOW_TIES.");
                tiePolicy = TiePolicy.ALLOW_TIES;
            }

            List<ConditionSpec> conditions = new ArrayList<>();
            for (String condRaw : entry.getStringList("conditions")) {
                Optional<ConditionSpec> parsedCond = ConditionSpec.parse(condRaw);
                if (parsedCond.isPresent()) {
                    conditions.add(parsedCond.get());
                } else {
                    warnings.add("[podiums.yml] Key 'podiums." + id + ".conditions' has invalid condition '" + condRaw
                            + "'. Fix: Use '<operator> <value>' with operators =, !=, >, <, >=, <= (e.g. '>= 5').");
                }
            }

            Map<String, List<String>> rankRules = new LinkedHashMap<>();
            ConfigurationSection ranksSec = entry.getConfigurationSection("ranks");
            if (ranksSec != null) {
                for (String rankKey : ranksSec.getKeys(false)) {
                    rankRules.put(rankKey, new ArrayList<>(ranksSec.getStringList(rankKey)));
                }
            }

            map.put(id, PodiumDefinition.builder()
                    .id(id)
                    .enabled(enabled)
                    .displayName(displayName)
                    .scheduleId(scheduleId)
                    .playerPlaceholder(playerPapi)
                    .valuePlaceholder(valuePapi)
                    .topSize(topSize)
                    .tiePolicy(tiePolicy)
                    .conditions(List.copyOf(conditions))
                    .rankRules(rankRules)
                    .build());
        }
        return map;
    }

    private void validateGuiConfig(YamlConfiguration yaml, List<String> warnings) {
        ConfigurationSection soundsSec = yaml.getConfigurationSection("sounds");
        if (soundsSec != null) {
            for (String key : soundsSec.getKeys(false)) {
                String val = soundsSec.getString(key, "");
                try {
                    Sound.valueOf(val.trim().toUpperCase());
                } catch (Exception ex) {
                    warnings.add("[gui.yml] Key 'sounds." + key + "' has unknown Sound '" + val
                            + "'. Fix: Use a valid Bukkit Sound name (e.g. UI_BUTTON_CLICK).");
                }
            }
        }
        String fillerMat = yaml.getString("filler.material", "GRAY_STAINED_GLASS_PANE");
        if (Material.matchMaterial(fillerMat) == null) {
            warnings.add("[gui.yml] Key 'filler.material' has unknown Material '" + fillerMat
                    + "'. Fix: Use a valid Minecraft Material name (e.g. GRAY_STAINED_GLASS_PANE).");
        }
    }

    private Optional<MissedPolicy> parseMissedPolicy(String raw, String file, String key, List<String> warnings) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(MissedPolicy.valueOf(raw.trim().toUpperCase()));
        } catch (IllegalArgumentException ex) {
            warnings.add("[" + file + "] Key '" + key + "' has invalid policy '" + raw
                    + "'. Fix: Use SKIP, RUN_LATEST, or RUN_ALL.");
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // Write-back helpers for In-Game GUI Editor
    // -------------------------------------------------------------------------

    public synchronized void savePodium(PodiumDefinition def) {
        String base = "podiums." + def.getId();
        rawPodiums.set(base + ".enabled", def.isEnabled());
        rawPodiums.set(base + ".display-name", def.getDisplayName());
        rawPodiums.set(base + ".schedule", def.getScheduleId());
        rawPodiums.set(base + ".player-placeholder", def.getPlayerPlaceholder());
        rawPodiums.set(base + ".value-placeholder", def.getValuePlaceholder());
        rawPodiums.set(base + ".top-size", def.getTopSize());
        rawPodiums.set(base + ".tie-policy", def.getTiePolicy().name());
        rawPodiums.set(base + ".conditions", def.getConditions().stream().map(ConditionSpec::getRaw).toList());
        rawPodiums.set(base + ".ranks", null);
        for (Map.Entry<String, List<String>> entry : def.getRankRules().entrySet()) {
            rawPodiums.set(base + ".ranks." + entry.getKey(), entry.getValue());
        }
        podiums.put(def.getId(), def);
        saveYaml("podiums.yml", rawPodiums);
    }

    public synchronized void deletePodium(String id) {
        rawPodiums.set("podiums." + id, null);
        podiums.remove(id);
        saveYaml("podiums.yml", rawPodiums);
    }

    public synchronized void saveSchedule(ScheduleSpec spec) {
        String base = "schedules." + spec.getId();
        rawSchedules.set(base + ".timezone", spec.getTimezone().getId());
        rawSchedules.set(base + ".frequency", spec.getFrequency().name());
        rawSchedules.set(base + ".time-start", spec.getTimeStart().toString());
        if (spec.getFrequency() == ScheduleSpec.Frequency.WEEKLY && spec.getDaysOfWeek() != null) {
            rawSchedules.set(base + ".days", spec.getDaysOfWeek().stream().map(DayOfWeek::name).toList());
        } else if (spec.getFrequency() == ScheduleSpec.Frequency.MONTHLY) {
            rawSchedules.set(base + ".day-of-month", spec.getDayOfMonth() < 0 ? "LAST" : String.valueOf(spec.getDayOfMonth()));
        } else if (spec.getFrequency() == ScheduleSpec.Frequency.INTERVAL && spec.getInterval() != null) {
            rawSchedules.set(base + ".interval", TimeUtil.formatDuration(spec.getInterval()).replace(" ", ""));
        }
        if (spec.getMissedPolicy() != null) {
            rawSchedules.set(base + ".missed-policy", spec.getMissedPolicy().name());
        }
        schedules.put(spec.getId(), spec);
        saveYaml("schedules.yml", rawSchedules);
    }

    public synchronized void deleteSchedule(String id) {
        rawSchedules.set("schedules." + id, null);
        schedules.remove(id);
        saveYaml("schedules.yml", rawSchedules);
    }

    public synchronized void updateMainSetting(String path, Object value) {
        rawMain.set(path, value);
        saveYaml("config.yml", rawMain);
        List<String> warnings = new ArrayList<>();
        this.mainConfig = parseMainConfig(rawMain, warnings);
    }

    private void saveYaml(String fileName, YamlConfiguration yaml) {
        File target = new File(plugin.getDataFolder(), fileName);
        try {
            AtomicFileUtil.writeAtomic(target.toPath(), yaml.saveToString());
        } catch (IOException ex) {
            logger.severe("Failed to save " + fileName + ": " + ex.getMessage());
        }
    }
}
