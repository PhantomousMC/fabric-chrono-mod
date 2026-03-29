package com.chronomod.systems

import com.chronomod.data.PlayerDataManager
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.Logger

/** Tracks and decrements player time quotas while they are online */
class QuotaTracker(
        private val dataManager: PlayerDataManager,
        private val logger: Logger,
        /**
         * Optional callback invoked after each per-second quota burn, before any kick.
         * Use this to update dependent systems (e.g. the scoreboard display) in sync
         * with the burn cycle without requiring those systems to run their own tick loop.
         */
        private val onQuotaBurned: ((ServerPlayer) -> Unit)? = null
) {
    private var tickCounter = 0

    /** Register the quota tracker to run every second (20 ticks) */
    fun register() {
        ServerTickEvents.END_SERVER_TICK.register(
                ServerTickEvents.EndTick { server -> onServerTick(server) }
        )
        logger.info("QuotaTracker registered")
    }

    /**
     * Called on every server tick (20 times per second) We only process quota burns once per second
     */
    private fun onServerTick(server: MinecraftServer) {
        tickCounter++

        // Execute every 20 ticks (1 second)
        if (tickCounter >= 20) {
            tickCounter = 0
            processQuotaBurn(server)
        }
    }

    /** Process quota burn for all online players */
    private fun processQuotaBurn(server: MinecraftServer) {
        val playerManager = server.playerList
        val onlinePlayers = playerManager.players

        for (player in onlinePlayers) {
            burnQuotaForPlayer(player)
        }
    }

    /** Burn 1 second of quota for a specific player */
    private fun burnQuotaForPlayer(player: ServerPlayer) {
        val uuid = player.uuid
        val playerData = dataManager.get(uuid) ?: return

        // Decrement quota by 1 second
        val stillHasTime = playerData.decrementQuota(1)

        // If quota depleted, kick the player
        if (!stillHasTime) {
            kickPlayerForNoQuota(player)
        }
    }

    /** Kick a player when their quota is depleted */
    private fun kickPlayerForNoQuota(player: ServerPlayer) {
        val kickMessage =
                Component.literal(
                        "Your time quota has been depleted! Come back next week for more time."
                )
        player.connection.disconnect(kickMessage)
        logger.info("Kicked player ${player.name.string} (${player.uuid}) - quota depleted")
    }
}
