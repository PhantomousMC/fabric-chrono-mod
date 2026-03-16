package com.chronomod.config

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.Logger

/**
 * Mod configuration loaded from config/chrono-mod/config.json.
 *
 * @property initialQuotaSeconds Quota granted to new players on first join (default: 8 hours)
 * @property periodicAllotmentSeconds Quota granted at the start of each allotment period (default:
 *   8 hours)
 * @property pvpTransferSeconds Quota transferred from victim to killer on a PvP kill (default: 1
 *   hour)
 * @property allotmentPeriodLength How many seconds must pass before the next allotment is granted
 *   (default: 7 days)
 */
@Serializable
data class ModConfig(
        val initialQuotaSeconds: Long = 8L * 60 * 60,
        val periodicAllotmentSeconds: Long = 8L * 60 * 60,
        val pvpTransferSeconds: Long = 1L * 60 * 60,
        val allotmentPeriodLength: Long = 7L * 24 * 60 * 60
)

/** Loads and saves [ModConfig] to/from a JSON file, creating defaults when absent. */
class ModConfigManager(private val configFile: Path, private val logger: Logger) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    var config: ModConfig = ModConfig()
        private set

    /** Load config from disk, or create a default config file if none exists. */
    fun load() {
        try {
            if (Files.exists(configFile)) {
                val content = Files.readString(configFile)
                config = json.decodeFromString(content)
                logger.info(
                        "Loaded config: initialQuotaSeconds=${config.initialQuotaSeconds}, " +
                                "periodicAllotmentSeconds=${config.periodicAllotmentSeconds}, " +
                                "pvpTransferSeconds=${config.pvpTransferSeconds}, " +
                                "allotmentPeriodLength=${config.allotmentPeriodLength}"
                )
            } else {
                logger.info(
                        "No config file found at $configFile, creating with defaults"
                )
                save()
            }
        } catch (e: Exception) {
            logger.error("Failed to load config, using defaults", e)
        }
    }

    /** Save the current config to disk. */
    fun save() {
        try {
            Files.createDirectories(configFile.parent)
            Files.writeString(configFile, json.encodeToString(config))
        } catch (e: Exception) {
            logger.error("Failed to save config", e)
        }
    }
}
