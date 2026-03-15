package com.chronomod.events

import com.chronomod.config.ModConfig
import com.chronomod.data.PlayerDataManager
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.slf4j.Logger

/** Handles PvP kills and quota transfers */
class PvPTransferHandler(
        private val dataManager: PlayerDataManager,
        private val logger: Logger,
        private val config: ModConfig = ModConfig()
) {
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
        val killerUuid = killer.uuid
        val victimUuid = victim.uuid

        val killerData = dataManager.getOrCreate(killerUuid)
        val victimData = dataManager.getOrCreate(victimUuid)

        // Transfer quota from victim to killer
        val transferred =
                victimData.transferQuotaTo(killerData, config.pvpTransferSeconds)

        if (transferred > 0) {
            val transferredFormatted = formatSeconds(transferred)

            // Notify killer
            killer.sendSystemMessage(
                    Component.literal(
                            "§a+$transferredFormatted from killing ${victim.name.string}! Total: ${killerData.formatRemainingTime()}"
                    )
            )

            // Notify victim
            victim.sendSystemMessage(
                    Component.literal(
                            "§c-$transferredFormatted lost to ${killer.name.string}! Remaining: ${victimData.formatRemainingTime()}"
                    )
            )

            logger.info(
                    "PvP transfer: ${killer.name.string} killed ${victim.name.string}, " +
                            "transferred $transferredFormatted (${transferred}s)"
            )

            // Save data immediately
            dataManager.save()
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
