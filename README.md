# C.O.P.E. — Checker Of Player's Engine/Mods

C.O.P.E. is a Paper plugin prototype for conservative server-side anomaly alerts and limited client-reported signals.

> **Version: 0.2.0 — experimental.** This source has been reviewed and updated, but it has **not yet been compiled or tested on a live Paper 1.21.11 server in this environment**. Do not use alerts as automatic proof of cheating.

## Requirements

- Paper 1.21.11
- Java 21
- Gradle 8.10.2 or newer

## Build

```bash
gradle --no-daemon clean build
```

The JAR should be generated at `build/libs/COPE-0.2.0.jar`. The GitHub Actions workflow builds on pushes and pull requests. A successful build proves compilation only; it does not prove that checks are accurate in live gameplay.

## Install

1. Build the project.
2. Put the JAR in the server's `plugins/` directory.
3. Start Paper once to generate `plugins/COPE/config.yml`.
4. Review `config.yml`; sign translation checks are disabled by default.
5. Use `/cope reload` after editing the configuration.

## Commands

- `/cope help`
- `/cope status`
- `/cope check <player>` — shows the brand and plugin channels reported by the client, plus the current suspicion score.
- `/cope info <player>` — shows recent evidence and the current suspicion score.
- `/cope scan <player> [probe]` — manually starts a configured virtual-sign translation probe.
- `/cope alerts` — toggles staff alerts.
- `/cope mods allowed|blocked list`
- `/cope mods allowed|blocked add <id>`
- `/cope mods allowed|blocked remove <id>`
- `/cope reload`

Permission: `cope.admin` (default OP). Alert recipients need `cope.alerts` (default OP).

## Changes in 0.2.0

- Movement is sampled once per server tick instead of treating every `PlayerMoveEvent` as a tick. The check requires multiple consecutive outlier samples and grants grace after velocity changes and teleports.
- Movement exemptions cover common legitimate movement states such as flight, vehicles, gliding, swimming, climbing, water, riptiding, and levitation. Speed effects increase the allowance.
- Reach alerts require repeated outliers in a time window and include a configurable tolerance buffer.
- Added recent evidence history and `/cope info <player>`.
- Added configurable client-brand signals and exact blocked-channel matching.
- Fixed sign-probe cleanup to restore the original block data, not just its material. Probe responses are now reported as a **client signal**, not a cheat verdict or violation score.
- Violation decay is centralized and configurable; per-check cooldowns reduce alert spam.

## Important limitations

### Client mods

A normal Paper server cannot enumerate every mod installed on a player's client. Client brand strings and plugin channels are self-reported/incomplete signals. The `allowed-mods` and `blocked-mods` settings are retained as configuration placeholders for a future companion-client handshake; they are **not currently enforced as a verified mod inventory**. Do not claim a player has a specific installed mod based only on these settings.

### Sign translation

The probe uses Paper's experimental `Player.openVirtualSign` and `UncheckedSignChangeEvent` APIs. A resolved translation key is only a client-side signal. It is not proof of cheating and may be affected by language, resource packs, client changes, or version differences. The configured sample keys are examples, not a maintained signature database. The probe briefly opens a sign editor, so it is disabled by default and should only be used with player notice.

### Movement and combat

Movement and reach checks are conservative heuristics, not packet-level physics simulation. Server plugins, unusual terrain, knockback, latency, custom movement mechanics, and server performance can still cause false positives or missed detections. Test thresholds on a staging server before relying on alerts.

### AI and enforcement

This version does not contain a machine-learning model. It uses transparent rule-based checks. It does not automatically kick or ban players. Keep `alerts-only` enabled; automated punishments are intentionally not implemented.

## Testing still required

Before using this on Nexus SMP or another live server:

1. Confirm the GitHub Actions build succeeds with Java 21 and Paper 1.21.11 API.
2. Test movement with sprinting, speed effects, ice, knockback, slime, water, elytra, vehicles, and lag.
3. Test reach with ordinary clients at different ping levels and target movement.
4. Test sign probes with normal clients, multiple locales, and resource packs; verify the temporary client-side block always restores.
5. Verify commands, permissions, config reload, and alert toggles.

Treat all alerts as leads for staff review, not final judgments.

## License

No license has been added. All rights reserved by default unless a license is explicitly added.
