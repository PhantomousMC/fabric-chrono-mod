package com.chronomod.events

import com.chronomod.data.AdvancementGrantResult
import com.chronomod.data.PlayerDataManager
import net.minecraft.advancements.AdvancementHolder
import net.minecraft.advancements.AdvancementType
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.slf4j.Logger

/** Handles advancement completion events and grants quota time to the player */
class AdvancementHandler(private val dataManager: PlayerDataManager, private val logger: Logger) {

    fun onAdvancementCompleted(player: ServerPlayer, advancement: AdvancementHolder) {
        val display = advancement.value().display().orElse(null) ?: return
        val advancementId = advancement.id.toString()

        // Skip root advancements (organizational, not meant to be earned)
        if (advancementId.contains("/root")) {
            logger.debug(
                    "Advancement ${advancementId} is a root advancement, skipping time grant"
            )
            return
        }

        // Check if we've already granted time for this advancement in this session
        val playerData = dataManager.get(player.uuid)
        if (playerData != null && playerData.hasAwardedAdvancement(advancementId)) {
            logger.debug(
                    "Advancement ${advancementId} already awarded to ${player.name.string} (${player.uuid}) this session, skipping"
            )
            return
        }

        val seconds = when (display.type) {
            AdvancementType.TASK -> dataManager.config.advancementTaskSeconds
            AdvancementType.GOAL -> dataManager.config.advancementGoalSeconds
            AdvancementType.CHALLENGE -> dataManager.config.advancementChallengeSeconds
            else -> dataManager.config.advancementTaskSeconds
        }

        when (val result = dataManager.grantAdvancementTime(player.uuid, seconds)) {
            is AdvancementGrantResult.Granted -> {
                // Mark as awarded before sending message to prevent re-entry
                playerData?.markAdvancementAwarded(advancementId)

                player.sendSystemMessage(
                        Component.literal("§a+${result.amountFormatted} time granted for completing the advancement!")
                )
                dataManager.save()
                logger.info(
                        "Granted ${result.amountFormatted} to ${player.name.string} (${player.uuid}) " +
                                "for advancement ${advancementId}"
                )
            }
            AdvancementGrantResult.NoData -> {
                logger.warn(
                        "No data found for ${player.name.string} (${player.uuid}) when granting advancement time"
                )
            }
        }
    }
}
