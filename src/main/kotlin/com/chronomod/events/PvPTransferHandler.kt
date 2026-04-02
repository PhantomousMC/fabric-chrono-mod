package com.chronomod.events

import com.chronomod.config.ModConfig
import com.chronomod.data.PlayerDataManager
import com.chronomod.data.PvPTransferResult
import com.chronomod.systems.HappyHourManager
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.slf4j.Logger

/** Handles PvP kills and quota transfers */
class PvPTransferHandler(
        private val dataManager: PlayerDataManager,
        private val happyHourManager: HappyHourManager,
        private val config: ModConfig,
        private val logger: Logger
) {
        // Track processed kills to avoid double-processing
        private val processedDeaths = mutableSetOf<String>()

        /** Register the PvP transfer handler */
        fun register() {
                // Handle direct PvP kills
                ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register(
                        ServerEntityCombatEvents.AfterKilledOtherEntity {
                                world,
                                killer,
                                killedEntity,
                                damageSource ->
                                // Check if both killer and victim are players
                                if (killer is ServerPlayer && killedEntity is ServerPlayer) {
                                        handlePlayerKill(killer, killedEntity)
                                        // Track this kill to avoid double-processing with AFTER_DEATH event
                                        processedDeaths.add("${killedEntity.uuid}_${killer.uuid}")
                                }
                        }
                )

                // Handle indirect PvP kills (fall damage, suffocation, etc. while in combat)
                ServerLivingEntityEvents.AFTER_DEATH.register { entity, damageSource ->
                    if (entity is ServerPlayer) {
                        // Try to get the attacker from theData n damage source
                        val attacker = damageSource.entity
                        if (attacker is ServerPlayer) {
                            val killKey = "${entity.uuid}_${attacker.uuid}"
                            // Only process if not already handled by direct kill event
                            if (!processedDeaths.contains(killKey)) {
                                handlePlayerKill(attacker, entity)
                                processedDeaths.add(killKey)
                                logger.info(
                                    "Indirect PvP transfer processed: ${attacker.name.string} was last attacker of ${entity.name.string} " +
                                    "(damage type: ${damageSource.msgId})"
                                )
                            }
                        }
                    }
                }

                logger.info("PvPTransferHandler registered (including indirect kills)")
        }

        /** Handle quota transfer when a player kills another player */
        private fun handlePlayerKill(killer: ServerPlayer, victim: ServerPlayer) {
                val multiplier = if (happyHourManager.isActive()) config.pvpTransferMultiplier else 1.0
                when (val result = dataManager.transferQuotaOnPvPKill(victim.uuid, killer.uuid, multiplier)) {
                        is PvPTransferResult.Success -> {
                                val transferredFormatted = formatSeconds(result.transferred)
                                val happyHourSuffix = if (happyHourManager.isActive()) " §b(Happy Hour 2x!)" else ""

                                // Notify killer
                                killer.sendSystemMessage(
                                        Component.literal(
                                                "§a+$transferredFormatted from killing ${victim.name.string}! Total: ${result.killerRemaining}$happyHourSuffix"
                                        )
                                )

                                // Notify victim
                                victim.sendSystemMessage(
                                        Component.literal(
                                                "§c-$transferredFormatted lost to ${killer.name.string}! Remaining: ${result.victimRemaining}$happyHourSuffix"
                                        )
                                )

                                logger.info(
                                        "PvP transfer: ${killer.name.string} killed ${victim.name.string}, " +
                                                "transferred $transferredFormatted (${result.transferred}s)" +
                                                (if (multiplier > 1.0) " [multiplied by $multiplier]" else "")
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

        /** Clear processed kills to prevent memory buildup (periodically called by main mod) */
        fun clearProcessedKills() {
                if (processedDeaths.isNotEmpty()) {
                        logger.debug("Clearing ${processedDeaths.size} processed kill entries for deduplication")
                        processedDeaths.clear()
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
