package com.chronomod.events

import com.chronomod.ChronoMod
import com.chronomod.data.AllotmentResult
import com.chronomod.data.PlayerDataManager
import com.chronomod.systems.BossbarTracker
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.slf4j.Logger

/** Handles player join events for quota management */
class PlayerJoinHandler(private val dataManager: PlayerDataManager, private val bossbarTracker: BossbarTracker, private val logger: Logger) {
    /** Register join and disconnect handlers */
    fun register() {
        ServerPlayConnectionEvents.JOIN.register(
                ServerPlayConnectionEvents.Join { handler, _, _ -> 
                    onPlayerJoin(handler.player)
                }
        )

        ServerPlayConnectionEvents.DISCONNECT.register(
                ServerPlayConnectionEvents.Disconnect { handler, _ ->
                    onPlayerDisconnect(handler.player)
                }
        )

        logger.info("PlayerJoinHandler registered")
    }

    /** Handle player join - create initial data or grant allotment if eligible */
    private fun onPlayerJoin(player: ServerPlayer) {
        val uuid = player.uuid
        val isNewPlayer = !dataManager.exists(uuid)

        if (isNewPlayer) {
            // New player - get initial quota
            val playerData = dataManager.getOrCreate(uuid)
            player.sendSystemMessage(
                    Component.literal(
                            "§aWelcome! You have been granted ${playerData.formatRemainingTime()} of playtime."
                    )
            )
            logger.info("New player ${player.name.string} ($uuid) joined with initial quota")
        } else {
            // Existing player - check for allotment
            when (val result = dataManager.checkAndGrantAllotment(uuid)) {
                is AllotmentResult.Granted -> {
                    player.sendSystemMessage(
                            Component.literal(
                                    "§aAllotment granted! You now have ${result.newTotal} of playtime."
                            )
                    )
                    logger.info("Granted allotment to ${player.name.string} ($uuid)")
                }
                is AllotmentResult.NotEligible -> {
                    player.sendSystemMessage(
                            Component.literal(
                                    "§7Welcome back! You have ${result.currentTotal} of playtime remaining."
                            )
                    )
                }
            }
        }

        // Save immediately after any quota changes
        dataManager.save()

        // Update stored username (handles new players and name changes)
        val playerData = dataManager.get(uuid)!!
        playerData.username = player.name.string

        // Deliver any pending notifications queued while the player was offline
        if (playerData.pendingNotifications.isNotEmpty()) {
            for (msg in playerData.pendingNotifications) {
                player.sendSystemMessage(Component.literal(msg))
            }
            playerData.pendingNotifications.clear()
            dataManager.save()
        }

        // Show bossbar if happy hour is active
        val server = ChronoMod.currentServer
        if (server != null) {
            bossbarTracker.showToPlayer(uuid, server)
        }
    }

    /** Handle player disconnect - save data and clean up bossbar */
    private fun onPlayerDisconnect(player: ServerPlayer) {
        logger.info("Player ${player.name.string} (${player.uuid}) disconnected")
        // Clear awarded advancements to free memory
        dataManager.get(player.uuid)?.clearAwardedAdvancements()
        dataManager.save()
        // Remove bossbar from player
        val server = ChronoMod.currentServer
        if (server != null) {
            bossbarTracker.removeFromPlayer(player.uuid, server)
        }
    }
}
