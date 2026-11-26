package com.github.orenaidaucac.royPodium.config;

import com.github.orenaidaucac.royPodium.discord.DiscordEmbedBuilder;
import com.github.orenaidaucac.royPodium.discord.DiscordTemplate;
import com.github.orenaidaucac.royPodium.discord.DiscordWebhookValidator;
import com.github.orenaidaucac.royPodium.discord.ValueFormatSpec;
import com.github.orenaidaucac.royPodium.podium.ConditionSpec;
import com.github.orenaidaucac.royPodium.podium.PodiumDefinition;
import com.github.orenaidaucac.royPodium.podium.TiePolicy;
import com.github.orenaidaucac.royPodium.scheduler.DatePatternSpec;
import com.github.orenaidaucac.royPodium.scheduler.MissedPolicy;
import com.github.orenaidaucac.royPodium.scheduler.ScheduleSpec;
import com.github.orenaidaucac.royPodium.scheduler.ShortMonthPolicy;
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

    private static final int CURRENT_CONFIG_VERSION = 5;
    private static final List<String> CONFIG_FILES = List.of(
            "config.yml", "messages.yml", "schedules.yml", "podiums.yml", "gui.yml", "discord.yml"
    );

    private final Plugin plugin;
    private final Logger logger;

    @Getter private MainConfig mainConfig;
    @Getter private MessagesConfig messages;
    @Getter private GuiConfig guiConfig;
    @Getter private DiscordConfig discordConfig;
    @Getter private Map<String, ScheduleSpec> schedules = new LinkedHashMap<>();
    @Getter private Map<String, PodiumDefinition> podiums = new LinkedHashMap<>();
    @Getter private List<String> lastValidationWarnings = List.of();

    private YamlConfiguration rawMain;
    private YamlConfiguration rawMessages;
    private YamlConfiguration rawSchedules;
    private YamlConfiguration rawPodiums;
    private YamlConfiguration rawGui;
    private YamlConfiguration rawDiscord;

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
        rawDiscord = loadAndMigrate("discord.yml", true, warnings);

        this.mainConfig = parseMainConfig(rawMain, warnings);
        this.messages = new MessagesConfig(rawMessages);
        this.guiConfig = new GuiConfig(rawGui);
        validateGuiConfig(rawGui, warnings);
        this.discordConfig = parseDiscordConfig(rawDiscord, warnings);
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
                        + "'. Fix: Use DAILY, WEEKLY, MONTHLY, INTERVAL, or SPECIFIC_DATES. Skipping schedule '" + id + "'.");
                continue;
            }

            String timeRaw = entry.getString("time-start", "00:00:00");
            Optional<LocalTime> parsedTime = TimeUtil.parseLocalTime(timeRaw);
            if (parsedTime.isEmpty() && freq != ScheduleSpec.Frequency.SPECIFIC_DATES) {
                warnings.add("[schedules.yml] Key 'schedules." + id + ".time-start' has invalid time '" + timeRaw
                        + "'. Fix: Use 'HH:mm' or 'HH:mm:ss' (24-hour format, e.g. '20:00:00'). Skipping schedule '" + id + "'.");
                continue;
            }
            LocalTime resolvedTime = parsedTime.orElse(LocalTime.MIDNIGHT);

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

            ShortMonthPolicy shortMonthPolicy = ShortMonthPolicy.SKIP_MONTH;
            List<DatePatternSpec> datePatterns = new ArrayList<>();
            if (freq == ScheduleSpec.Frequency.SPECIFIC_DATES) {
                String smpRaw = entry.getString("short-month-policy", "SKIP_MONTH").trim().toUpperCase();
                try {
                    shortMonthPolicy = ShortMonthPolicy.valueOf(smpRaw);
                } catch (IllegalArgumentException ex) {
                    warnings.add("[schedules.yml] Key 'schedules." + id + ".short-month-policy' has invalid value '" + smpRaw
                            + "'. Fix: Use SKIP_MONTH or LAST_DAY. Using SKIP_MONTH.");
                }
                List<String> rawDates = entry.getStringList("dates");
                if (rawDates.isEmpty()) {
                    warnings.add("[schedules.yml] Key 'schedules." + id + ".dates' is empty for SPECIFIC_DATES frequency. Add at least one entry like '2026-12-25 20:00:00'.");
                }
                for (String rawDate : rawDates) {
                    DatePatternSpec.ParseResult pr = DatePatternSpec.parse(rawDate);
                    if (pr.isValid()) {
                        datePatterns.add(pr.spec());
                    } else {
                        warnings.add("[schedules.yml] Key 'schedules." + id + ".dates' has invalid entry '" + rawDate
                                + "': " + pr.error() + " Fix: Use 'YYYY-MM-DD HH:mm:ss' (use '*' for wildcards). Skipping invalid entry.");
                    }
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
                    .timeStart(resolvedTime)
                    .daysOfWeek(days)
                    .dayOfMonth(dayOfMonth)
                    .interval(interval)
                    .missedPolicy(mp)
                    .datePatterns(List.copyOf(datePatterns))
                    .shortMonthPolicy(shortMonthPolicy)
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

            boolean discordEnabled = entry.getBoolean("discord.enabled", true);
            List<String> discordWebhooks = new ArrayList<>(entry.getStringList("discord.webhooks"));
            String singleWh = entry.getString("discord.webhook", "");
            if (!singleWh.isBlank() && !discordWebhooks.contains(singleWh)) {
                discordWebhooks.add(singleWh);
            }
            String discordTemplate = entry.getString("discord.template", "");
            Integer discordColor = entry.contains("discord.color")
                    ? parseColorValue(entry.getString("discord.color"), "podiums.yml", "podiums." + id + ".discord.color", warnings)
                    : null;
            String discordThreadId = entry.getString("discord.thread-id", "");
            String discordThreadName = entry.getString("discord.thread-name", "");
            List<String> discordAppliedTags = entry.getStringList("discord.applied-tags");

            ValueFormatSpec valueFormat = ValueFormatSpec.builder()
                    .grouping(entry.getBoolean("value-format.grouping", true))
                    .decimals(entry.getInt("value-format.decimals", -1))
                    .compact(entry.getBoolean("value-format.compact", false))
                    .prefix(entry.getString("value-format.prefix", ""))
                    .suffix(entry.getString("value-format.suffix", ""))
                    .keepNonNumeric(entry.getBoolean("value-format.keep-non-numeric", true))
                    .fallback(entry.getString("value-format.fallback", "N/A"))
                    .build();

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
                    .discordEnabled(discordEnabled)
                    .discordWebhooks(List.copyOf(discordWebhooks))
                    .discordTemplate(discordTemplate)
                    .discordColor(discordColor)
                    .discordThreadId(discordThreadId)
                    .discordThreadName(discordThreadName)
                    .discordAppliedTags(List.copyOf(discordAppliedTags))
                    .valueFormat(valueFormat)
                    .rankRules(rankRules)
                    .build());
        }
        return map;
    }

    private DiscordConfig parseDiscordConfig(YamlConfiguration yaml, List<String> warnings) {
        boolean enabled = yaml.getBoolean("enabled", false);
        String defaultWebhook = yaml.getString("default-webhook", "default");
        String defaultTemplate = yaml.getString("default-template", "default");
        String fallbackTemplate = yaml.getString("fallback-template", "minimal");

        Map<String, DiscordConfig.WebhookProfile> webhooks = new LinkedHashMap<>();
        ConfigurationSection whSec = yaml.getConfigurationSection("webhooks");
        if (whSec != null) {
            for (String key : whSec.getKeys(false)) {
                ConfigurationSection entry = whSec.getConfigurationSection(key);
                if (entry == null) {
                    warnings.add("[discord.yml] Key 'webhooks." + key + "' must be a YAML section.");
                    continue;
                }
                String url = entry.getString("url", "").trim();
                String masked = DiscordWebhookValidator.maskUrl(url);
                if (enabled) {
                    DiscordWebhookValidator.ValidationResult res = DiscordWebhookValidator.validate(url);
                    if (!res.valid()) {
                        warnings.add("[discord.yml] Key 'webhooks." + key + ".url' (" + masked + ") is invalid: "
                                + res.reason() + " Fix: Provide a valid https://discord.com/api/webhooks/<id>/<token> URL. Skipping webhook '" + key + "'.");
                        continue;
                    }
                }
                webhooks.put(key, DiscordConfig.WebhookProfile.builder()
                        .id(key)
                        .url(url)
                        .maskedUrl(masked)
                        .username(entry.getString("username", "RoyPodium"))
                        .avatarUrl(entry.getString("avatar-url", ""))
                        .template(entry.getString("template", ""))
                        .threadId(entry.getString("thread-id", ""))
                        .threadName(entry.getString("thread-name", ""))
                        .appliedTags(List.copyOf(entry.getStringList("applied-tags")))
                        .build());
            }
        }

        int timeoutSec = Math.max(2, yaml.getInt("rate-limit.timeout-seconds", 10));
        int maxRetries = Math.max(0, yaml.getInt("rate-limit.max-retries", 3));
        long minIntervalMs = Math.max(250L, yaml.getLong("rate-limit.min-interval-ms", 1000L));
        int drainSec = Math.max(1, yaml.getInt("rate-limit.shutdown-drain-seconds", 5));
        int testCooldown = Math.max(0, yaml.getInt("test.cooldown-seconds", 10));

        boolean avatarEnabled = yaml.getBoolean("player-avatar.enabled", true);
        String avatarTpl = yaml.getString("player-avatar.url-template", "https://mc-heads.net/avatar/{player}/128");

        String statusOnline = yaml.getString("status-labels.online", "Delivered");
        String statusOffline = yaml.getString("status-labels.offline", "Queued (Offline)");
        String statusSkipped = yaml.getString("status-labels.skipped", "Skipped ({reason})");

        Map<String, String> rankIcons = new LinkedHashMap<>();
        ConfigurationSection iconsSec = yaml.getConfigurationSection("rank-icons");
        String defaultIcon = "#{rank}";
        if (iconsSec != null) {
            for (String key : iconsSec.getKeys(false)) {
                if ("default".equalsIgnoreCase(key)) {
                    defaultIcon = iconsSec.getString(key, "#{rank}");
                } else {
                    rankIcons.put(key, iconsSec.getString(key, "#" + key));
                }
            }
        }

        Map<String, DiscordTemplate> templates = parseDiscordTemplates(yaml, warnings);

        return DiscordConfig.builder()
                .enabled(enabled)
                .defaultWebhook(defaultWebhook)
                .defaultTemplate(defaultTemplate)
                .fallbackTemplate(fallbackTemplate)
                .webhooks(Map.copyOf(webhooks))
                .templates(Map.copyOf(templates))
                .timeoutSeconds(timeoutSec)
                .maxRetries(maxRetries)
                .minIntervalMs(minIntervalMs)
                .shutdownDrainSeconds(drainSec)
                .testCooldownSeconds(testCooldown)
                .playerAvatarEnabled(avatarEnabled)
                .playerAvatarUrlTemplate(avatarTpl)
                .statusOnline(statusOnline)
                .statusOffline(statusOffline)
                .statusSkipped(statusSkipped)
                .rankIcons(Map.copyOf(rankIcons))
                .defaultRankIcon(defaultIcon)
                .build();
    }

    private Map<String, DiscordTemplate> parseDiscordTemplates(YamlConfiguration yaml, List<String> warnings) {
        Map<String, DiscordTemplate> resolved = new LinkedHashMap<>();
        ConfigurationSection tplRoot = yaml.getConfigurationSection("templates");

        // Backward compatibility: if an older config only has top-level 'embed:', treat it as 'default'
        if (tplRoot == null && yaml.isConfigurationSection("embed")) {
            DiscordTemplate legacy = parseTemplateSection("default", yaml.getConfigurationSection("embed"),
                    DiscordTemplate.builder().id("default").build(), warnings);
            resolved.put("default", legacy);
            resolved.put("minimal", DiscordTemplate.minimalFallback());
            return resolved;
        }

        if (tplRoot == null) {
            resolved.put("default", DiscordTemplate.builder().id("default").build());
            resolved.put("minimal", DiscordTemplate.minimalFallback());
            return resolved;
        }

        for (String id : tplRoot.getKeys(false)) {
            resolveTemplateRecursive(id, tplRoot, resolved, new HashSet<>(), warnings);
        }
        if (!resolved.containsKey("minimal")) {
            resolved.put("minimal", DiscordTemplate.minimalFallback());
        }
        for (DiscordTemplate tpl : resolved.values()) {
            warnings.addAll(DiscordEmbedBuilder.validateTemplate(tpl));
        }
        return resolved;
    }

    private DiscordTemplate resolveTemplateRecursive(String id,
                                                     ConfigurationSection root,
                                                     Map<String, DiscordTemplate> resolved,
                                                     Set<String> visiting,
                                                     List<String> warnings) {
        if (resolved.containsKey(id)) {
            return resolved.get(id);
        }
        ConfigurationSection sec = root.getConfigurationSection(id);
        if (sec == null) {
            warnings.add("[discord.yml] Key 'templates." + id + "' must be a YAML section.");
            return DiscordTemplate.minimalFallback();
        }
        if (!visiting.add(id)) {
            warnings.add("[discord.yml] Circular template inheritance detected involving '" + id + "'. Fix: Remove circular 'extends' reference.");
            return DiscordTemplate.minimalFallback();
        }

        DiscordTemplate base = DiscordTemplate.builder().id(id).build();
        String parentId = sec.getString("extends", "");
        if (!parentId.isBlank()) {
            if (!root.isConfigurationSection(parentId)) {
                warnings.add("[discord.yml] Template '" + id + "' extends unknown template '" + parentId + "'. Using default base.");
            } else {
                base = resolveTemplateRecursive(parentId, root, resolved, visiting, warnings);
            }
        }
        visiting.remove(id);

        DiscordTemplate built = parseTemplateSection(id, sec, base, warnings);
        resolved.put(id, built);
        return built;
    }

    private DiscordTemplate parseTemplateSection(String id,
                                                 ConfigurationSection sec,
                                                 DiscordTemplate base,
                                                 List<String> warnings) {
        DiscordTemplate.DiscordTemplateBuilder b = base.toBuilder().id(id)
                .extendsTemplate(sec.getString("extends", ""));

        if (sec.contains("layout-mode")) {
            String raw = sec.getString("layout-mode", "LIST_FIELD").trim().toUpperCase();
            try {
                b.layoutMode(DiscordTemplate.LayoutMode.valueOf(raw));
            } catch (IllegalArgumentException ex) {
                warnings.add("[discord.yml] Key 'templates." + id + ".layout-mode' has invalid value '" + raw
                        + "'. Fix: Use LIST_FIELD, PER_ENTRY_FIELD, or PER_ENTRY_EMBED.");
            }
        }
        if (sec.contains("overflow-policy")) {
            String raw = sec.getString("overflow-policy", "SPLIT_MESSAGE").trim().toUpperCase();
            try {
                b.overflowPolicy(DiscordTemplate.OverflowPolicy.valueOf(raw));
            } catch (IllegalArgumentException ex) {
                warnings.add("[discord.yml] Key 'templates." + id + ".overflow-policy' has invalid value '" + raw
                        + "'. Fix: Use SPLIT_MESSAGE or TRUNCATE.");
            }
        }
        if (sec.contains("content")) b.content(sec.getString("content", ""));
        if (sec.contains("tts")) b.tts(sec.getBoolean("tts", false));
        if (sec.contains("suppress-embeds")) b.suppressEmbeds(sec.getBoolean("suppress-embeds", false));
        if (sec.contains("suppress-notifications")) b.suppressNotifications(sec.getBoolean("suppress-notifications", false));
        if (sec.contains("mention-roles")) b.mentionRoles(List.copyOf(sec.getStringList("mention-roles")));
        if (sec.contains("mention-users")) b.mentionUsers(List.copyOf(sec.getStringList("mention-users")));

        if (sec.isConfigurationSection("author")) {
            ConfigurationSection auth = sec.getConfigurationSection("author");
            if (auth.contains("name")) b.authorName(auth.getString("name", ""));
            if (auth.contains("url")) b.authorUrl(auth.getString("url", ""));
            if (auth.contains("icon-url")) b.authorIconUrl(auth.getString("icon-url", ""));
        }

        if (sec.contains("color")) {
            Integer c = parseColorValue(sec.getString("color"), "discord.yml", "templates." + id + ".color", warnings);
            if (c != null) b.color(c);
        }
        if (sec.isConfigurationSection("rank-colors")) {
            Map<String, Integer> rc = new LinkedHashMap<>(base.getRankColors());
            ConfigurationSection rcSec = sec.getConfigurationSection("rank-colors");
            for (String k : rcSec.getKeys(false)) {
                Integer c = parseColorValue(rcSec.getString(k), "discord.yml", "templates." + id + ".rank-colors." + k, warnings);
                if (c != null) rc.put(k, c);
            }
            b.rankColors(Map.copyOf(rc));
        }

        if (sec.contains("title")) b.title(sec.getString("title", ""));
        if (sec.contains("title-url")) b.titleUrl(sec.getString("title-url", ""));
        if (sec.contains("description")) b.description(sec.getString("description", ""));
        if (sec.contains("include-timestamp")) b.includeTimestamp(sec.getBoolean("include-timestamp", true));
        if (sec.contains("thumbnail-mode")) {
            String raw = sec.getString("thumbnail-mode", "TOP_1_PLAYER").trim().toUpperCase();
            try {
                b.thumbnailMode(DiscordTemplate.ThumbnailMode.valueOf(raw));
            } catch (IllegalArgumentException ex) {
                warnings.add("[discord.yml] Key 'templates." + id + ".thumbnail-mode' has invalid value '" + raw
                        + "'. Fix: Use NONE, TOP_1_PLAYER, or CUSTOM_URL.");
            }
        }
        if (sec.contains("thumbnail-url")) b.thumbnailUrl(sec.getString("thumbnail-url", ""));
        if (sec.contains("image-url")) b.imageUrl(sec.getString("image-url", ""));
        if (sec.contains("field-title")) b.fieldTitle(sec.getString("field-title", "Leaderboard"));
        if (sec.contains("inline-entry-fields")) b.inlineEntryFields(sec.getBoolean("inline-entry-fields", false));

        if (sec.isConfigurationSection("entry-formats")) {
            Map<String, String> formats = new LinkedHashMap<>(base.getEntryFormats());
            ConfigurationSection efSec = sec.getConfigurationSection("entry-formats");
            for (String k : efSec.getKeys(false)) {
                if ("default".equalsIgnoreCase(k)) {
                    b.defaultEntryFormat(efSec.getString(k));
                } else {
                    formats.put(k, efSec.getString(k));
                }
            }
            b.entryFormats(Map.copyOf(formats));
        } else if (sec.contains("entry-format")) {
            b.defaultEntryFormat(sec.getString("entry-format"));
        }

        if (sec.contains("skipped-format")) b.skippedFormat(sec.getString("skipped-format", ""));
        if (sec.contains("include-skipped")) b.includeSkipped(sec.getBoolean("include-skipped", true));
        if (sec.contains("empty-message")) b.emptyMessage(sec.getString("empty-message", ""));
        if (sec.contains("entry-embed-title")) b.entryEmbedTitle(sec.getString("entry-embed-title", ""));
        if (sec.contains("entry-embed-description")) b.entryEmbedDescription(sec.getString("entry-embed-description", ""));
        if (sec.contains("footer-text")) b.footerText(sec.getString("footer-text", ""));
        else if (sec.contains("footer")) b.footerText(sec.getString("footer", ""));
        if (sec.contains("footer-icon-url")) b.footerIconUrl(sec.getString("footer-icon-url", ""));

        if (sec.contains("custom-fields")) {
            List<DiscordTemplate.CustomField> cfs = new ArrayList<>();
            for (Map<?, ?> rawMap : sec.getMapList("custom-fields")) {
                String name = rawMap.get("name") != null ? String.valueOf(rawMap.get("name")) : "";
                String val = rawMap.get("value") != null ? String.valueOf(rawMap.get("value")) : "";
                boolean inline = rawMap.get("inline") instanceof Boolean bl && bl;
                cfs.add(DiscordTemplate.CustomField.builder().name(name).value(val).inline(inline).build());
            }
            b.customFields(List.copyOf(cfs));
        }

        if (sec.contains("link-buttons")) {
            List<DiscordTemplate.LinkButton> btns = new ArrayList<>();
            for (Map<?, ?> rawMap : sec.getMapList("link-buttons")) {
                String label = rawMap.get("label") != null ? String.valueOf(rawMap.get("label")) : "";
                String url = rawMap.get("url") != null ? String.valueOf(rawMap.get("url")) : "";
                String emoji = rawMap.get("emoji") != null ? String.valueOf(rawMap.get("emoji")) : "";
                btns.add(DiscordTemplate.LinkButton.builder().label(label).url(url).emoji(emoji).build());
            }
            b.linkButtons(List.copyOf(btns));
        }
        return b.build();
    }

    private Integer parseColorValue(String raw, String file, String key, List<String> warnings) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        Integer named = switch (s.toUpperCase()) {
            case "GOLD" -> 0xFFD700;
            case "RED" -> 0xE74C3C;
            case "GREEN" -> 0x2ECC71;
            case "BLUE" -> 0x3498DB;
            case "AQUA" -> 0x1ABC9C;
            case "YELLOW" -> 0xF1C40F;
            case "PURPLE" -> 0x9B59B6;
            case "ORANGE" -> 0xE67E22;
            case "PINK" -> 0xE91E63;
            case "WHITE" -> 0xFFFFFF;
            case "GRAY", "GREY" -> 0x95A5A6;
            case "BLACK" -> 0x000000;
            case "BLURPLE", "DISCORD_BLURPLE" -> 0x5865F2;
            default -> null;
        };
        if (named != null) {
            return named;
        }
        if (s.startsWith("#")) {
            try {
                return Integer.parseInt(s.substring(1), 16) & 0xFFFFFF;
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            return Integer.parseInt(s) & 0xFFFFFF;
        } catch (NumberFormatException ex) {
            warnings.add("[" + file + "] Key '" + key + "' has invalid color '" + raw
                    + "'. Fix: Use '#RRGGBB', a named color (GOLD, RED, GREEN, BLURPLE), or a decimal integer.");
            return null;
        }
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
        rawPodiums.set(base + ".discord.enabled", def.isDiscordEnabled());
        rawPodiums.set(base + ".discord.webhooks", def.getDiscordWebhooks());
        rawPodiums.set(base + ".discord.template", def.getDiscordTemplate());
        if (def.getDiscordColor() != null) {
            rawPodiums.set(base + ".discord.color", String.format("#%06X", def.getDiscordColor()));
        }
        rawPodiums.set(base + ".discord.thread-id", def.getDiscordThreadId());
        rawPodiums.set(base + ".discord.thread-name", def.getDiscordThreadName());
        rawPodiums.set(base + ".discord.applied-tags", def.getDiscordAppliedTags());
        if (def.getValueFormat() != null) {
            rawPodiums.set(base + ".value-format.grouping", def.getValueFormat().isGrouping());
            rawPodiums.set(base + ".value-format.decimals", def.getValueFormat().getDecimals());
            rawPodiums.set(base + ".value-format.compact", def.getValueFormat().isCompact());
            rawPodiums.set(base + ".value-format.prefix", def.getValueFormat().getPrefix());
            rawPodiums.set(base + ".value-format.suffix", def.getValueFormat().getSuffix());
            rawPodiums.set(base + ".value-format.keep-non-numeric", def.getValueFormat().isKeepNonNumeric());
            rawPodiums.set(base + ".value-format.fallback", def.getValueFormat().getFallback());
        }
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
        } else if (spec.getFrequency() == ScheduleSpec.Frequency.SPECIFIC_DATES) {
            rawSchedules.set(base + ".short-month-policy", spec.getShortMonthPolicy().name());
            rawSchedules.set(base + ".dates", spec.getDatePatterns().stream().map(DatePatternSpec::getRaw).toList());
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
