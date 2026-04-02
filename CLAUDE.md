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
└─── ChronoCommand.kt            # Player commands (/chrono, including happy hour)
├── config/
└─── ModConfig.kt                # Configuration management
├── data/
├─── PlayerTimeData.kt           # Player quota data model
└─── PlayerDataManager.kt        # JSON persistence, caching & business logic
├── systems/
├─── QuotaTracker.kt             # Time burning mechanism (gated during happy hour)
├─── HappyHourManager.kt         # Happy hour state management
└─── BossbarTracker.kt           # Countdown timer display (via action bar)
├── events/
├─── PlayerJoinHandler.kt        # Join/disconnect handling
├─── PvPTransferHandler.kt       # PvP quota transfers (multiplied during happy hour)
└─── AdvancementHandler.kt       # Advancement reward grants
src/main/java/com/chronomod/
└── mixin/
    └── PlayerAdvancementsMixin.java # Mixin to intercept advancement events
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
    val allotmentPeriodLength: Long = 7L * 24 * 60 * 60,
    val advancementTaskSeconds: Long = 15L * 60,
    val advancementGoalSeconds: Long = 30L * 60,
    val advancementChallengeSeconds: Long = 60L * 60,
    val pvpTransferMultiplier: Double = 2.0
)
```

**Parameters**:
- `initialQuotaSeconds` - Quota for new players (default: 8 hours)
- `periodicAllotmentSeconds` - Allotment size (default: 8 hours)
- `pvpTransferSeconds` - PvP transfer amount (default: 1 hour)
- `allotmentPeriodLength` - Time between allotments (default: 7 days)
- `advancementTaskSeconds` - Reward for task advancements (default: 15 minutes)
- `advancementGoalSeconds` - Reward for goal advancements (default: 30 minutes)
- `advancementChallengeSeconds` - Reward for challenge advancements (default: 1 hour)
- `pvpTransferMultiplier` - Multiplier for PvP transfers during happy hour (default: 2.0)

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
    @Contextual var lastWeeklyAllotment: Instant,
    var username: String = "",
    val pendingNotifications: MutableList<String> = mutableListOf()
)
```

**Fields**:
- `uuid` — Player unique identifier
- `remainingTimeSeconds` — Current quota in seconds
- `lastWeeklyAllotment` — Timestamp of last allotment (used to calculate eligibility)
- `username` — Minecraft username (updated on join; enables offline player resolution by name)
- `pendingNotifications` — Queue of chat messages from offline operations (e.g., transfers, admin commands while player away); persisted in JSON so messages survive server restarts

**Key Methods**:
- `createNew(uuid, initialQuotaSeconds)` - Initialize new player with configurable quota
- `isEligibleForAllotment(periodLength)` - Check if allotment period has elapsed
- `grantAllotment(allotmentSeconds)` - Add quota and update timestamp
- `addTime(seconds)` - Add quota without updating `lastWeeklyAllotment` (used for advancement rewards)
- `decrementQuota(seconds)` - Reduce quota, return false if depleted
- `transferQuotaTo(other, amount)` - Transfer quota between players
- `formatRemainingTime()` - Format as "HH:MM:SS"
- `hasAwardedAdvancement(id)` - Check if advancement already granted time this session
- `markAdvancementAwarded(id)` - Record advancement as awarded this session
- `clearAwardedAdvancements()` - Clear session set on disconnect (memory cleanup)

**Backward Compatibility**: New fields `username` and `pendingNotifications` have default values. Existing JSON files without these fields load without error (defaults are applied).

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
- `getByUsername(name)` - Find player data by username (case-insensitive); returns null if not found
- `resolvePlayer(name, server)` - Resolve player by name (online or offline)
  - Checks online players first, then falls back to stored username
  - Returns `Pair<ServerPlayer?, PlayerTimeData?>` (both null if not found, only one may be non-null for offline players)

**Business Logic Methods**:
- `checkAndGrantAllotment(uuid)` - Check eligibility and grant allotment if eligible
  - Returns `AllotmentResult.Granted` with new total, or
  - Returns `AllotmentResult.NotEligible` with current total
- `transferQuotaOnPvPKill(victimUuid, killerUuid)` - Handle PvP quota transfer
  - Returns `PvPTransferResult.Success` with transfer details
  - Returns `PvPTransferResult.NoTimeAvailable` if victim has no quota
  - Returns `PvPTransferResult.NoData` if player data missing
- `grantAdvancementTime(uuid, seconds)` - Grant quota for completing an advancement
  - Returns `AdvancementGrantResult.Granted` with formatted duration
  - Returns `AdvancementGrantResult.NoData` if player data missing

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

sealed class AdvancementGrantResult {
    data class Granted(val amountFormatted: String) : AdvancementGrantResult()
    object NoData : AdvancementGrantResult()
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

### 4. HappyHourManager (Happy Hour State Management)
**File**: `src/main/kotlin/com/chronomod/systems/HappyHourManager.kt`

**Purpose**: Manages happy hour lifecycle and state without enforcing game mechanics

**State Machine**:
```kotlin
sealed class HappyHourState {
    object Inactive : HappyHourState()
    data class Active(val endTimeEpochMs: Long) : HappyHourState()
}
```

**Thread Safety**: Uses `AtomicReference<HappyHourState>` for concurrent access

**Key Methods**:
- `start(durationSeconds: Long)` - Start happy hour, returns end time
- `end()` - Immediately end happy hour
- `isActive()` - Check if happy hour active and not expired (auto-expires if needed)
- `getSafeRemainingSeconds()` - Get remaining seconds with auto-expiration
- `getState()` - Get current state without auto-expiring

**Design Note**: Pure state management - no game logic dependencies. Allows other systems to query and react to happy hour state independently.

### 5. BossbarTracker (Countdown Display)
**File**: `src/main/kotlin/com/chronomod/systems/BossbarTracker.kt`

**Purpose**: Display happy hour countdown to players with persistent, smooth visual updates via action bar messages

**Mechanism**:
- Registers `ServerTickEvents.END_SERVER_TICK`
- Updates every 20 ticks (1 second) for smooth, real-time countdown
- Broadcasts remaining time to all online players simultaneously
- Shows "▶ Happy Hour: M:SS remaining [Progress Bar]" format with Unicode visual indicators
- Progress bar uses block characters: "▓" (filled) → "░" (empty) as time drains
- Color logic: Yellow (§6) normally → Red (§c) when <5 seconds remain for urgency

**Data Structures**:
- `bossbarUuid` - Identifier for tracking active happy hour countdown (UUID)
- `playerUuidsWithBossbar` - ConcurrentHashMap of UUID → Boolean tracking which players see the countdown
- Thread-safe: Uses ConcurrentHashMap for multi-threaded tick events

**Key Methods**:
- `createBossbar(durationSeconds, server)` - Initialize countdown display, start tick updates
- `update(server)` - Called every 20 ticks; queries HappyHourManager state and broadcasts updated message
- `showToPlayer(uuid, server)` - Add player to active countdown display (called on player join)
- `removeFromPlayer(uuid, server)` - Remove player from countdown display (called on player disconnect)
- `removeBossbarForAll(server)` - Clear countdown for all players when happy hour ends

**Behavior**:
- **Inactive**: No tracking, no messages sent
- **Active**: Action bar message broadcasts every 1 second
  - Format: "▶ Happy Hour: 1:23 remaining ▓▓▓▓▓▓▒░░░░"
  - Progress bar width: ~30 characters max, scaled to remaining time
  - Smooth visual drain: Each second removes one block character
- **Late Joins**: Players joining during active happy hour immediately added to display via `PlayerJoinHandler.onJoin()`
- **Color Warning**: Last 5 seconds turn message red (§c) to alert players
- **Auto-cleanup**: Clears display from all players when happy hour ends via `ChronoCommand.executeHappyHourEnd()`

**Design Notes**:
- Uses `sendSystemMessage(component, true)` where second parameter targets action bar
- Queries `HappyHourManager.getSafeRemainingSeconds()` each tick to auto-expire stale happy hours
- Requires global `ChronoMod.currentServer` reference to access ServerPlayer entity for broadcasting
- Stateless display logic - recomputes progress bar every tick based on current time
- Thread-safe UUID tracking allows concurrent player join/disconnect during updates

### 6. PlayerJoinHandler (Lifecycle Events)
**File**: `src/main/kotlin/com/chronomod/events/PlayerJoinHandler.kt`

**Dependencies**: `PlayerDataManager`, `BossbarTracker`, `Logger`, `ChronoMod` (for global server ref)

**Join Logic**:
1. Check if new player → Grant initial quota (via `dataManager.getOrCreate()`)
2. Existing player → Check allotment eligibility (via `dataManager.checkAndGrantAllotment()`)
3. Pattern match on `AllotmentResult` to send appropriate message
4. Save data immediately
5. Update stored username (via `playerData.username = player.name.string`) — handles name changes
6. Deliver any queued `pendingNotifications` — send each to player as chat message, then clear and save
7. Show countdown display if happy hour active (via `bossbarTracker.showToPlayer(uuid, server)`)
   - Late-joining players immediately added to active countdown display
   - Updates with rest of player population at 1-second intervals

**Disconnect Logic**:
- Remove player from countdown display (via `bossbarTracker.removeFromPlayer(uuid, server)`)
- Clear session advancement set via `playerData.clearAwardedAdvancements()`
- Save player data on disconnect

**Server Reference Pattern**:
- Event handlers extract server via `ChronoMod.currentServer` (global singleton set on SERVER_STARTED)
- Avoids Java reflection on private `ServerPlayer.server` field
- Null-checked before use (defensive pattern)

**Design Note**: Handler is a thin adapter - it translates Minecraft events to business operations. Coordinates with `BossbarTracker` to ensure joining players see active happy hour and are removed on disconnect. Username updates enable offline player resolution.

### 7. PvPTransferHandler (Combat Transfers)
**File**: `src/main/kotlin/com/chronomod/events/PvPTransferHandler.kt`

**Dependencies**: `PlayerDataManager`, `HappyHourManager`, `ModConfig`, `Logger`

**Event**: `ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY`

**Transfer Logic**:
1. Verify both entities are ServerPlayer instances
2. Calculate multiplier: 2x from config if happy hour active, else 1.0x
3. Delegate to `dataManager.transferQuotaOnPvPKill(victimUuid, killerUuid, multiplier)`
4. Pattern match on `PvPTransferResult`:
   - `Success` → Notify both players with multiplier indicator, save data
   - `NoTimeAvailable` → Log debug message
   - `NoData` → Log warning
5. All quota calculation done in PlayerDataManager

**Happy Hour Integration**:
- Chat messages include "§b(Happy Hour 2x!)" indicator if active
- Multiplier applied in `PlayerDataManager.transferQuotaOnPvPKill()`
- Logging includes multiplier factor for admin visibility

**Design Note**: Handler queries happy hour state to determine multiplier, then passes to business logic.

### 8. AdvancementHandler (Advancement Rewards)
**File**: `src/main/kotlin/com/chronomod/events/AdvancementHandler.kt`

**Dependencies**: `PlayerDataManager`, `Logger` (no config dependency)

**Called from**: `ChronoMod.onAdvancementCompleted()` (bridge from `PlayerAdvancementsMixin`)

**Reward Logic**:
1. Skip advancements without display info (recipe unlocks, silent grants)
2. Skip root advancements (IDs containing `/root`)
3. Check session deduplication via `playerData.hasAwardedAdvancement(id)`
4. Map `AdvancementType` → config seconds:
   - `TASK` → `config.advancementTaskSeconds` (default: 15 min)
   - `GOAL` → `config.advancementGoalSeconds` (default: 30 min)
   - `CHALLENGE` → `config.advancementChallengeSeconds` (default: 1 hour)
5. Delegate to `dataManager.grantAdvancementTime(uuid, seconds)`
6. On `Granted` → mark advancement awarded, send chat message, save
7. On `NoData` → log warning

### 7. PlayerAdvancementsMixin (Mixin)
**File**: `src/main/java/com/chronomod/mixin/PlayerAdvancementsMixin.java`
**Config**: `src/main/resources/chrono-mod.mixins.json`

**Target**: `net.minecraft.server.PlayerAdvancements`

**Injection**: `@Inject(method = "award", at = @At("RETURN"))`

**Logic**:
- `award()` is called per criterion; only fires when `cir.returnValue == true` (criterion newly granted)
- Checks `getOrStartProgress(advancement).isDone()` — advancement fully completed
- Skips advancements without display info (`advancement.value().display().isEmpty`)
- Calls `ChronoMod.INSTANCE.onAdvancementCompleted(player, advancement)`

**Design Note**: Written in Java (not Kotlin) to avoid annotation processing edge cases with Mixin.

### 9. ChronoCommand (Player Commands)
**File**: `src/main/kotlin/com/chronomod/commands/ChronoCommand.kt`

**Dependencies**: `PlayerDataManager`, `HappyHourManager`, `BossbarTracker`, `ModConfig`, `Logger`

**Tab Completion**: Built-in suggestion provider lists both online and offline players (by stored username)

**Commands**:
- `/chrono balance` - Show own remaining quota
- `/chrono balance <player>` - Show another player's remaining quota (supports offline by name, tab-complete)
- `/chrono list` - List all players' quotas sorted alphabetically; offline players display by stored username instead of UUID; 0-quota entries shown in red (§c)
- `/chrono transfer <player> <minutes>` - Transfer quota to another player (min 1 minute, no self-transfer)
  - **Offline Support**: Works with offline players by name; queues notification in `pendingNotifications` for next login
  - **Revive Detection**: If target had 0 quota (was revived), sender sees revive confirmation message and target receives revive notification on next login
  - **Notification Format**: "§aWhile you were offline, §e{sender}§a transferred §e{X min}§a to you!"

**Admin Commands** (Operator-only):
- `/chrono add <player> <minutes>` - Admin command to grant time to an online player
  - Example: `/chrono add Steve 60` grants 60 minutes to Steve
  - Notifies the target player immediately
  - Shows before/after quota totals
  - Minimum: 1 minute
  - Operator verification required (checks ops.json)

- `/chrono remove <player> <minutes>` - Admin command to remove time from an online player
  - Example: `/chrono remove Steve 30` removes 30 minutes from Steve
  - Validates player has sufficient quota before removal
  - Shows before/after quota totals
  - Notifies the target player immediately
  - Minimum: 1 minute
  - Operator verification required (checks ops.json)

- `/chrono happyhour start <minutes>` - Start a happy hour event
  - Broadcasts fullscreen title and subtitle to all players
  - Activates countdown display: "▶ Happy Hour: M:SS remaining [Progress Bar]" via action bar (1-second updates)
  - Parameters: duration in minutes (minimum 1)
  - Players' quota doesn't burn during this time
  - PvP transfers are multiplied by `config.pvpTransferMultiplier` (default: 2.0)
  - All online players see persistent countdown with smooth visual progress drain
  - Late-joining players added to active countdown display automatically
  - Replaces any existing happy hour
  - **Permission Check**: Returns error if player is not an operator (checked via ops.json)

- `/chrono happyhour end` - Immediately end the current happy hour
  - Broadcasts end message to all players
  - Clears countdown display for all players
  - Quota burning resumes for all players
  - **Permission Check**: Returns error if player is not an operator (checked via ops.json)

### 10. ChronoMod (Main Entry)
**File**: `src/main/kotlin/com/chronomod/ChronoMod.kt`

**Global State**:
- `var currentServer: MinecraftServer? = null` - Singleton reference to active server
  - Set on `SERVER_STARTED` event
  - Cleared on `SERVER_STOPPING` event
  - Accessed by event handlers that need to broadcast action bar messages without storing server reference
  - Enables `PlayerJoinHandler`, `PlayerJoinHandler`, and `BossbarTracker` to access ServerPlayer entities

**Initialization Order**:
1. Load configuration from `config/chrono-mod/config.json`
2. Create PlayerDataManager with config reference
3. Initialize HappyHourManager and BossbarTracker
4. Initialize all system components with dependencies
5. Register server lifecycle events
6. Register all component events
7. Setup auto-save (every 5 minutes)

**Lifecycle Hooks**:
- `SERVER_STARTED` → Set `currentServer`, load data
- `SERVER_STOPPING` → Save data, clear `currentServer`
- `END_SERVER_TICK` → Auto-save timer, HappyHourManager ticker, BossbarTracker ticker

**Mixin Bridge**: `onAdvancementCompleted(player, advancement)` — called by `PlayerAdvancementsMixin`, delegates to `AdvancementHandler`

**Design Notes**: 
- Config is loaded first and passed to all components that need it
- HappyHourManager and BossbarTracker are initialized early and passed to dependent systems
- Global server reference allows event handlers to broadcast action bar messages without parameter threading
- Thread safety: All system references initialized before thread-sensitive events fire

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
    "lastWeeklyAllotment": 1710374400,
    "username": "Steve",
    "pendingNotifications": [
      "§aWhile you were offline, §eAlice§a transferred §e1h§a to you!"
    ]
  }
}
```

## Game Mechanics

### Time Burn Rate
- **Rate**: 1 second of quota per 1 second of playtime
- **Applies to**: Online players only
- **Tick frequency**: Every 20 game ticks (1 real-world second)
- **Happy Hour Override**: Quota does NOT burn during active happy hour

### Periodic Allotment
- **Amount**: Configurable via `periodicAllotmentSeconds` (default: 8 hours)
- **Frequency**: Configurable via `allotmentPeriodLength` (default: 7 days)
- **Trigger**: Player login
- **Condition**: `Instant.now() - lastWeeklyAllotment >= allotmentPeriodLength`

### PvP Transfers
- **Trigger**: Player kills another player
- **Base Amount**: Configurable via `pvpTransferSeconds` (default: 1 hour)
- **Direction**: Victim → Killer
- **Minimum**: Transfers available quota (can be less if victim has insufficient time)
- **Happy Hour Multiplier**: During happy hour, transfer amount is multiplied by `pvpTransferMultiplier` (default: 2.0x)
  - Example: Base 1 hour × 2.0 multiplier = 2 hours transferred during happy hour

### Happy Hour
- **Trigger**: Admin command `/chrono happyhour start <minutes>`
- **Duration**: Configurable per activation (minimum 1 minute)
- **Effects**: 
  - Players' quota doesn't burn (frozen in time)
  - PvP transfers are multiplied by `config.pvpTransferMultiplier`
  - All online players see countdown via action bar
  - New players joining see happy hour notification
- **Replacement**: Starting new happy hour ends any existing one
- **State**: Session-scoped (not persisted across restarts)

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
- Advancement events via Mixin (`PlayerAdvancements.award`)

### Minecraft APIs Used
- `ServerPlayer` - Player entity operations
- `Component` - Text/chat messages (Mojang mappings)
- `PlayerAdvancements` / `AdvancementHolder` / `AdvancementType` - Advancement system

## Mojang Mappings Migration

### Key Class Name Changes (from Yarn)
| Yarn Name | Mojang Name |
|-----------|-------------|
| `ServerPlayerEntity` | `ServerPlayer` |
| `Text` | `Component` |
| `PlayerManager` | `PlayerList` |
| `ServerWorld` | `ServerLevel` |

### Method Changes
- `player.sendMessage(text, actionBar)` → `player.sendSystemMessage(component)`
- `player.networkHandler` → `player.connection`
- `server.playerManager` → `server.playerList`
- `playerList.playerList` → `playerList.players`

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
- [ ] `/chrono balance` → Shows own remaining time
- [ ] `/chrono balance <player>` → Shows target player's remaining time
- [ ] `/chrono list` → Lists all players sorted alphabetically
- [ ] Player completes task advancement → +15 min granted, chat message shown
- [ ] Player completes goal advancement → +30 min granted, chat message shown
- [ ] Player completes challenge advancement → +1 hour granted, chat message shown
- [ ] Player completes advancement with multiple criteria → time granted only once
- [ ] Player completes root advancement (e.g. story/root) → no time granted
- [ ] Player unlocks recipe → no time granted, no message
- [ ] Player quota reaches 0 → Kicked from server
- [ ] Server restart → Data persists
- [ ] Multiple players online → All quotas burn correctly
- [ ] Config file created on first run with defaults (including advancement & happy hour fields)
- [ ] Config changes applied after server restart
- [ ] **Offline Players: `/chrono balance <offline-player>`** → Shows stored quota for offline player by name
- [ ] **Offline Players: `/chrono list`** → Offline players show by stored username (not UUID); 0-quota entries in red
- [ ] **Offline Players: `/chrono transfer <offline-player> 10`** → Transfers 10 min; target receives notification on login
- [ ] **Offline Players: `/chrono transfer <depleted-offline-player> 10`** → Revive case: sender sees "revived" message; target receives revive notification on login
- [ ] **Admin Add: `/chrono add <online-player> 60` (OP)** → Grants time; shows before/after totals
- [ ] **Admin Remove: `/chrono remove <online-player> 30` (OP)** → Removes time; checks sufficient quota exists
- [ ] **Offline Players: Tab-complete** → Shows both online and offline player names
- [ ] **Offline Players: Case-insensitive** → `/chrono transfer STEVE 5` resolves against stored username "Steve"
- [ ] **Offline Players: Server restart** → Pending notifications survive restarts (persisted in JSON)
- [ ] **Offline Players: Notification delivery** → Offline messages shown at top of join sequence, before allotment/welcome
- [ ] **Happy Hour: Admin runs `/chrono happyhour start 5`** → Fullscreen title "Happy Hour!" shown to all players
- [ ] **Happy Hour: Countdown visible** → Action bar shows "▶ Happy Hour: 4:XX remaining ▓▓▓▓▓▒░░░░" format
- [ ] **Happy Hour: Updates every 1 second** → Countdown updates smoothly without 10-second gaps
- [ ] **Happy Hour: Progress bar drains** → Unicode block characters visually decrease as time passes (▓→░)
- [ ] **Happy Hour: Color urgency** → Progress bar yellow (§6) normally, turns red (§c) in final 5 seconds
- [ ] **Happy Hour: Quota doesn't burn** → Player plays 2 minutes, quota unchanged
- [ ] **Happy Hour: PvP transfers multiplied** → Transfer is 2x config value (default 2h instead of 1h)
- [ ] **Happy Hour: Late join shows countdown** → Player joining during happy hour sees countdown immediately
- [ ] **Happy Hour: Disconnect clears display** → Player doesn't see countdown after disconnecting
- [ ] **Happy Hour: `/chrono happyhour end`** → Happy hour ends, action bar clears, quota burn resumes
- [ ] **Happy Hour: New start replaces** → Start arbitrary happy hour, then start new one → new duration replaces old
- [ ] **Happy Hour: Config multiplier** → Change `pvpTransferMultiplier: 3.0`, restart, verify 3x transfers during happy hour
- [ ] **Backward Compatibility** → Existing JSON without `username` or `pendingNotifications` fields loads without error

## Configuration

The mod supports JSON-based configuration via `config/chrono-mod/config.json`:

### Configuration File
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

### Configuration Options
- **initialQuotaSeconds**: Quota granted to new players (default: 28,800 = 8 hours)
- **periodicAllotmentSeconds**: Quota added at each allotment (default: 28,800 = 8 hours)
- **pvpTransferSeconds**: Quota transferred on PvP kills (default: 3,600 = 1 hour)
- **allotmentPeriodLength**: Seconds between allotments (default: 604,800 = 7 days)
- **advancementTaskSeconds**: Quota for task advancements (default: 900 = 15 minutes)
- **advancementGoalSeconds**: Quota for goal advancements (default: 1,800 = 30 minutes)
- **advancementChallengeSeconds**: Quota for challenge advancements (default: 3,600 = 1 hour)
- **pvpTransferMultiplier**: Multiplier for PvP transfers during happy hour (default: 2.0)

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
  "allotmentPeriodLength": 86400,
  "advancementTaskSeconds": 300,
  "advancementGoalSeconds": 600,
  "advancementChallengeSeconds": 1800
}
```
This gives new players 4 hours, daily allotments of 2 hours, 30-minute PvP transfers, and 5/10/30-minute advancement rewards.

## Known Limitations

1. **Time precision**: 1-second granularity (may lose <1 sec on crashes)
2. **No grace period**: Instant kick when quota depleted
3. **Single quota pool**: No separate "bonus time" tracking
4. **No hot-reload**: Config changes require server restart
5. **Advancement deduplication is session-scoped**: Restarting the server resets the session set (safe — MC won't re-fire already-completed advancements)

## Future Enhancements

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

- **Issue**: Advancement rewards not triggering
  - **Check**: `chrono-mod.mixins.json` is present in the JAR
  - **Check**: `fabric.mod.json` contains `"mixins": ["chrono-mod.mixins.json"]`
  - **Check**: Server logs for mixin application errors on startup

## License
MIT License - See LICENSE file for details
