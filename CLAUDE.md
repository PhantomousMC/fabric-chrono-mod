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
├── data/
│   ├── PlayerTimeData.kt           # Player quota data model
│   └── PlayerDataManager.kt        # JSON persistence and caching
├── systems/
│   └── QuotaTracker.kt             # Time burning mechanism
├── events/
│   ├── PlayerJoinHandler.kt        # Join/disconnect handling
│   └── PvPTransferHandler.kt       # PvP quota transfers
└── display/
    └── ScoreboardManager.kt        # Scoreboard UI
```

## Core Components

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

**Constants**:
- `INITIAL_QUOTA_SECONDS = 28,800` (8 hours)
- `WEEKLY_ALLOTMENT_SECONDS = 28,800` (8 hours)
- `PVP_TRANSFER_SECONDS = 3,600` (1 hour)
- `WEEK_IN_SECONDS = 604,800` (7 days)

**Key Methods**:
- `createNew(uuid)` - Initialize new player with 8-hour quota
- `isEligibleForWeeklyAllotment()` - Check if 7 days passed
- `grantWeeklyAllotment()` - Add 8 hours and update timestamp
- `decrementQuota(seconds)` - Reduce quota, return false if depleted
- `transferQuotaTo(other, amount)` - Transfer quota between players
- `formatRemainingTime()` - Format as "HH:MM:SS"

### 2. PlayerDataManager (Persistence)
**File**: `src/main/kotlin/com/chronomod/data/PlayerDataManager.kt`

**Storage**:
- **Location**: `config/chrono-mod/player-data.json`
- **Format**: JSON with UUID keys
- **Caching**: ConcurrentHashMap for thread safety

**Custom Serializers**:
- `UUIDSerializer` - Converts UUID to/from string
- `InstantSerializer` - Converts Instant to/from epoch seconds

**Methods**:
- `load()` - Load from disk on server start
- `save()` - Write to disk (auto-save + manual triggers)
- `getOrCreate(uuid)` - Get existing or create new player data
- `exists(uuid)` - Check if player has data

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

**Join Logic**:
1. Check if new player → Grant initial 8-hour quota
2. Existing player → Check weekly eligibility
3. If 7+ days passed → Grant weekly allotment
4. Send appropriate welcome message
5. Save data immediately

**Disconnect Logic**:
- Save player data on disconnect

### 5. PvPTransferHandler (Combat Transfers)
**File**: `src/main/kotlin/com/chronomod/events/PvPTransferHandler.kt`

**Event**: `ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY`

**Transfer Logic**:
1. Verify both entities are ServerPlayer instances
2. Transfer min(victim's quota, 1 hour) to killer
3. Notify both players of transfer
4. Save data immediately

**Edge Cases**:
- Victim has < 1 hour: Transfers available amount
- Non-player kills: Ignored

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
1. Create PlayerDataManager with file path
2. Initialize all system components
3. Register server lifecycle events
4. Register all component events
5. Setup auto-save (every 5 minutes)

**Lifecycle Hooks**:
- `SERVER_STARTED` → Load data, initialize scoreboard
- `SERVER_STOPPING` → Save data
- `END_SERVER_TICK` → Auto-save timer

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

### Weekly Allotment
- **Amount**: 8 hours (28,800 seconds)
- **Frequency**: Once per 7 days
- **Trigger**: Player login
- **Condition**: `Instant.now() - lastWeeklyAllotment >= 604,800 seconds`

### PvP Transfers
- **Trigger**: Player kills another player
- **Amount**: 1 hour (3,600 seconds)
- **Direction**: Victim → Killer
- **Minimum**: Transfers available quota (can be < 1 hour)

### Quota Depletion
- **Action**: Player kicked from server
- **Message**: Custom disconnect message
- **Prevention**: Player cannot rejoin until weekly allotment

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

- [ ] New player joins → Receives 8-hour quota
- [ ] Player plays for 1 hour → Quota decreases to 7 hours
- [ ] Player rejoins after 7 days → Receives weekly bonus
- [ ] Player A kills Player B → 1 hour transferred
- [ ] Player quota reaches 0 → Kicked from server
- [ ] Server restart → Data persists
- [ ] Scoreboard shows correct time
- [ ] Multiple players online → All quotas burn correctly

## Configuration

Currently hardcoded constants. Future enhancement could add `config/chrono-mod/config.json`:

```json
{
  "initialQuotaHours": 8,
  "weeklyAllotmentHours": 8,
  "pvpTransferHours": 1,
  "autoSaveIntervalMinutes": 5
}
```

## Known Limitations

1. **Scoreboard display**: Shows minutes only (integer), not HH:MM:SS
2. **Time precision**: 1-second granularity (may lose <1 sec on crashes)
3. **No commands**: No admin commands to modify quotas
4. **No grace period**: Instant kick when quota depleted
5. **Single quota pool**: No separate "bonus time" tracking

## Future Enhancements

- [ ] Admin commands (`/quota set/add/remove`)
- [ ] Player command (`/quota check`)
- [ ] Configurable values
- [ ] Grace period before kick
- [ ] Playtime leaderboard
- [ ] Time purchase system (with in-game currency)
- [ ] Quota sharing between friends
- [ ] AFK detection (pause quota burn)

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
