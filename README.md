<div align="center">

![RoyPodium Banner](https://i.ibb.co/Y4rVMdfq/watermark-removed-Gemini-Generated-Image-cjuwypcjuwypcjuw.png)

# 🏆 RoyPodium

**Automated, Timezone-Aware Leaderboard Rewards for Minecraft**

[![Version](https://img.shields.io/badge/Version-2026.1-gold?style=for-the-badge)]()
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21%20--%2026.3-brightgreen?style=for-the-badge)]()
[![Platforms](https://img.shields.io/badge/Platforms-Bukkit%20%7C%20Spigot%20%7C%20Paper%20%7C%20Purpur%20%7C%20Folia%20%7C%20Leaf-blue?style=for-the-badge)]()
[![Java](https://img.shields.io/badge/Java-21%2B-orange?style=for-the-badge)]()
[![Discord](https://img.shields.io/badge/Support-Discord-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/zNpkXGFQRS)

*Reward your top players automatically on any schedule using PlaceholderAPI — featuring offline reward queuing, snapshot value conditions, a full in-game GUI editor, rich Discord Webhooks, and TPS-safe execution.*

<p align="center">
  <a href="https://discord.gg/zNpkXGFQRS">
    <img src="https://discord.com/api/guilds/1363441005705297990/widget.png?style=banner2" alt="Join our Discord">
  </a>
</p>

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

- **Unlimited Leaderboards (`podiums.yml`):** Reward as many TOP leaderboards as you want using any PlaceholderAPI leaderboard expansion.
- **Flexible, DST-Safe Schedules (`schedules.yml`):** Run payouts `DAILY`, `WEEKLY`, `MONTHLY` (including `LAST` day of month), on an `INTERVAL`, or on exact/recurring `SPECIFIC_DATES` with full IANA timezone support.
- **Smart Online & Offline Delivery:** Online players receive rewards immediately; offline players have their rewards safely saved by UUID and delivered automatically when they log back in—even if they changed their username.
- **Direct Manual Queue Editing:** Add, edit, or remove pending offline rewards directly in `data/pending-rewards.yml` and apply changes with `/roypodium reload`.
- **Snapshot Value Conditions:** Require players to meet numeric or text thresholds (such as `>= 5`) at payout time without shifting other players' ranks.
- **Unified Color Engine:** Mix **MiniMessage**, **Hex (`&#RRGGBB`, `<#RRGGBB>`)**, and **Legacy (`&`, `§`)** in the same line across chat and GUI, with automatic protection against tag/color injection.
- **Unlimited Custom Aliases & Subcommands:** Register custom `/roypodium` aliases and custom subcommands in `config.yml`, applied immediately on reload.
- **100% Configurable In-Game GUI Editor:** Manage podiums, rank rewards, conditions, schedules, specific dates, offline queues, Discord toggles/templates, and settings in-game without touching YAML.
- **Rich Discord Webhook Notifications:** Send customizable Embeds, multi-embed/field layouts, Forum/Thread posts, Link Buttons, and formatted scores asynchronously.
- **TPS & MSPT Protection:** Tick-budgeted dispatch, incremental snapshot scanning, non-blocking async UUID lookups, and automatic TPS throttling.

---

## 🧩 Requirements & Compatibility

| Component | Requirement / Support |
| :--- | :--- |
| **Minecraft Versions** | `1.21` – `26.3` |
| **Server Software** | Bukkit, Spigot, Paper, Purpur, **Folia** (`folia-supported: true`), Leaf |
| **Java Version** | Java 21 or newer |
| **Soft Dependency** | **PlaceholderAPI** *(required for leaderboard lookups; if missing, RoyPodium logs a clear English warning and pauses payouts without crashing)* |

> [!NOTE]
> RoyPodium automatically adapts to **Folia** (`GlobalRegionScheduler`, `EntityScheduler`, and `AsyncScheduler`) as well as traditional **Bukkit/Spigot/Paper/Purpur/Leaf** servers.

---

## 🚀 Installation & Quick Start

1. Place `roypodium-2026.1.jar` inside your server's `plugins/` folder.
2. Make sure **PlaceholderAPI** and your leaderboard plugin (for example, AJLeaderboards) are installed.
3. Start your server to generate all configuration files inside `plugins/RoyPodium/`.
4. Configure your schedules and podiums—either in `schedules.yml` / `podiums.yml` or directly in-game via `/roypodium editor`.
5. Preview a payout safely with `/roypodium test <podium> simulate` (no commands are executed).
6. Apply any manual file edits anytime with `/roypodium reload`.

<!-- Screenshot Placeholder: In-game /roypodium editor main menu -->

---

## 📂 Configuration Overview

RoyPodium separates settings into clean, well-commented files inside `plugins/RoyPodium/`:

| File | What It Controls |
| :--- | :--- |
| `config.yml` | Default timezone, missed-schedule policy, tick-budget & TPS protection, queue expiration, custom aliases, and custom subcommands |
| `messages.yml` | Every chat message, prefix, help line, test output, queue message, and editor prompt |
| `schedules.yml` | Named schedules (timezone, frequency, start time, days, interval, or specific dates) |
| `podiums.yml` | Leaderboard definitions, PlaceholderAPI templates, conditions, tie policy, per-podium Discord settings, value formatting, and rank commands |
| `gui.yml` | All GUI titles, row counts, slots, materials, item names, lore, sounds, and labels |
| `discord.yml` | Global Discord toggle, named webhook profiles, named Embed templates, rank icons, and rate limits |
| `data/pending-rewards.yml` | Offline players' pending rewards keyed by UUID (supports direct Admin editing) |
| `data/state.yml` | Internal payout history used to prevent duplicate payouts across restarts and reloads |

> [!TIP]
> When updating RoyPodium, existing configurations automatically upgrade (`config-version`) by adding new options without erasing your existing podiums, schedules, or queued rewards.

---

## 🕒 Schedules & Timezones

Every schedule in `schedules.yml` uses an IANA timezone (such as `UTC`, `America/New_York`, `Europe/London`, or `Asia/Ho_Chi_Minh`) with automatic Daylight Saving Time (DST) handling.

| Frequency | Required Options | How It Works |
| :--- | :--- | :--- |
| `DAILY` | `time-start` (`HH:mm:ss`) | Runs once per day at the configured time |
| `WEEKLY` | `days` (`MONDAY`..`SUNDAY`), `time-start` | Runs on the chosen days of the week |
| `MONTHLY` | `day-of-month` (`1`..`31` or `LAST`), `time-start` | Runs on a specific day of the month (or `LAST` day) |
| `INTERVAL` | `interval` (`30m`, `1h`, `2h`), `time-start` | Repeats at a fixed interval anchored to `time-start` |
| `SPECIFIC_DATES` | `dates` (`YYYY-MM-DD HH:mm[:ss]`) | Runs on exact dates/times or recurring `*` wildcard patterns |

<details>
<summary>📅 Specific-Date Patterns &amp; Missed-Schedule Recovery</summary>

- **Wildcards in `SPECIFIC_DATES`:** Use `*` for any year, month, or day—for example, `2026-12-25 20:00:00` (one-time), `*-01-01 00:00:00` (annual), or `*-*-01 12:00:00` (monthly). You can list multiple dates and multiple times on the same day.
- **Short-Month Policy (`short-month-policy`):** When a recurring pattern targets day `29`, `30`, or `31` in a shorter month (like February), choose `SKIP_MONTH` (skip that month) or `LAST_DAY` (run on the last valid day of that month).
- **Missed-Schedule Policy (`missed-schedule-policy`):** If the server was offline during a scheduled payout, choose `SKIP` (ignore missed runs), `RUN_LATEST` (run the most recent missed payout once), or `RUN_ALL` (catch up up to `settings.max-catchup-runs`).

</details>

---

## 🏆 Creating Leaderboards (Podiums)

In `podiums.yml`, each podium links a schedule, PlaceholderAPI placeholders (using `{rank}` or `%rank%`), a `top-size`, a `tie-policy` (`ALLOW_TIES` or `SKIP_DUPLICATE_PLAYER`), optional `conditions`, and per-rank reward commands (supporting single ranks like `"1"` or ranges like `"4-10"`).

<details>
<summary>📋 Available Variables in Reward Commands</summary>

- `{player}` — Player's current username
- `{uuid}` — Player's UUID
- `{rank}` — Leaderboard rank (`1` .. `top-size`)
- `{podium}` — Podium ID
- `{podium_name}` — Display name with color codes stripped
- `{value}` — Score/value from `value-placeholder`
- `{date}` — Payout date (`yyyy-MM-dd`)
- `{time}` — Payout time (`HH:mm:ss`)

</details>

---

## 🎯 Reward Conditions

Add a `conditions` list to any podium in `podiums.yml` to require minimum or exact `value-placeholder` values:

- **Supported Operators:** `=`, `==`, `!=`, `>`, `<`, `>=`, `<=` (and `eq`, `ne`, `gt`, `lt`, `gte`, `lte`).
- **Snapshot Evaluation:** Conditions are checked once when the leaderboard snapshot is taken—never re-checked when an offline player joins later.
- **No Rank Shifting:** If rank #1 fails a condition, rank #1 is skipped and rank #2 stays at #2 (later players are not promoted).
- **Safe Number Handling:** Commas in formatted numbers (such as `1,250.50`) are handled cleanly, and non-numeric values fail numeric checks gracefully without errors.

---

## 📦 Offline Queue & Manual Editing

When a winning player is offline, their reward commands are saved atomically by UUID in `plugins/RoyPodium/data/pending-rewards.yml` and executed after `queue.join-delay-ticks` when they join (automatically updating their username if they changed it).

Admins can also **add, edit, or delete** entries directly in `data/pending-rewards.yml` and run `/roypodium reload`:
- Only the player's **UUID** and a non-empty **`commands`** list are required—all other fields (`last-known-name`, `podium-id`, `rank`, `value`, `payout-id`, `expires-at`) have safe automatic defaults.
- If a hand-edited entry has a typo or missing field, RoyPodium logs a clear English warning, skips only that entry, and **preserves it untouched** so your edits are never lost.

---

## 🔀 Custom Command Aliases

Add unlimited aliases for `/roypodium` under `command-aliases` in `config.yml`, and unlimited custom subcommands under `custom-subcommands`:
- Applied immediately on `/roypodium reload` without restarting the server.
- Full Tab-Completion and identical permissions.
- If an alias conflicts with another plugin's command, RoyPodium logs a clear warning and skips it safely without overwriting the other plugin.

---

## 🎨 Unified Color System

RoyPodium supports **MiniMessage**, **Hex (`&#RRGGBB`, `<#RRGGBB>`)**, and **Legacy (`&`, `§`)** mixed together in the same line across all messages, GUI titles, item names, lore, and test reports—working consistently on Bukkit, Spigot, Paper, Purpur, Folia, and Leaf. Player names and PlaceholderAPI outputs are automatically escaped so they cannot inject tags or color codes.

---

## 🖥️ In-Game GUI Editor

Run `/roypodium editor` to manage everything visually with TriumphGUI:
- **Podiums:** Toggle status, edit display name, placeholders, top size, schedule link, rank rewards, reward conditions, Discord toggle, and Discord template, or trigger a test/live payout.
- **Schedules:** Create/delete schedules, change timezone, cycle frequencies (including `SPECIFIC_DATES`), and manage specific date-time lists.
- **Offline Queue & Settings:** Inspect or clear pending player rewards, and adjust `commands-per-tick`, `missed-schedule-policy`, and `queue.expiration`.
- **Anti-Exploit & Reload Safe:** Blocks drag, shift-click, double-click, number-key swaps, offhand swaps, and item drops; verifies permissions on every interaction; safely closes open editors on reload.

<!-- Screenshot Placeholder: In-game Podium Edit & Schedule Dates GUI -->

---

## 🧪 Testing & Simulation

- `/roypodium test <podium|all> simulate` — Shows who is **ONLINE (Immediate)**, **OFFLINE (Queued)**, or **SKIPPED** (with the exact condition failure reason) without executing any commands.
- `/roypodium test <podium|all> execute` — Runs a real payout immediately using the same tick-budgeted pipeline as scheduled payouts.

---

## 💬 Discord Webhooks

Send rich, customizable **Discord** notifications after each payout (`discord.yml` & per-podium `podiums.yml`), including full support for named templates with inheritance, Embeds, Forum/Thread posts, Link Buttons, and display-only number formatting.

<!-- Screenshot Placeholder: Discord Embed notification showing Top Players -->

<details>
<summary>✨ Discord Capabilities, Value Formatting &amp; Security</summary>

- **Named Templates & Inheritance (`templates:` in `discord.yml`):** Create unlimited templates (`default`, `compact`, `detailed`, `showcase`, `minimal`) using `extends`, with 3 layout modes (`LIST_FIELD`, `PER_ENTRY_FIELD`, `PER_ENTRY_EMBED`), conditional `rank-colors`, and `SPLIT_MESSAGE` or `TRUNCATE` overflow handling.
- **Full Official Webhook Features:** Customize `content`, `tts`, message flags (`suppress-embeds`, `suppress-notifications`), `author`, `title` + `title-url`, `description`, `thumbnail`, `image`, `footer` + `footer-icon-url`, `inline` custom fields, Link Buttons (`link-buttons`), Thread/Forum targeting (`thread-id`, `thread-name`, `applied-tags`), and Discord dynamic timestamps (`{timestamp_relative}`, `{timestamp_full}`, etc.).
- **Display-Only Value Formatting (`value-format` in `podiums.yml`):** Format scores with `grouping`, `decimals`, `compact` (`K`/`M`/`B`/`T`), `prefix`/`suffix`, and `fallback` without altering reward conditions or commands.
- **Test & Validate Commands:** Preview with `/roypodium webhook preview <podium> [webhook] [template]`, send a test with `/roypodium webhook send <podium> [webhook] [template]`, or check Discord API limits with `/roypodium webhook validate [template|all]`.

</details>

> [!CAUTION]
> **Webhook Security:** Webhook URLs are treated as secrets—never logged or shown in chat, GUI, or bStats. Only `https://` URLs on official Discord domains are accepted, internal/private IPs are blocked (anti-SSRF), Minecraft colors are stripped, and untrusted mentions (`@everyone`, `@here`, `<@...>`) are neutralized.

---

## ⚡ Performance & Anti-Lag

RoyPodium protects server TPS and MSPT during both scheduled payouts and admin tests:

- **Tick-Budgeted Dispatch & Snapshots:** `dispatch.commands-per-tick` (default `5`), `performance.snapshot-ranks-per-tick` (default `20`), and `performance.tick-budget-ms` (default `12.0` ms) spread work across ticks.
- **TPS-Aware Auto-Throttling:** `performance.min-tps` (default `18.0`) automatically scales down per-tick work if server TPS drops.
- **Non-Blocking Offline UUIDs:** `performance.async-uuid-lookup` (default `true`) avoids blocking Mojang HTTP calls on the Main/Region Thread.
- **Coalesced Async Storage & Off-Thread Webhooks:** Queue YAML serialization and Discord Embed building + HTTP requests run 100% off the Main/Region Thread, with overlap protection for test commands.

---

## 📊 bStats Metrics

RoyPodium uses **[bStats](https://bstats.org/plugin/bukkit/RoyPodium/35686)** (Plugin ID: **`35686`**) to collect anonymous, aggregate usage statistics to help guide compatibility across Minecraft versions and server software.

<div align="center">

[![bStats Metrics](https://bstats.org/signatures/bukkit/RoyPodium.svg)](https://bstats.org/plugin/bukkit/RoyPodium/35686)

*View public charts at [bstats.org/plugin/bukkit/RoyPodium/35686](https://bstats.org/plugin/bukkit/RoyPodium/35686).*  
*(Note: Charts populate automatically after servers running RoyPodium begin reporting data.)*

</div>

<details>
<summary>📈 Collected Anonymous Custom Charts &amp; Opt-Out</summary>

| Chart ID | Description | Reported Values |
| :--- | :--- | :--- |
| `podium_count_bucket` | Number of configured leaderboards | `0`, `1-3`, `4-10`, `11+` |
| `schedule_count_bucket` | Number of configured schedules | `0`, `1-3`, `4-10`, `11+` |
| `uses_conditions` | Whether any podium uses value conditions | `Yes`, `No` |
| `uses_custom_aliases` | Whether custom `command-aliases` are configured | `Yes`, `No` |
| `missed_schedule_policy` | Active missed-schedule recovery policy | `SKIP`, `RUN_LATEST`, `RUN_ALL` |
| `placeholderapi_present` | Whether PlaceholderAPI is installed | `Yes`, `No` |
| `server_scheduler_mode` | Active scheduler backend | `Folia`, `Bukkit/Paper` |

> **Privacy Commitment:** RoyPodium **never** collects player names, UUIDs, reward commands, podium names, IP addresses, or server file paths.  
> **How to Opt Out:** Set `enabled: false` in `plugins/bStats/config.yml` and restart your server. RoyPodium strictly respects this setting.

</details>

---

## 📜 Commands & Permissions

Main command: `/roypodium` (built-in alias: `/podium`, plus any custom `command-aliases` in `config.yml`).

| Command | Description | Permission | Default |
| :--- | :--- | :--- | :--- |
| `/roypodium help` | Show available commands | `roypodium.use` | `true` |
| `/roypodium status` | View PlaceholderAPI, podiums, schedules, and queue status | `roypodium.status` | `op` |
| `/roypodium editor` | Open the in-game TriumphGUI editor | `roypodium.editor` | `op` |
| `/roypodium test <podium\|all> [simulate\|execute]` | Simulate or execute a podium payout immediately | `roypodium.test` | `op` |
| `/roypodium webhook <preview\|send> <podium> [webhook] [template]` | Preview or send a test Discord webhook | `roypodium.webhook` | `op` |
| `/roypodium webhook validate [template\|all]` | Validate Discord templates against API limits | `roypodium.webhook` | `op` |
| `/roypodium queue list [page]` | List players with pending offline rewards | `roypodium.queue` | `op` |
| `/roypodium queue inspect <player\|uuid>` | Inspect pending rewards for a player | `roypodium.queue` | `op` |
| `/roypodium queue clear <player\|uuid\|all>` | Clear pending rewards | `roypodium.queue` | `op` |
| `/roypodium reload` | Reload configs, aliases, and manual queue edits | `roypodium.reload` | `op` |
| *All admin permissions* | Grants `reload`, `test`, `queue`, `editor`, `status`, `webhook` | `roypodium.admin` | `op` |

---

## ❓ FAQ & Troubleshooting

<details>
<summary><b>Why are payouts paused with a PlaceholderAPI warning?</b></summary>

`PlaceholderAPI` is a soft-dependency. If it is not installed or enabled, RoyPodium logs a clear English error and pauses scheduled payouts so the server never crashes. Install PlaceholderAPI and restart or reload.
</details>

<details>
<summary><b>Why was my custom alias skipped on reload?</b></summary>

Check the console: if another plugin already owns that command name, or if the alias contains spaces, `/`, or `:`, RoyPodium skips it safely and logs the exact reason.
</details>

<details>
<summary><b>Why did a player get skipped in simulation?</b></summary>

If `conditions` are configured on that podium, `/roypodium test <podium> simulate` displays `SKIPPED` along with the exact reason (such as failing a threshold or returning a non-numeric value).
</details>

---

## 📝 Update Changelog

### `2026.1`
- **Optimized** test and payout performance: non-blocking cached UUID lookups with async fallback, tick-budgeted incremental snapshot scanning, TPS-aware throttling, 100% async coalesced queue YAML serialization, off-thread Discord payload building, and test overlap protection.
- **Expanded** **Discord Webhooks** with named `templates` (`extends` inheritance), `LIST_FIELD` / `PER_ENTRY_FIELD` / `PER_ENTRY_EMBED` layouts, `content`, `tts`, `flags`, `thread-id` / `thread-name` / `applied-tags`, Link Buttons, `author`/`image`/`footer-icon-url`/`inline` fields, conditional `rank-colors`, dynamic `<t:...>` timestamps, `/roypodium webhook validate`, and GUI template selector.
- **Added** **Discord Webhook** payout notifications (`discord.yml`, per-podium `discord` & `value-format`), official Discord Embed limit splitting, SSRF/URL-masking/mention-escape security, async rate-limit handling, `/roypodium webhook <preview|send>`, and GUI toggle/test buttons.
- **Added** `SPECIFIC_DATES` schedule frequency (`YYYY-MM-DD HH:mm:ss` + `*` wildcards, `short-month-policy`, DST-safe evaluation, and GUI date editor).
- **Added** anonymous **bStats** metrics integration (Plugin ID `35686`).
- **Added** unlimited `command-aliases`, unified MiniMessage + Hex + Legacy color engine with anti-injection escaping, manual `data/pending-rewards.yml` editing, `conditions` in `podiums.yml`, and automatic non-destructive config migration.
- **Fixed** TriumphGUI item metadata serialization on Leaf/Paper `1.21.11+` and PlugManX reload alias cleanup.

---

<div align="center">

💬 **Need help or have suggestions?** Join our [Discord Server](https://discord.gg/zNpkXGFQRS)!  
⭐ **Enjoying RoyPodium?** Consider leaving a **5-star review** — your feedback helps support future improvements!

</div>
