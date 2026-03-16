package com.chronomod

import com.chronomod.commands.ChronoCommand
import com.chronomod.config.ModConfigManager
import com.chronomod.data.PlayerDataManager
import com.chronomod.display.ScoreboardManager
import com.chronomod.events.PlayerJoinHandler
import com.chronomod.events.PvPTransferHandler
import com.chronomod.systems.QuotaTracker
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object ChronoMod : DedicatedServerModInitializer {
    const val MOD_ID = "chrono-mod"
    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

    // Config manager
    private lateinit var configManager: ModConfigManager

    // Data manager for persistence
    private lateinit var dataManager: PlayerDataManager

    // System components
    private lateinit var quotaTracker: QuotaTracker
    private lateinit var playerJoinHandler: PlayerJoinHandler
    private lateinit var pvpTransferHandler: PvPTransferHandler
    private lateinit var scoreboardManager: ScoreboardManager
    private lateinit var chronoCommand: ChronoCommand

    // Auto-save timer
    private val autoSaveTickCounter = AtomicInteger(0)
    private const val AUTO_SAVE_INTERVAL_TICKS = 20 * 60 * 5 // 5 minutes

    override fun onInitializeServer() {
        LOGGER.info("Initializing Chrono Mod - Time Quota System")

        val configDir = Paths.get("config", MOD_ID)

        // Load config first so all components use the configured values
        val configFile = configDir.resolve("config.json")
        configManager = ModConfigManager(configFile, LOGGER)
        configManager.load()

        // Initialize data manager with config
        val dataFile = configDir.resolve("player-data.json")
        dataManager = PlayerDataManager(dataFile, LOGGER, configManager.config)

        // Initialize systems
        scoreboardManager = ScoreboardManager(dataManager, LOGGER)
        quotaTracker = QuotaTracker(dataManager, LOGGER) { player -> scoreboardManager.updatePlayerDisplay(player) }
        playerJoinHandler = PlayerJoinHandler(dataManager, LOGGER)
        pvpTransferHandler = PvPTransferHandler(dataManager, LOGGER)
        chronoCommand = ChronoCommand(dataManager, LOGGER)

        // Register server lifecycle events
        registerLifecycleEvents()

        // Register all system components
        quotaTracker.register()
        playerJoinHandler.register()
        pvpTransferHandler.register()
        scoreboardManager.register()
        chronoCommand.register()

        // Register auto-save
        registerAutoSave()

        LOGGER.info("Chrono Mod initialized successfully!")
    }

    /** Register server lifecycle events */
    private fun registerLifecycleEvents() {
        // Server started - load data and initialize scoreboard
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            LOGGER.info("Server started - loading player data")
            dataManager.load()
            scoreboardManager.initialize(server)
        }

        // Server stopping - save data
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            LOGGER.info("Server stopping - saving player data")
            dataManager.save()
        }
    }

    /** Register auto-save every 5 minutes */
    private fun registerAutoSave() {
        ServerTickEvents.END_SERVER_TICK.register { _ ->
            val ticks = autoSaveTickCounter.incrementAndGet()

            if (ticks >= AUTO_SAVE_INTERVAL_TICKS) {
                autoSaveTickCounter.set(0)
                LOGGER.info("Auto-saving player data")
                dataManager.save()
            }
        }
    }
}
