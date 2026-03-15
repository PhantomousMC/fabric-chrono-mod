package com.chronomod.events

import com.chronomod.data.PlayerDataManager
import com.chronomod.data.PvPTransferResult
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.slf4j.Logger

/** Handles PvP kills and quota transfers */
class PvPTransferHandler(private val dataManager: PlayerDataManager, private val logger: Logger) {
        /** Register the PvP transfer handler */
        fun register() {
                ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register(
                        ServerEntityCombatEvents.AfterKilledOtherEntity {
                                world,
                                killer,
                                killedEntity,
                                damageSource ->
                                // Check if both killer and victim are players
                                if (killer is ServerPlayer && killedEntity is ServerPlayer) {
                                        handlePlayerKill(killer, killedEntity)
                                }
                        }
                )
                logger.info("PvPTransferHandler registered")
        }

        /** Handle quota transfer when a player kills another player */
        private fun handlePlayerKill(killer: ServerPlayer, victim: ServerPlayer) {
                when (val result = dataManager.transferQuotaOnPvPKill(victim.uuid, killer.uuid)) {
                        is PvPTransferResult.Success -> {
                                val transferredFormatted = formatSeconds(result.transferred)

                                // Notify killer
                                killer.sendSystemMessage(
                                        Component.literal(
                                                "§a+$transferredFormatted from killing ${victim.name.string}! Total: ${result.killerRemaining}"
                                        )
                                )

                                // Notify victim
                                victim.sendSystemMessage(
                                        Component.literal(
                                                "§c-$transferredFormatted lost to ${killer.name.string}! Remaining: ${result.victimRemaining}"
                                        )
                                )

                                logger.info(
                                        "PvP transfer: ${killer.name.string} killed ${victim.name.string}, " +
                                                "transferred $transferredFormatted (${result.transferred}s)"
                                )

                                // Save data immediately
                                dataManager.save()
                        }
                        is PvPTransferResult.NoTimeAvailable -> {
                                logger.debug(
                                        "No time available to transfer from ${victim.name.string} to ${killer.name.string}"
                                )
                        }
                        is PvPTransferResult.NoData -> {
                                logger.warn(
                                        "Missing player data for PvP kill: ${killer.name.string} killed ${victim.name.string}"
                                )
                        }
                }
        }

        /** Format seconds to readable time string */
        private fun formatSeconds(seconds: Long): String {
                val hours = seconds / 3600
                val minutes = (seconds % 3600) / 60
                val secs = seconds % 60

                return when {
                        hours > 0 -> String.format("%dh %dm %ds", hours, minutes, secs)
                        minutes > 0 -> String.format("%dm %ds", minutes, secs)
                        else -> String.format("%ds", secs)
                }
        }
}
