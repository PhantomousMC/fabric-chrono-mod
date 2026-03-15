package com.chronomod.commands

import com.chronomod.data.PlayerDataManager
import com.mojang.brigadier.arguments.IntegerArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.slf4j.Logger

/** Handles registration of the /chrono command and its subcommands */
class ChronoCommand(private val dataManager: PlayerDataManager, private val logger: Logger) {

    /** Register the /chrono command */
    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("chrono")
                    .then(
                        Commands.literal("transfer")
                            .then(
                                Commands.argument("player", EntityArgument.player())
                                    .then(
                                        Commands.argument("minutes", IntegerArgumentType.integer(1))
                                            .executes { context ->
                                                val sender = context.source.playerOrException
                                                val target = EntityArgument.getPlayer(context, "player")
                                                val minutes = IntegerArgumentType.getInteger(context, "minutes")
                                                executeTransfer(sender, target, minutes.toLong())
                                            }
                                    )
                            )
                    )
            )
        }
        logger.info("ChronoCommand registered")
    }

    /** Execute the transfer subcommand */
    private fun executeTransfer(sender: ServerPlayer, target: ServerPlayer, minutes: Long): Int {
        if (sender.uuid == target.uuid) {
            sender.sendSystemMessage(Component.literal("§cYou cannot transfer quota to yourself."))
            return 0
        }

        val amountSeconds = minutes * 60L

        val senderData = dataManager.getOrCreate(sender.uuid)
        val targetData = dataManager.getOrCreate(target.uuid)

        if (senderData.remainingTimeSeconds < amountSeconds) {
            sender.sendSystemMessage(
                Component.literal(
                    "§cYou don't have enough quota. You have ${senderData.formatRemainingTime()} remaining."
                )
            )
            return 0
        }

        // Quota was already validated above, so transferred will equal amountSeconds
        val transferred = senderData.transferQuotaTo(targetData, amountSeconds)
        val transferredFormatted = formatSeconds(transferred)

        sender.sendSystemMessage(
            Component.literal(
                "§aTransferred $transferredFormatted to ${target.name.string}. You have ${senderData.formatRemainingTime()} remaining."
            )
        )
        target.sendSystemMessage(
            Component.literal(
                "§a+$transferredFormatted received from ${sender.name.string}. You have ${targetData.formatRemainingTime()} remaining."
            )
        )

        logger.info(
            "Quota transfer: ${sender.name.string} -> ${target.name.string}, " +
                "$transferredFormatted ($transferred s)"
        )

        dataManager.save()
        return 1
    }

    /** Format seconds as a human-readable time string */
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
