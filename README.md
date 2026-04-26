# Chrono Mod

A server-side Minecraft Fabric mod that implements a time quota system. Players receive limited playtime that burns
while they're online, encouraging strategic play and creating a fair, time-limited gaming experience.

## Features

### ⏰ Time Quota System
- **Initial Quota**: New players start with a configurable amount of playtime (default: 8 hours)
- **Time Burn**: Quota decreases in real-time while online (1 second per second)
- **Automatic Kick**: Players are kicked when their quota reaches zero

### 📅 Periodic Allotment
- Players receive additional playtime at regular intervals (default: 8 hours every 7 days)
- Granted automatically on login after the configured period has elapsed
- Players are notified on login when their next allotment is due, using a human-readable countdown like "2 days, 4 hours, 30 minutes"
- Prevents stockpiling - only active players benefit

### ⚔️ PvP Quota Transfer
- When a player kills another player, quota is transferred to the killer (default: 1 hour)
- Creates strategic gameplay decisions
- Transfers only available quota (victim can't go negative)

### 🎁 Voluntary Quota Transfer
- Players can voluntarily transfer quota to others using `/chrono transfer`
- Useful for helping friends or coordinating team play
- Prevents self-transfers and negative amounts
- Requires at least 1 minute transfer (no exploits)

### 💾 Persistent Storage
- Player quotas automatically saved every 5 minutes
- Manual save on player disconnect and server shutdown
- Survives server restarts and crashes
- Stored in `config/chrono-mod/player-data.json`

### 🏆 Advancement Rewards
- Completing advancements grants bonus quota time
- Three tiers based on advancement type (configurable):
  - **Task**: +15 minutes (default)
  - **Goal**: +30 minutes (default)
  - **Challenge**: +1 hour (default)
- Root and silent advancements (e.g. recipe unlocks) are excluded
- Each advancement can only grant time once per session

### 🎊 Happy Hour Events
- **Admin-Initiated Time Windows**: Server admins can activate happy hours using commands
- **No Quota Burn**: Players' quota doesn't burn during happy hour
- **Enhanced PvP Transfers**: PvP kills transfer multiplied quota (default: 2x, configurable)
- **Live Countdown**: All players see persistent countdown via action bar: `▶ Happy Hour: 4:23 remaining ▓▓▓▓▓▒░░░░`
- **Smooth Updates**: Countdown updates every 1 second with smooth visual progress (not 10-second intervals)
- **Visual Progress Bar**: Unicode block characters (▓ = remaining, ░ = expired) drain visually as time passes
- **Urgency Indicator**: Countdown turns red (§c) with 5 seconds or less remaining to alert players
- **Fullscreen Announcement**: Title + subtitle broadcast to all players when happy hour starts
- **Late Join Support**: Players joining during happy hour automatically added to active countdown display
- **Single Happy Hour**: Starting a new happy hour replaces any existing one

### 🔄 Offline Player Support
- **Persistent Notifications**: Messages queued for offline players are persisted and survive server restarts
- **Username Tracking**: Player usernames are stored and updated on every login, enabling name-based commands
- **Offline Transfers**: Admins and players can transfer quota to offline players by name
- **Offline Messages**: Queued messages are delivered as chat when the player logs back in
- **Revive System**: Depleted players (0 quota) show a special revive message when brought back
- **Offline Admin Tools**: Admins can add/remove time from offline players with automatic notifications
- **List Display**: `/chrono list` displays offline players by stored username instead of UUID; depleted players shown in red

## Installation

### Requirements
- Minecraft Server 1.21.11
- Fabric Loader 0.18.2+
- Java 21 or higher

### Steps
1. Install [Fabric Loader](https://fabricmc.net/use/) on your Minecraft 1.21.11 server
2. Download required dependencies:
   - [Fabric API 0.139.4+1.21.11](https://modrinth.com/mod/fabric-api)
   - [Fabric Language Kotlin 1.13.9+](https://modrinth.com/mod/fabric-language-kotlin)
3. Download `chrono-mod-0.1.0.jar` from releases
4. Place all JARs in your server's `mods/` folder
5. Start the server

## How It Works

### For Players
1. **First Join**: You receive initial playtime quota (default: 8 hours)
2. **Playing**: Your quota burns at 1:1 ratio with real time
3. **Periodic Bonus**: After the allotment period, log in to receive bonus time (default: +8 hours every 7 days)
4. **PvP**: Defeating another player grants you quota from their pool (default: +1 hour)
5. **Voluntary Transfer**: Share quota with friends using `/chrono transfer <player> <minutes>` — works even if they're offline
6. **Offline Messages**: If you receive transfers or admin adjustments while offline, you'll see messages when you log back in
7. **Revived**: If an admin or another player gives you quota when you had 0 (were depleted), you'll see a special revive message
8. **Quota Depleted**: You'll be kicked and must wait for the next allotment period or ask an admin to revive you

**Note**: Default values shown above are configurable by server admins.

### Commands

**Tab Completion**: All commands support tab-completion, showing both online and offline player names.

- `/chrono balance` - Check your own remaining quota
- `/chrono balance <player>` - Check another player's remaining quota (supports offline players by name)
- `/chrono list` - Show all players' remaining quota sorted alphabetically
  - Offline players shown by stored username instead of UUID
  - Players with 0 quota displayed in red (§c) to show they are depleted
- `/chrono transfer <player> <minutes>` - Transfer quota to another player (online or offline)
  - Example: `/chrono transfer Steve 30` transfers 30 minutes to Steve
  - **Offline Transfer**: If Steve is offline, a notification is queued and delivered on their next login
  - **Revive Feature**: If transferring to a player with 0 quota (reviving them), they'll see a special revive message on login
  - Minimum: 1 minute
  - Cannot transfer to yourself
  - Requires sufficient quota in your account

**Admin Commands** (OP-level only):
- `/chrono add <player> <minutes>` - Grant time to an online player
  - Example: `/chrono add Steve 60` grants 60 minutes to Steve
  - Shows before/after quota totals to the admin
  - Minimum: 1 minute
  - Requires operator status (via ops.json)

- `/chrono remove <player> <minutes>` - Remove time from an online player
  - Example: `/chrono remove Steve 30` removes 30 minutes from Steve
  - Validates player has sufficient quota before removal
  - Shows before/after quota totals to the admin
  - Minimum: 1 minute
  - Requires operator status (via ops.json)

- `/chrono happyhour start <minutes>` - Start a happy hour event (Operators only)
  - Example: `/chrono happyhour start 60` starts a 60-minute happy hour
  - Broadcasts fullscreen title announcement to all players
  - Players don't burn quota during this time
  - PvP transfers are multiplied (default: 2x)
  - Minimum: 1 minute
  - Replaces any existing happy hour
  - Error message if player is not an operator
- `/chrono happyhour end` - Immediately end the current happy hour (Operators only)
  - Announces end to all players
  - Quota burning resumes for all players
  - Error message if player is not an operator

### Example Timeline
```
Day 0:  Join server → 8 hours quota
Day 0:  Complete "Stone Age" advancement (task) → +15 min → 8h 15m
Day 0:  Complete "Into Fire" advancement (challenge) → +1h → 9h 15m
Day 0:  Admin starts happy hour for 30 min (quota frozen, PvP 2x)
Day 0:  Kill player during happy hour → +2h (multiplied) → 11h 15m remaining
Day 1:  Play 2 hours → 9h 15m remaining
Day 3:  Play 3 hours → 6h 15m remaining
Day 5:  Kill player (normal) → 7h 15m remaining (+1 from PvP)
Day 6:  Transfer 1h to friend → 6h 15m remaining
Day 7:  Login → 14h 15m remaining (+8 weekly allotment, had 6h 15m)
```

## Development

### Requirements
- Java 21 or higher
- Gradle 9.2.1+ (included via wrapper)

### Building
```bash
# Clone the repository
git clone https://github.com/yourusername/chrono-mod.git
cd chrono-mod

# Build the mod
./gradlew build

# Output: build/libs/chrono-mod-0.1.0.jar
```

### Development Server
```bash
./gradlew runServer
```

### Project Structure
```
src/main/kotlin/com/chronomod/
├── ChronoMod.kt              # Main entry point
├── commands/                 # Player commands (/chrono)
├── config/                   # Configuration management
├── data/                     # Data models & persistence
├── systems/                  # Core game systems
├── events/                   # Event handlers
src/main/java/com/chronomod/
└── mixin/                    # Minecraft mixins
```

## Configuration

The mod's time quota parameters can be configured via `config/chrono-mod/config.json`. The file is automatically created
with default values on first run.

### Configuration File
Location: `config/chrono-mod/config.json`

```json
{
  "initialQuotaSeconds": 28800,
  "periodicAllotmentSeconds": 28800,
  "pvpTransferSeconds": 3600,
  "allotmentPeriodLength": 604800,
  "advancementTaskSeconds": 900,
  "advancementGoalSeconds": 1800,
  "advancementChallengeSeconds": 3600,
  "pvpTransferMultiplier": 2.0
}
```

### Configuration Parameters
- **initialQuotaSeconds**: Quota granted to new players on first join (default: 28,800 = 8 hours)
- **periodicAllotmentSeconds**: Quota granted at each allotment period (default: 28,800 = 8 hours)
- **pvpTransferSeconds**: Quota transferred on PvP kills (default: 3,600 = 1 hour)
- **allotmentPeriodLength**: Time between allotments in seconds (default: 604,800 = 7 days)
- **advancementTaskSeconds**: Quota granted for task advancements (default: 900 = 15 minutes)
- **advancementGoalSeconds**: Quota granted for goal advancements (default: 1,800 = 30 minutes)
- **advancementChallengeSeconds**: Quota granted for challenge advancements (default: 3,600 = 1 hour)
- **pvpTransferMultiplier**: Multiplier for PvP transfers during happy hour (default: 2.0)
- **Auto-save Interval**: 5 minutes (hardcoded in ChronoMod.kt)

### Changing Values
1. Stop your server
2. Edit `config/chrono-mod/config.json`
3. Modify values (all times in seconds, multiplier as decimal)
4. Start your server
5. Changes take effect for new quota grants/transfers and happy hour events

**Note**: Existing player quotas are not retroactively adjusted when config changes.

See [CLAUDE.md](CLAUDE.md) for technical documentation.

## Technical Details

- **Language**: Kotlin 2.3.10
- **Mappings**: Mojang Official Mappings
- **Build System**: Gradle 9.2.1 with Fabric Loom 1.14
- **Data Format**: JSON with kotlinx.serialization
- **Thread Safety**: ConcurrentHashMap for player data

For detailed technical documentation, see [CLAUDE.md](CLAUDE.md).

## Troubleshooting

### Players not receiving weekly allotment
- Check server logs for errors
- Verify `config/chrono-mod/player-data.json` exists and is readable
- Ensure at least 7 days have passed since last allotment

### Data not persisting
- Verify `config/chrono-mod/` directory has write permissions
- Check disk space availability
- Review server logs for JSON serialization errors

## License
MIT License - see [LICENSE](LICENSE) file for details

## Credits
Built with:
- [Fabric](https://fabricmc.net/) - Modding toolchain
- [Kotlin](https://kotlinlang.org/) - Programming language
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) - JSON persistence
