package com.chronomod

import com.chronomod.commands.ChronoCommand
import com.chronomod.config.ModConfigManager
import com.chronomod.data.PlayerDataManager
import com.chronomod.events.AdvancementHandler
import com.chronomod.events.PlayerJoinHandler
import com.chronomod.events.PvPTransferHandler
import com.chronomod.systems.BossbarTracker
import com.chronomod.systems.HappyHourManager
import com.chronomod.systems.QuotaTracker
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.advancements.AdvancementHolder
import net.minecraft.server.level.ServerPlayer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object ChronoMod : DedicatedServerModInitializer {
    const val MOD_ID = "chrono-mod"
    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

    // Server reference for event handlers
    var currentServer: net.minecraft.server.MinecraftServer? = null

    // Config manager
    private lateinit var configManager: ModConfigManager

    // Data manager for persistence
    private lateinit var dataManager: PlayerDataManager

    // Happy hour system
    private lateinit var happyHourManager: HappyHourManager
    private lateinit var bossbarTracker: BossbarTracker

    // System components
    private lateinit var quotaTracker: QuotaTracker
    private lateinit var playerJoinHandler: PlayerJoinHandler
    private lateinit var pvpTransferHandler: PvPTransferHandler
    private lateinit var advancementHandler: AdvancementHandler
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

        // Initialize happy hour system
        happyHourManager = HappyHourManager()
        bossbarTracker = BossbarTracker(happyHourManager, LOGGER)

        // Initialize systems
        quotaTracker = QuotaTracker(dataManager, LOGGER, happyHourManager)
        playerJoinHandler = PlayerJoinHandler(dataManager, bossbarTracker, LOGGER)
        pvpTransferHandler = PvPTransferHandler(dataManager, happyHourManager, configManager.config, LOGGER)
        advancementHandler = AdvancementHandler(dataManager, LOGGER)
        chronoCommand = ChronoCommand(dataManager, happyHourManager, bossbarTracker, configManager.config, LOGGER)

        // Register server lifecycle events
        registerLifecycleEvents()

        // Register all system components
        quotaTracker.register()
        playerJoinHandler.register()
        pvpTransferHandler.register()
        bossbarTracker.register()
        chronoCommand.register()

        // Register auto-save
        registerAutoSave()

        LOGGER.info("Chrono Mod initialized successfully!")
    }

    /** Called from the mixin when a player completes a visible advancement */
    fun onAdvancementCompleted(player: ServerPlayer, advancement: AdvancementHolder) {
        advancementHandler.onAdvancementCompleted(player, advancement)
    }

    /** Register server lifecycle events */
    private fun registerLifecycleEvents() {
        // Server started - load data
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            currentServer = server
            LOGGER.info("Server started - loading player data")
            dataManager.load()
        }

        // Server stopping - save data
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            LOGGER.info("Server stopping - saving player data")
            dataManager.save()
            currentServer = null
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
                // Clear old processed kills to prevent memory buildup
                pvpTransferHandler.clearProcessedKills()
            }
        }
    }
}
