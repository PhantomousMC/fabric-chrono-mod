package com.chronomod.display

import com.chronomod.data.PlayerDataManager
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.criteria.ObjectiveCriteria
import org.slf4j.Logger

/** Manages the scoreboard display for player time quotas */
class ScoreboardManager(private val dataManager: PlayerDataManager, private val logger: Logger) {
    private var tickCounter = 0
    private var objective: Objective? = null

    companion object {
        private const val OBJECTIVE_NAME = "time_quota"
        private const val UPDATE_INTERVAL_TICKS = 20 // Update every second
    }

    /** Register the scoreboard manager */
    fun register() {
        ServerTickEvents.END_SERVER_TICK.register(
                ServerTickEvents.EndTick { server -> onServerTick(server) }
        )
        logger.info("ScoreboardManager registered")
    }

    /** Initialize the scoreboard on server start */
    fun initialize(server: MinecraftServer) {
        val scoreboard = server.scoreboard

        // Remove existing objective if it exists
        scoreboard.getObjective(OBJECTIVE_NAME)?.let { scoreboard.removeObjective(it) }

        // Create new objective
        objective =
                scoreboard.addObjective(
                        OBJECTIVE_NAME,
                        ObjectiveCriteria.DUMMY,
                        Component.literal("§6⏰ Time Remaining"),
                        ObjectiveCriteria.RenderType.INTEGER,
                        true,
                        null
                )

        // Set it to display on the sidebar
        scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, objective)

        logger.info("Scoreboard objective initialized")
    }

    /** Called on every server tick */
    private fun onServerTick(server: MinecraftServer) {
        tickCounter++

        // Update scoreboard every second
        if (tickCounter >= UPDATE_INTERVAL_TICKS) {
            tickCounter = 0
            updateScoreboard(server)
        }
    }

    /** Update scoreboard for all online players */
    private fun updateScoreboard(server: MinecraftServer) {
        val scoreboard = server.scoreboard
        val currentObjective = objective ?: return

        val playerManager = server.playerList
        val onlinePlayers = playerManager.players

        for (player in onlinePlayers) {
            val uuid = player.uuid
            val playerData = dataManager.get(uuid)

            if (playerData != null) {
                // Update score (we use remaining minutes as the score value)
                val score = scoreboard.getOrCreatePlayerScore(player, currentObjective)
                score.set((playerData.remainingTimeSeconds / 60).toInt())
            }
        }
    }

    /** Update a specific player's scoreboard immediately */
    fun updatePlayerScore(server: MinecraftServer, playerUuid: java.util.UUID) {
        val scoreboard = server.scoreboard
        val currentObjective = objective ?: return

        val player = server.playerList.getPlayer(playerUuid) ?: return
        val playerData = dataManager.get(playerUuid) ?: return

        val score = scoreboard.getOrCreatePlayerScore(player, currentObjective)
        score.set((playerData.remainingTimeSeconds / 60).toInt())
    }
}
