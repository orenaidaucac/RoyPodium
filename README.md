<div align="center">

# 🏆 RoyPodium

**Automated, Timezone-Aware Leaderboard Rewards for Minecraft**

[![Version](https://img.shields.io/badge/Version-2026.1-gold?style=for-the-badge)]()
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21%20--%2026.3-brightgreen?style=for-the-badge)]()
[![Platforms](https://img.shields.io/badge/Platforms-Bukkit%20%7C%20Spigot%20%7C%20Paper%20%7C%20Purpur%20%7C%20Folia%20%7C%20Leaf-blue?style=for-the-badge)]()
[![Java](https://img.shields.io/badge/Java-21%2B-orange?style=for-the-badge)]()

*Reward top players automatically on any schedule using PlaceholderAPI — with offline queuing, tick-budgeted command execution, value conditions, MiniMessage formatting, and a full in-game GUI editor.*

</div>

---

## 📖 Table of Contents

- [✨ Key Features](#-key-features)
- [🧩 Requirements & Compatibility](#-requirements--compatibility)
- [🚀 Installation & Quick Start](#-installation--quick-start)
- [📂 Configuration Overview](#-configuration-overview)
- [🕒 Schedules & Timezones](#-schedules--timezones)
- [🏆 Creating Leaderboards (Podiums)](#-creating-leaderboards-podiums)
- [🎯 Reward Conditions](#-reward-conditions)
- [📦 Offline Queue & Manual Editing](#-offline-queue--manual-editing)
- [🔀 Custom Command Aliases](#-custom-command-aliases)
- [🎨 Unified Color System](#-unified-color-system)
- [🖥️ In-Game GUI Editor](#️-in-game-gui-editor)
- [🧪 Testing & Simulation](#-testing--simulation)
- [💬 Discord Webhooks](#-discord-webhooks)
- [⚡ Performance & Anti-Lag](#-performance--anti-lag)
- [📊 bStats Metrics](#-bstats-metrics)
- [📜 Commands & Permissions](#-commands--permissions)
- [❓ FAQ & Troubleshooting](#-faq--troubleshooting)
- [📝 Update Changelog](#-update-changelog)

---

## ✨ Key Features

- **Unlimited Leaderboards (`podiums.yml`):** Create as many TOP leaderboards as you want using any PlaceholderAPI leaderboard expansion (e.g., kills, playtime, balance, island level).
- **Timezone & DST-Safe Scheduling (`schedules.yml`):** Run payouts `DAILY`, `WEEKLY`, `MONTHLY` (including `LAST` day of month), or on an `INTERVAL` with full IANA timezone and Daylight Saving Time support.
- **Smart Online & Offline Delivery:** Online players receive rewards immediately; offline players have their rewards safely stored by UUID in `data/pending-rewards.yml` and delivered automatically when they log back in (even if they changed their username).
- **Manual Queue Editing:** Admins can add, edit, or remove pending offline rewards directly in `data/pending-rewards.yml` and apply changes with `/roypodium reload`.
- **Snapshot Value Conditions:** Require players to meet numeric or text thresholds (e.g., `>= 5` kills) at payout time—players who fail are skipped without shifting other players' ranks.
- **Unified Color Engine:** Mix **MiniMessage**, **Hex (`&#RRGGBB`, `<#RRGGBB>`)**, and **Legacy (`&`, `§`)** in the same line across chat, GUI, and test output—with built-in protection against MiniMessage/color injection from player names or PlaceholderAPI values.
- **Unlimited Custom Aliases & Subcommands:** Add your own `/roypodium` aliases and custom informational/command subcommands right in `config.yml`, hot-reloaded without restarting.
- **100% Configurable In-Game GUI Editor:** Edit podiums, rank rewards, conditions, schedules, offline queues, and global settings in-game without touching YAML.
- **Tick-Budgeted Command Dispatcher:** Spreads large reward bursts across multiple server ticks so payouts never cause TPS spikes.

---

## 🧩 Requirements & Compatibility

| Component | Requirement / Support |
| :--- | :--- |
| **Minecraft Versions** | `1.21` – `26.3` |
| **Server Software** | Bukkit, Spigot, Paper, Purpur, **Folia** (`folia-supported: true`), Leaf |
| **Java Version** | Java 21 or newer |
| **Soft Dependency** | **PlaceholderAPI** *(required for leaderboard lookups; if missing, RoyPodium logs a clear warning and pauses payouts without crashing)* |

> [!NOTE]
> RoyPodium uses a dedicated Folia-safe scheduler (`GlobalRegionScheduler`, `EntityScheduler`, and `AsyncScheduler` on Folia, falling back to `BukkitScheduler` on non-Folia servers).

---

## 🚀 Installation & Quick Start

1. Download `roypodium-2026.1.jar` and place it in your server's `plugins/` folder.
2. Ensure **PlaceholderAPI** (and your leaderboard expansion, such as AJLeaderboards) is installed in `plugins/`.
3. Start or restart your server to generate all default configuration files in `plugins/RoyPodium/`.
4. Open `plugins/RoyPodium/schedules.yml` and `plugins/RoyPodium/podiums.yml`—or run `/roypodium editor` in-game!
5. Run `/roypodium test <podium> simulate` to preview who would win without executing any commands.
6. Run `/roypodium reload` whenever you edit YAML files.

<!-- Screenshot Placeholder: In-game /roypodium editor main menu -->

---

## 📂 Configuration Overview

RoyPodium splits its configuration into clean, purpose-specific files inside `plugins/RoyPodium/`:

| File | Purpose |
| :--- | :--- |
| `config.yml` | Global settings, default timezone, missed-schedule policy, tick budget, queue expiration, custom aliases, and custom subcommands |
| `messages.yml` | All chat messages, prefixes, simulation output, queue messages, and editor prompts |
| `schedules.yml` | Named schedules (timezone, frequency, start time, days, interval) |
| `podiums.yml` | Leaderboard definitions, PlaceholderAPI templates, conditions, tie policy, and per-rank reward commands |
| `gui.yml` | Every GUI title, row count, slot, material, item name, lore, sound, and label |
| `discord.yml` | Global Discord webhook settings, named webhook profiles, embed templates, and rate limits |
| `data/pending-rewards.yml` | Pending rewards for offline players (keyed by UUID; supports manual Admin editing) |
| `data/state.yml` | Internal tracking of last executed timestamps and payout IDs to prevent duplicate payouts |

> [!TIP]
> When updating RoyPodium, existing configurations automatically migrate (`config-version`) by adding new keys without deleting your existing podiums, schedules, or pending rewards.

---

## 🕒 Schedules & Timezones

Each schedule in `schedules.yml` uses an IANA timezone ID (such as `UTC`, `America/New_York`, `Europe/London`, or `Asia/Ho_Chi_Minh`) and one of four frequencies:

| Frequency | Required Keys | Example |
| :--- | :--- | :--- |
| `DAILY` | `time-start` (`HH:mm:ss`) | Runs every day at `00:00:00` |
| `WEEKLY` | `days` (`MONDAY`..`SUNDAY`), `time-start` | Runs every `SUNDAY` at `20:00:00` |
| `MONTHLY` | `day-of-month` (`1`..`31` or `LAST`), `time-start` | Runs on the `LAST` day of each month |
| `INTERVAL` | `interval` (`30m`, `1h`, `2h`), `time-start` | Runs every `1h` anchored to `time-start` |
| `SPECIFIC_DATES` | `dates` (`YYYY-MM-DD HH:mm[:ss]`, supports `*` wildcards) | Runs on exact dates/times or recurring patterns |

#### Specific-Date & Recurring Patterns (`SPECIFIC_DATES`)
Use `SPECIFIC_DATES` for holiday events, one-time payouts, or custom recurring dates—with multiple date-time entries per schedule:

```yaml
  special_events:
    timezone: "UTC"
    frequency: "SPECIFIC_DATES"
    short-month-policy: "SKIP_MONTH" # or LAST_DAY for days 29-31 in shorter months
    dates:
      - "2026-12-25 20:00:00"        # Exact one-time date & time
      - "*-01-01 00:00:00"           # Every year on Jan 1 at midnight
      - "*-*-01 12:00:00"            # 1st of every month at noon
```

### Missed Schedule Policy
If your server was offline when a payout was due, `missed-schedule-policy` (`config.yml` or per-schedule `missed-policy`) controls recovery:
- `SKIP` — Ignore missed payouts and wait for the next scheduled time.
- `RUN_LATEST` — Execute the most recent missed payout once on startup.
- `RUN_ALL` — Catch up missed payouts up to `settings.max-catchup-runs`.

---

## 🏆 Creating Leaderboards (Podiums)

In `podiums.yml`, define any number of podiums under `podiums:`:

```yaml
podiums:
  kills_daily:
    enabled: true
    display-name: "&c&lDaily Top Kills"
    schedule: "daily_midnight"
    player-placeholder: "%ajlb_lb_statistic_player_kills_{rank}_daily_name%"
    value-placeholder: "%ajlb_lb_statistic_player_kills_{rank}_daily_value%"
    top-size: 5
    tie-policy: "ALLOW_TIES"
    conditions:
      - ">= 1"
    ranks:
      "1":
        - "eco give {player} 5000"
      "2-3":
        - "eco give {player} 2000"
```

<details>
<summary>📋 Available Placeholders in Reward Commands &amp; Tie Policies</summary>

**Command Variables:**
- `{player}` — Player's current username
- `{uuid}` — Player's UUID
- `{rank}` — Rank number (`1` .. `top-size`)
- `{podium}` — Podium ID
- `{podium_name}` — Display name with color codes stripped
- `{value}` — Score/value from `value-placeholder`
- `{date}` — Payout date (`yyyy-MM-dd`)
- `{time}` — Payout time (`HH:mm:ss`)

**Tie Policies (`tie-policy`):**
- `ALLOW_TIES` — Rewards every rank slot independently.
- `SKIP_DUPLICATE_PLAYER` — Ensures each player UUID can only be rewarded once per payout.

</details>

---

## 🎯 Reward Conditions

Podiums can require players to meet one or more conditions based on `value-placeholder`:

- **Supported Operators:** `=`, `==`, `!=`, `>`, `<`, `>=`, `<=` (and word aliases `eq`, `ne`, `gt`, `lt`, `gte`, `lte`).
- **Multiple Conditions:** All conditions listed under `conditions:` must pass.
- **No Rank Shifting:** If rank #1 fails a condition, rank #1 is skipped—rank #2 remains rank #2 and is **not** promoted.
- **Snapshot Evaluation:** Conditions are evaluated once when the leaderboard snapshot is captured, never re-evaluated when an offline player logs in later.
- **Safe Parsing:** Commas in formatted numbers (e.g., `1,250.50`) are handled cleanly; non-numeric or empty values fail numeric conditions gracefully without errors.

---

## 📦 Offline Queue & Manual Editing

When a winning player is offline, their rewards are saved atomically to `plugins/RoyPodium/data/pending-rewards.yml` and delivered after `queue.join-delay-ticks` when they join.

Admins can also **add, edit, or delete** entries directly in `data/pending-rewards.yml` and run `/roypodium reload`:

```yaml
players:
  069a79f4-44e9-4726-a5be-fca90e38aaf5:
    entries:
      bonus_gift:
        commands:
          - "eco give Steve 1000"
        last-known-name: "Steve"
```

> [!IMPORTANT]
> - Only `commands` (a non-empty list) and a valid player UUID key are required for manual entries—all other fields (`last-known-name`, `podium-id`, `rank`, `value`, `payout-id`, `expires-at`) have safe automatic defaults.
> - If a manual entry has a typo or missing field, RoyPodium logs a clear English warning explaining how to fix it, skips only that entry, and **preserves it untouched** in the file so your edits are never lost.

---

## 🔀 Custom Command Aliases

Add unlimited aliases for `/roypodium` in `config.yml`:

```yaml
command-aliases:
  - "toprewards"
  - "lbpodium"
```

- Applied immediately on `/roypodium reload` with full Tab-Completion and identical permissions.
- If an alias conflicts with a command owned by another plugin, RoyPodium logs a warning and skips it safely without overwriting the other plugin's command.

---

## 🎨 Unified Color System

RoyPodium supports **MiniMessage**, **Hex**, and **Legacy** codes **mixed together in the same line** across all messages, GUI titles, item names, lore, and test reports:

```yaml
prefix: "<gradient:#ffaa00:#ff5500><bold>RoyPodium</bold></gradient> &8» &#e0e0e0"
```

- Works identically on **Bukkit, Spigot, Paper, Purpur, Folia, and Leaf**.
- Player names and PlaceholderAPI values are automatically escaped so untrusted inputs cannot inject MiniMessage tags or legacy color codes.

---

## 🖥️ In-Game GUI Editor

Open `/roypodium editor` to manage everything visually using TriumphGUI:
- **Manage Podiums:** Toggle enabled/disabled, edit display name, placeholders, top size, schedule link, rank rewards, and **value conditions**, or run a live payout.
- **Manage Schedules:** Create/delete schedules, edit timezone, cycle frequency, and set start time.
- **Offline Reward Queue:** View queued players and shift-right-click to clear individual or all queues.
- **Global Settings:** Adjust `commands-per-tick`, `missed-schedule-policy`, and `queue.expiration`.
- **Anti-Exploit & Reload Safe:** Blocks drag, shift-click, double-click, number keys, offhand swap, and item drops; verifies permissions on every interaction; safely closes all open editors on `/roypodium reload`.

<!-- Screenshot Placeholder: Podium Edit & Condition Edit GUI screens -->

---

## 🧪 Testing & Simulation

Preview any podium without executing real commands or modifying queues:

- `/roypodium test <podium|all> simulate` — Captures a live snapshot, shows who is **ONLINE (Immediate)**, **OFFLINE (Queued)**, or **SKIPPED** (with the exact condition failure reason).
- `/roypodium test <podium|all> execute` — Runs a real payout immediately.

---

## 💬 Discord Webhooks

Send rich, customizable **Discord Embed** notifications automatically after each scheduled payout—listing winners, formatted scores, delivery status (`Delivered`, `Queued (Offline)`, or `Skipped`), rank medals, and optional player head thumbnails.

<!-- Screenshot Placeholder: Discord Embed notification showing Top Players -->

### Key Capabilities
- **Multiple Webhooks & Per-Podium Control (`discord.yml` & `podiums.yml`):** Enable/disable globally or per podium, use a default webhook, or route specific podiums to different Discord channels.
- **Display-Only Value Formatting (`value-format` in `podiums.yml`):** Format raw PlaceholderAPI numbers with thousands separators (`grouping`), fixed decimal places (`decimals`), compact notation (`compact`: `K`/`M`/`B`/`T`), `prefix`/`suffix`, and safe non-numeric fallback—without affecting reward conditions or commands.
- **Zero Impact on Payouts:** Webhooks are dispatched on a dedicated asynchronous background queue with HTTP `429` rate-limit handling. Network or Discord errors never delay or cancel player rewards.
- **Test & Preview Command:**
  - `/roypodium webhook preview <podium> [webhook]` — Preview the rendered Discord message in chat without sending it.
  - `/roypodium webhook send <podium> [webhook]` — Send a live test embed (with anti-spam cooldown) without awarding any rewards.

> [!CAUTION]
> **Webhook URL Security:** Never share your Discord Webhook URL. RoyPodium validates that URLs use `https://` on official Discord domains, blocks internal/private IPs (anti-SSRF), masks URLs in all logs and error messages, strips Minecraft color codes, and neutralizes `@everyone`/`@here`/user mentions.

---

## ⚡ Performance & Anti-Lag

- **Tick-Budgeted Dispatch:** `dispatch.commands-per-tick` (default `5`) and `dispatch.interval-ticks` (default `1`) spread large console command bursts across ticks to protect server TPS.
- **Atomic Async Storage:** Queue and state files are written asynchronously to temporary files and atomically moved to prevent file corruption on crash without blocking the main thread.

---

## 📊 bStats Metrics

RoyPodium uses **[bStats](https://bstats.org/plugin/bukkit/RoyPodium/35686)** (Plugin ID: **`35686`**) to collect anonymous, aggregate usage statistics that help guide compatibility and feature improvements across Minecraft versions and server platforms.

<div align="center">

[![bStats Metrics](https://bstats.org/signatures/bukkit/RoyPodium.svg)](https://bstats.org/plugin/bukkit/RoyPodium/35686)

*View live public charts at [bstats.org/plugin/bukkit/RoyPodium/35686](https://bstats.org/plugin/bukkit/RoyPodium/35686).*
*(Note: Charts will populate automatically once servers running RoyPodium begin reporting data.)*

</div>

### Collected Custom Charts
| Chart ID | Description | Reported Values |
| :--- | :--- | :--- |
| `podium_count_bucket` | Number of configured leaderboards | `0`, `1-3`, `4-10`, `11+` |
| `schedule_count_bucket` | Number of configured schedules | `0`, `1-3`, `4-10`, `11+` |
| `uses_conditions` | Whether any podium uses value conditions | `Yes`, `No` |
| `uses_custom_aliases` | Whether custom `command-aliases` are configured | `Yes`, `No` |
| `missed_schedule_policy` | Active missed-schedule recovery policy | `SKIP`, `RUN_LATEST`, `RUN_ALL` |
| `placeholderapi_present` | Whether PlaceholderAPI is installed on the server | `Yes`, `No` |
| `server_scheduler_mode` | Active scheduler backend | `Folia`, `Bukkit/Paper` |

> [!NOTE]
> **Privacy Commitment:** RoyPodium **never** collects player names, UUIDs, reward commands, podium names, IP addresses, or server file paths. Only anonymous, low-cardinality aggregate totals are sent.
>
> **How to Opt Out:** You can disable bStats globally at any time by setting `enabled: false` in `plugins/bStats/config.yml` and restarting your server. RoyPodium strictly respects this setting.

---

## 📜 Commands & Permissions

Main command: `/roypodium` (built-in alias: `/podium`, plus any custom `command-aliases` in `config.yml`).

| Command | Description | Permission | Default |
| :--- | :--- | :--- | :--- |
| `/roypodium help` | Show available commands | `roypodium.use` | `true` |
| `/roypodium status` | View PlaceholderAPI, podiums, schedules, and queue status | `roypodium.status` | `op` |
| `/roypodium editor` | Open the in-game TriumphGUI editor | `roypodium.editor` | `op` |
| `/roypodium test <podium\|all> [simulate\|execute]` | Simulate or execute a podium payout immediately | `roypodium.test` | `op` |
| `/roypodium webhook <preview\|send> <podium> [webhook]` | Preview or send a test Discord webhook | `roypodium.webhook` | `op` |
| `/roypodium queue list [page]` | List players with pending offline rewards | `roypodium.queue` | `op` |
| `/roypodium queue inspect <player\|uuid>` | Inspect pending rewards for a player | `roypodium.queue` | `op` |
| `/roypodium queue clear <player\|uuid\|all>` | Clear pending rewards | `roypodium.queue` | `op` |
| `/roypodium reload` | Reload configs, aliases, and manual queue edits | `roypodium.reload` | `op` |
| *All admin permissions* | Grants `reload`, `test`, `queue`, `editor`, `status` | `roypodium.admin` | `op` |

---

## ❓ FAQ & Troubleshooting

<details>
<summary><b>Why are payouts paused with a PlaceholderAPI warning?</b></summary>

`PlaceholderAPI` is a soft-dependency. If it is not installed or enabled, RoyPodium logs a clear English error and pauses scheduled payouts so the server never crashes. Install PlaceholderAPI and restart/reload.
</details>

<details>
<summary><b>Why was my custom alias skipped on reload?</b></summary>

Check the console: if another plugin already owns that command name, or if the alias contains spaces, `/`, or `:`, RoyPodium skips it safely and logs the exact reason.
</details>

<details>
<summary><b>Why did a player get skipped in simulation?</b></summary>

If `conditions:` are configured on that podium, `/roypodium test <podium> simulate` displays `SKIPPED` along with the exact reason (e.g., `Failed condition '>= 5' (value: 3)` or `Non-numeric value 'N/A'`).
</details>

---

## 📝 Update Changelog

### Latest Update (`2026.1`)
- **Added** **Discord Webhook** payout notifications (`discord.yml`, per-podium `discord` & `value-format` in `podiums.yml`), official Discord Embed limit splitting, SSRF/URL-masking/mention-escape security, async rate-limit handling, `/roypodium webhook <preview|send>`, and GUI toggle/test buttons.
- **Added** `SPECIFIC_DATES` schedule frequency in `schedules.yml` supporting exact date-times (`YYYY-MM-DD HH:mm:ss`), `*` wildcards for recurring dates, multiple times per day, `short-month-policy` (`SKIP_MONTH` / `LAST_DAY`), DST-safe evaluation, and an in-game GUI date editor (`schedule-dates`).
- **Added** anonymous **bStats** metrics integration (Plugin ID `35686`, relocated & Folia-safe) with 7 low-cardinality custom charts and full opt-out support.
- **Added** unlimited dynamic `command-aliases` in `config.yml` with conflict protection, permission/tab-complete parity, and hot-reload support.
- **Added** unified MiniMessage + Hex + Legacy color engine across all messages, GUI text, and test output, with anti-injection escaping for player names and PlaceholderAPI values.
- **Added** direct manual editing support for `data/pending-rewards.yml` with English documentation, friendly defaults, reload support, and preservation of malformed entries.
- **Added** `conditions` support in `podiums.yml` (`=`, `!=`, `>`, `<`, `>=`, `<=` and aliases), snapshot-time evaluation without rank shifting, simulation failure reporting, and in-game GUI condition editor.
- **Added** automatic non-destructive configuration migration (`config-version: 2`).
- **Fixed** TriumphGUI item metadata serialization on Leaf/Paper `1.21.11+` by building item display names and lore via standard Bukkit `ItemMeta`.
