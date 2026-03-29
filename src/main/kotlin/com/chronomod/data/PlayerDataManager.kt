package com.chronomod.data

import com.chronomod.config.ModConfig
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.slf4j.Logger

/** Manages player time data persistence and in-memory cache */
class PlayerDataManager(
        private val dataFile: Path,
        private val logger: Logger,
        val config: ModConfig = ModConfig()
) {
    // In-memory cache of player data
    private val playerData = ConcurrentHashMap<UUID, PlayerTimeData>()

    // JSON serializer with custom serializers for UUID and Instant
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(UUIDSerializer)
            contextual(InstantSerializer)
        }
    }

    /** Load player data from disk */
    fun load() {
        try {
            if (Files.exists(dataFile)) {
                val jsonContent = Files.readString(dataFile)
                val loadedData = json.decodeFromString<Map<String, PlayerTimeData>>(jsonContent)

                playerData.clear()
                loadedData.forEach { (_, data) -> playerData[data.uuid] = data }

                logger.info("Loaded ${playerData.size} player records from disk")
            } else {
                logger.info("No existing player data file found, starting fresh")
            }
        } catch (e: Exception) {
            logger.error("Failed to load player data", e)
        }
    }

    /** Save player data to disk */
    fun save() {
        try {
            // Ensure parent directory exists
            Files.createDirectories(dataFile.parent)

            // Convert to map with UUID strings as keys for better JSON readability
            val dataToSave = playerData.mapKeys { it.key.toString() }
            val jsonContent = json.encodeToString(dataToSave)

            Files.writeString(dataFile, jsonContent)
            logger.info("Saved ${playerData.size} player records to disk")
        } catch (e: Exception) {
            logger.error("Failed to save player data", e)
        }
    }

    /** Get or create player data */
    fun getOrCreate(uuid: UUID): PlayerTimeData {
        return playerData.getOrPut(uuid) {
            logger.info("Creating new player data for $uuid")
            PlayerTimeData.createNew(uuid, config.initialQuotaSeconds)
        }
    }

    /** Get player data if exists */
    fun get(uuid: UUID): PlayerTimeData? {
        return playerData[uuid]
    }

    /** Get all player data */
    fun getAll(): Collection<PlayerTimeData> {
        return playerData.values
    }

    /** Check if player exists in data */
    fun exists(uuid: UUID): Boolean {
        return playerData.containsKey(uuid)
    }

    /**
     * Check if player is eligible for allotment and grant it if so.
     * @return AllotmentResult indicating what happened
     */
    fun checkAndGrantAllotment(uuid: UUID): AllotmentResult {
        val playerData = getOrCreate(uuid)

        return if (playerData.isEligibleForAllotment(config.allotmentPeriodLength)) {
            playerData.grantAllotment(config.periodicAllotmentSeconds)
            AllotmentResult.Granted(playerData.formatRemainingTime())
        } else {
            AllotmentResult.NotEligible(playerData.formatRemainingTime())
        }
    }

    /**
     * Grant quota for completing an advancement.
     * @param uuid Player UUID
     * @param seconds Amount to add in seconds
     * @return AdvancementGrantResult with details about the grant
     */
    fun grantAdvancementTime(uuid: UUID, seconds: Long): AdvancementGrantResult {
        val data = get(uuid) ?: return AdvancementGrantResult.NoData
        data.addTime(seconds)
        return AdvancementGrantResult.Granted(formatDuration(seconds))
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    /**
     * Transfer quota from victim to killer on PvP kill.
     * @return PvPTransferResult with details about the transfer
     */
    fun transferQuotaOnPvPKill(victimUuid: UUID, killerUuid: UUID): PvPTransferResult {
        val victimData = get(victimUuid) ?: return PvPTransferResult.NoData
        val killerData = get(killerUuid) ?: return PvPTransferResult.NoData

        val transferred = victimData.transferQuotaTo(killerData, config.pvpTransferSeconds)

        return if (transferred > 0) {
            PvPTransferResult.Success(
                    transferred = transferred,
                    victimRemaining = victimData.formatRemainingTime(),
                    killerRemaining = killerData.formatRemainingTime()
            )
        } else {
            PvPTransferResult.NoTimeAvailable
        }
    }
}

/** Result of granting advancement time */
sealed class AdvancementGrantResult {
    data class Granted(val amountFormatted: String) : AdvancementGrantResult()
    object NoData : AdvancementGrantResult()
}

/** Result of checking and granting allotment */
sealed class AllotmentResult {
    data class Granted(val newTotal: String) : AllotmentResult()
    data class NotEligible(val currentTotal: String) : AllotmentResult()
}

/** Result of PvP quota transfer */
sealed class PvPTransferResult {
    data class Success(
            val transferred: Long,
            val victimRemaining: String,
            val killerRemaining: String
    ) : PvPTransferResult()
    object NoTimeAvailable : PvPTransferResult()
    object NoData : PvPTransferResult()
}

/** Custom serializer for UUID */
object UUIDSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): UUID {
        return UUID.fromString(decoder.decodeString())
    }
}

/** Custom serializer for Instant */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("Instant", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeLong(value.epochSecond)
    }

    override fun deserialize(decoder: Decoder): Instant {
        return Instant.ofEpochSecond(decoder.decodeLong())
    }
}
