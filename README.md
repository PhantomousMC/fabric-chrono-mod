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

### 📊 Scoreboard Display
- Real-time quota display on sidebar
- Shows remaining time in minutes
- Updates every second
- Title: "⏰ Time Remaining"

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
5. **Voluntary Transfer**: Share quota with friends using `/chrono transfer <player> <minutes>`
6. **Quota Depleted**: You'll be kicked and must wait for the next allotment period

**Note**: Default values shown above are configurable by server admins.

### Commands
- `/chrono transfer <player> <minutes>` - Transfer quota to another player
  - Example: `/chrono transfer Steve 30` transfers 30 minutes to Steve
  - Minimum: 1 minute
  - Cannot transfer to yourself
  - Requires sufficient quota in your account

### Example Timeline
```
Day 0:  Join server → 8 hours quota
Day 1:  Play 2 hours → 6 hours remaining  
Day 3:  Play 3 hours → 3 hours remaining
Day 5:  Kill player → 4 hours remaining (+1 from PvP)
Day 6:  Transfer 1h to friend → 3 hours remaining
Day 7:  Login → 11 hours remaining (+8 weekly, had 3 left)
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
├── commands/                 # Admin commands
├── config/                   # Configuration management
├── data/                     # Data models & persistence
├── systems/                  # Core game systems
├── events/                   # Event handlers
└── display/                  # UI components
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
  "allotmentPeriodLength": 604800
}
```

### Configuration Parameters
- **initialQuotaSeconds**: Quota granted to new players on first join (default: 28,800 = 8 hours)
- **periodicAllotmentSeconds**: Quota granted at each allotment period (default: 28,800 = 8 hours)
- **pvpTransferSeconds**: Quota transferred on PvP kills (default: 3,600 = 1 hour)
- **allotmentPeriodLength**: Time between allotments in seconds (default: 604,800 = 7 days)
- **Auto-save Interval**: 5 minutes (hardcoded in ChronoMod.kt)

### Changing Values
1. Stop your server
2. Edit `config/chrono-mod/config.json`
3. Modify values (all times in seconds)
4. Start your server
5. Changes take effect for new quota grants/transfers

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

### Scoreboard not displaying
- Scoreboard initializes when server starts
- Try rejoining if already online during startup
- Check server logs for initialization errors

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
