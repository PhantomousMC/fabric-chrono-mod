package com.chronomod.events

import com.chronomod.data.PlayerDataManager
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.slf4j.Logger

/** Handles player join events for quota management */
class PlayerJoinHandler(private val dataManager: PlayerDataManager, private val logger: Logger) {
    /** Register join and disconnect handlers */
    fun register() {
        ServerPlayConnectionEvents.JOIN.register(
                ServerPlayConnectionEvents.Join { handler, _, _ -> onPlayerJoin(handler.player) }
        )

        ServerPlayConnectionEvents.DISCONNECT.register(
                ServerPlayConnectionEvents.Disconnect { handler, _ ->
                    onPlayerDisconnect(handler.player)
                }
        )

        logger.info("PlayerJoinHandler registered")
    }

    /** Handle player join - create initial data or grant weekly allotment */
    private fun onPlayerJoin(player: ServerPlayer) {
        val uuid = player.uuid
        val isNewPlayer = !dataManager.exists(uuid)
        val playerData = dataManager.getOrCreate(uuid)

        if (isNewPlayer) {
            // New player - they already got initial quota in getOrCreate
            player.sendSystemMessage(
                    Component.literal(
                            "§aWelcome! You have been granted ${playerData.formatRemainingTime()} of playtime."
                    )
            )
            logger.info("New player ${player.name.string} ($uuid) joined with initial quota")
        } else {
            // Existing player - check for weekly allotment
            if (playerData.isEligibleForWeeklyAllotment()) {
                playerData.grantWeeklyAllotment()
                player.sendSystemMessage(
                        Component.literal(
                                "§aWeekly allotment granted! You now have ${playerData.formatRemainingTime()} of playtime."
                        )
                )
                logger.info("Granted weekly allotment to ${player.name.string} ($uuid)")
            } else {
                // Just inform them of their remaining time
                player.sendSystemMessage(
                        Component.literal(
                                "§7Welcome back! You have ${playerData.formatRemainingTime()} of playtime remaining."
                        )
                )
            }
        }

        // Save immediately after granting quota
        dataManager.save()
    }

    /** Handle player disconnect - save data */
    private fun onPlayerDisconnect(player: ServerPlayer) {
        logger.info("Player ${player.name.string} (${player.uuid}) disconnected")
        dataManager.save()
    }
}
