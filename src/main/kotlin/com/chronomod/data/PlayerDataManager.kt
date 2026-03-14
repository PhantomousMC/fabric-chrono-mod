package com.chronomod.data

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
class PlayerDataManager(private val dataFile: Path, private val logger: Logger) {
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
            PlayerTimeData.createNew(uuid)
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
