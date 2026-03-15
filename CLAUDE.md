# Chrono Mod - Technical Documentation

## Overview
Chrono Mod is a server-side Minecraft Fabric mod that implements a time quota system for players. Players receive
limited playtime that burns while they're online, with various ways to extend their quota.

## Architecture

### Technology Stack
- **Minecraft**: 1.21.11
- **Fabric Loader**: 0.18.2
- **Fabric API**: 0.139.4+1.21.11
- **Fabric Loom**: 1.14-SNAPSHOT
- **Fabric Language Kotlin**: 1.13.9+kotlin.2.3.10
- **Kotlin**: 2.3.10
- **Gradle**: 9.2.1
- **Mappings**: Mojang Official Mappings

### Build System Notes
- Uses Gradle 9.2.1 (required for Loom 1.14+ and MC 1.21.11)
- Mojang mappings instead of Yarn (required for MC 1.21.11 compatibility)
- Kotlin plugin with kotlinx.serialization for JSON persistence
- All class references use Mojang mapping names (e.g., `ServerPlayer` not `ServerPlayerEntity`)

## Project Structure

```
src/main/kotlin/com/chronomod/
├── ChronoMod.kt                    # Main entry point, lifecycle management
├── commands/
│   └── ChronoCommand.kt            # Admin command (/chrono)
├── config/
│   └── ModConfig.kt                # Configuration management
├── data/
│   ├── PlayerTimeData.kt           # Player quota data model
│   └── PlayerDataManager.kt        # JSON persistence, caching & business logic
├── systems/
│   └── QuotaTracker.kt             # Time burning mechanism
├── events/
│   ├── PlayerJoinHandler.kt        # Join/disconnect handling
│   └── PvPTransferHandler.kt       # PvP quota transfers
└── display/
    └── ScoreboardManager.kt        # Scoreboard UI
```


## Architectural Principles

### Separation of Concerns
The mod follows a clean architecture with distinct layers:

1. **Event Layer** (`events/`) - Thin adapters that translate Minecraft events to domain operations
2. **Business Logic Layer** (`data/PlayerDataManager`) - Centralizes quota management rules
3. **Data Layer** (`data/PlayerTimeData`) - Pure data model with minimal logic
4. **Configuration Layer** (`config/`) - Externalized configuration management

**Key Design Decision**: Event handlers are config-unaware. They delegate to `PlayerDataManager` which encapsulates all
business logic and configuration concerns. This ensures:
- Event handlers focus solely on coordinating Minecraft events
- Configuration changes don't require modifying event handlers
- Business logic is testable independently of Minecraft events
- Clear dependency flow: Events → Business Logic → Data

## Core Components

### 0. ModConfig (Configuration Management)
**File**: `src/main/kotlin/com/chronomod/config/ModConfig.kt`

```kotlin
@Serializable
data class ModConfig(
    val initialQuotaSeconds: Long = 8L * 60 * 60,
    val periodicAllotmentSeconds: Long = 8L * 60 * 60,
    val pvpTransferSeconds: Long = 1L * 60 * 60,
    val allotmentPeriodLength: Long = 7L * 24 * 60 * 60
)
```

**Parameters**:
- `initialQuotaSeconds` - Quota for new players (default: 8 hours)
- `periodicAllotmentSeconds` - Allotment size (default: 8 hours)
- `pvpTransferSeconds` - PvP transfer amount (default: 1 hour)
- `allotmentPeriodLength` - Time between allotments (default: 7 days)

**ModConfigManager**:
- **Location**: `config/chrono-mod/config.json`
- **Auto-creation**: Creates file with defaults if missing
- **Error handling**: Falls back to defaults on load errors
- **Format**: Pretty-printed JSON

### 1. PlayerTimeData (Data Model)
**File**: `src/main/kotlin/com/chronomod/data/PlayerTimeData.kt`

```kotlin
@Serializable
data class PlayerTimeData(
    @Contextual val uuid: UUID,
    var remainingTimeSeconds: Long,
    @Contextual var lastWeeklyAllotment: Instant
)
```

**Companion Object Constants** (Deprecated):
- Legacy constants kept for reference only
- Actual values now come from `ModConfig`
- All deprecated and marked with `@Deprecated` annotation

**Key Methods**:
- `createNew(uuid, initialQuotaSeconds)` - Initialize new player with configurable quota
- `isEligibleForAllotment(periodLength)` - Check if allotment period has elapsed
- `grantAllotment(allotmentSeconds)` - Add quota and update timestamp
- `decrementQuota(seconds)` - Reduce quota, return false if depleted
- `transferQuotaTo(other, amount)` - Transfer quota between players
- `formatRemainingTime()` - Format as "HH:MM:SS"

**Design Note**: Methods accept config values as parameters rather than using defaults, enforcing that all configuration
flows through `PlayerDataManager`.

### 2. PlayerDataManager (Persistence & Business Logic)
**File**: `src/main/kotlin/com/chronomod/data/PlayerDataManager.kt`

**Storage**:
- **Location**: `config/chrono-mod/player-data.json`
- **Format**: JSON with UUID keys
- **Caching**: ConcurrentHashMap for thread safety
- **Config**: Holds reference to `ModConfig` for business logic

**Custom Serializers**:
- `UUIDSerializer` - Converts UUID to/from string
- `InstantSerializer` - Converts Instant to/from epoch seconds

**Data Methods**:
- `load()` - Load from disk on server start
- `save()` - Write to disk (auto-save + manual triggers)
- `getOrCreate(uuid)` - Get existing or create new player data (uses config for initial quota)
- `get(uuid)` - Get player data if exists
- `exists(uuid)` - Check if player has data

**Business Logic Methods**:
- `checkAndGrantAllotment(uuid)` - Check eligibility and grant allotment if eligible
  - Returns `AllotmentResult.Granted` with new total, or
  - Returns `AllotmentResult.NotEligible` with current total
- `transferQuotaOnPvPKill(victimUuid, killerUuid)` - Handle PvP quota transfer
  - Returns `PvPTransferResult.Success` with transfer details
  - Returns `PvPTransferResult.NoTimeAvailable` if victim has no quota
  - Returns `PvPTransferResult.NoData` if player data missing

**Result Types**:
```kotlin
sealed class AllotmentResult {
    data class Granted(val newTotal: String) : AllotmentResult()
    data class NotEligible(val currentTotal: String) : AllotmentResult()
}

sealed class PvPTransferResult {
    data class Success(
        val transferred: Long,
        val victimRemaining: String,
        val killerRemaining: String
    ) : PvPTransferResult()
    object NoTimeAvailable : PvPTransferResult()
    object NoData : PvPTransferResult()
}
```

**Design Note**: By centralizing business logic here, event handlers remain thin and config-unaware. All quota
calculation rules are encapsulated in one place.

### 3. QuotaTracker (Time Burning)
**File**: `src/main/kotlin/com/chronomod/systems/QuotaTracker.kt`

**Mechanism**:
- Registers `ServerTickEvents.END_SERVER_TICK`
- Runs every 20 ticks (1 second)
- Decrements quota for all online players
- Kicks players when quota reaches 0

**Kick Message**: "Your time quota has been depleted! Come back next week for more time."

### 4. PlayerJoinHandler (Lifecycle Events)
**File**: `src/main/kotlin/com/chronomod/events/PlayerJoinHandler.kt`

**Dependencies**: `PlayerDataManager`, `Logger` (no config dependency)

**Join Logic**:
1. Check if new player → Grant initial quota (via `dataManager.getOrCreate()`)
2. Existing player → Check allotment eligibility (via `dataManager.checkAndGrantAllotment()`)
3. Pattern match on `AllotmentResult` to send appropriate message
4. Save data immediately

**Disconnect Logic**:
- Save player data on disconnect

**Design Note**: Handler is a thin adapter - it translates Minecraft events to business operations without knowing about
configuration values.

### 5. PvPTransferHandler (Combat Transfers)
**File**: `src/main/kotlin/com/chronomod/events/PvPTransferHandler.kt`

**Dependencies**: `PlayerDataManager`, `Logger` (no config dependency)

**Event**: `ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY`

**Transfer Logic**:
1. Verify both entities are ServerPlayer instances
2. Delegate to `dataManager.transferQuotaOnPvPKill(victimUuid, killerUuid)`
3. Pattern match on `PvPTransferResult`:
   - `Success` → Notify both players, save data
   - `NoTimeAvailable` → Log debug message
   - `NoData` → Log warning
4. All quota calculation done in PlayerDataManager

**Design Note**: Handler focuses on event coordination and player messaging, delegating business logic to the data
layer.

### 6. ScoreboardManager (Display)
**File**: `src/main/kotlin/com/chronomod/display/ScoreboardManager.kt`

**Objective**:
- **Name**: `time_quota`
- **Type**: `ObjectiveCriteria.DUMMY`
- **Display**: Sidebar (right side)
- **Title**: "⏰ Time Remaining"

**Update Frequency**: Every 20 ticks (1 second)

**Display Format**: Shows remaining minutes as integer score

### 7. ChronoMod (Main Entry)
**File**: `src/main/kotlin/com/chronomod/ChronoMod.kt`

**Initialization Order**:
1. Load configuration from `config/chrono-mod/config.json`
2. Create PlayerDataManager with config reference
3. Initialize all system components
4. Register server lifecycle events
5. Register all component events
6. Setup auto-save (every 5 minutes)

**Lifecycle Hooks**:
- `SERVER_STARTED` → Load data, initialize scoreboard
- `SERVER_STOPPING` → Save data
- `END_SERVER_TICK` → Auto-save timer

**Design Note**: Config is loaded first and passed to PlayerDataManager, which then provides all config-aware business
logic to the rest of the system.

## Data Persistence

### Save Triggers
1. **Auto-save**: Every 5 minutes (6000 ticks)
2. **Player disconnect**: Immediate save
3. **Server shutdown**: Immediate save
4. **Quota changes**: After join bonuses, PvP transfers

### JSON Format Example
```json
{
  "550e8400-e29b-41d4-a716-446655440000": {
    "uuid": "550e8400-e29b-41d4-a716-446655440000",
    "remainingTimeSeconds": 25200,
    "lastWeeklyAllotment": 1710374400
  }
}
```

## Game Mechanics

### Time Burn Rate
- **Rate**: 1 second of quota per 1 second of playtime
- **Applies to**: Online players only
- **Tick frequency**: Every 20 game ticks (1 real-world second)

### Periodic Allotment
- **Amount**: Configurable via `periodicAllotmentSeconds` (default: 8 hours)
- **Frequency**: Configurable via `allotmentPeriodLength` (default: 7 days)
- **Trigger**: Player login
- **Condition**: `Instant.now() - lastWeeklyAllotment >= allotmentPeriodLength`

### PvP Transfers
- **Trigger**: Player kills another player
- **Amount**: Configurable via `pvpTransferSeconds` (default: 1 hour)
- **Direction**: Victim → Killer
- **Minimum**: Transfers available quota (can be less if victim has insufficient time)

### Quota Depletion
- **Action**: Player kicked from server
- **Message**: Custom disconnect message
- **Prevention**: Player cannot rejoin until next allotment period

## API Usage

### Fabric Events Used
- `ServerLifecycleEvents.SERVER_STARTED`
- `ServerLifecycleEvents.SERVER_STOPPING`
- `ServerTickEvents.END_SERVER_TICK`
- `ServerPlayConnectionEvents.JOIN`
- `ServerPlayConnectionEvents.DISCONNECT`
- `ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY`

### Minecraft APIs Used
- `ServerPlayer` - Player entity operations
- `Component` - Text/chat messages (Mojang mappings)
- `Scoreboard` - Display management
- `ObjectiveCriteria` - Scoreboard objectives

## Mojang Mappings Migration

### Key Class Name Changes (from Yarn)
| Yarn Name | Mojang Name |
|-----------|-------------|
| `ServerPlayerEntity` | `ServerPlayer` |
| `Text` | `Component` |
| `PlayerManager` | `PlayerList` |
| `ScoreboardCriterion` | `ObjectiveCriteria` |
| `ScoreboardObjective` | `Objective` |
| `ScoreboardDisplaySlot` | `DisplaySlot` |
| `ServerWorld` | `ServerLevel` |

### Method Changes
- `player.sendMessage(text, actionBar)` → `player.sendSystemMessage(component)`
- `player.networkHandler` → `player.connection`
- `server.playerManager` → `server.playerList`
- `playerList.playerList` → `playerList.players`
- `scoreboard.getOrCreateScore()` → `scoreboard.getOrCreatePlayerScore()`
- `scoreboard.setObjectiveSlot()` → `scoreboard.setDisplayObjective()`

## Building

### Commands
```bash
# Build mod
./gradlew build

# Clean build
./gradlew clean build

# Run development server
./gradlew runServer
```

### Output
- **JAR location**: `build/libs/chrono-mod-0.1.0.jar`
- **Size**: ~34KB
- **Dependencies**: Bundled via Fabric Language Kotlin

## Testing Checklist

- [ ] New player joins → Receives initial quota (default: 8 hours)
- [ ] Player plays for 1 hour → Quota decreases by 1 hour
- [ ] Player rejoins after allotment period → Receives periodic allotment
- [ ] Player A kills Player B → Configured amount transferred
- [ ] Player uses `/chrono transfer` → Quota transferred voluntarily
- [ ] Player quota reaches 0 → Kicked from server
- [ ] Server restart → Data persists
- [ ] Scoreboard shows correct time
- [ ] Multiple players online → All quotas burn correctly
- [ ] Config file created on first run with defaults
- [ ] Config changes applied after server restart

## Configuration

The mod supports JSON-based configuration via `config/chrono-mod/config.json`:

### Configuration File
```json
{
  "initialQuotaSeconds": 28800,
  "periodicAllotmentSeconds": 28800,
  "pvpTransferSeconds": 3600,
  "allotmentPeriodLength": 604800
}
```

### Configuration Options
- **initialQuotaSeconds**: Quota granted to new players (default: 28,800 = 8 hours)
- **periodicAllotmentSeconds**: Quota added at each allotment (default: 28,800 = 8 hours)
- **pvpTransferSeconds**: Quota transferred on PvP kills (default: 3,600 = 1 hour)
- **allotmentPeriodLength**: Seconds between allotments (default: 604,800 = 7 days)

### Behavior
- **Auto-creation**: File created with defaults if missing on first run
- **Error handling**: Falls back to defaults if file is corrupt
- **Hot-reload**: Not supported - requires server restart for changes to take effect
- **Logging**: Loaded values are logged on startup

### Customization Example
For a competitive server with daily allotments:
```json
{
  "initialQuotaSeconds": 14400,
  "periodicAllotmentSeconds": 7200,
  "pvpTransferSeconds": 1800,
  "allotmentPeriodLength": 86400
}
```
This gives new players 4 hours, daily allotments of 2 hours, and 30-minute PvP transfers.

## Known Limitations

1. **Scoreboard display**: Shows minutes only (integer), not HH:MM:SS
2. **Time precision**: 1-second granularity (may lose <1 sec on crashes)
3. **No grace period**: Instant kick when quota depleted
4. **Single quota pool**: No separate "bonus time" tracking
5. **No hot-reload**: Config changes require server restart

## Future Enhancements

- [ ] Admin commands for quota inspection and management
- [ ] Grace period before kick
- [ ] Playtime leaderboard
- [ ] Time purchase system (with in-game currency)
- [ ] AFK detection (pause quota burn)
- [ ] Config hot-reload support

## Troubleshooting

### Build Issues
- **Issue**: "Unsupported unpick version"
  - **Solution**: Use Gradle 9.2.1+ and Loom 1.14+

- **Issue**: "Unresolved reference" for Minecraft classes
  - **Solution**: Check Mojang mapping names, not Yarn names

### Runtime Issues
- **Issue**: Data not persisting
  - **Check**: `config/chrono-mod/` directory permissions
  - **Check**: Server logs for save/load errors

- **Issue**: Scoreboard not showing
  - **Check**: Server started successfully
  - **Check**: Players are online during initialization

## License
MIT License - See LICENSE file for details
