package com.chronomod.commands

import com.chronomod.config.ModConfig
import com.chronomod.data.PlayerDataManager
import com.chronomod.systems.BossbarTracker
import com.chronomod.systems.HappyHourManager
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.slf4j.Logger

/** Handles registration of the /chrono command and its subcommands */
class ChronoCommand(private val dataManager: PlayerDataManager, private val happyHourManager: HappyHourManager, private val bossbarTracker: BossbarTracker, private val config: ModConfig, private val logger: Logger) {

    /** Register the /chrono command */
    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("chrono")
                    .then(
                        Commands.literal("transfer")
                            .then(
                                Commands.argument("player", StringArgumentType.word())
                                    .suggests { context, builder -> buildPlayerSuggestions(context, builder) }
                                    .then(
                                        Commands.argument("minutes", IntegerArgumentType.integer(1))
                                            .executes { context ->
                                                val sender = context.source.playerOrException
                                                val targetName = StringArgumentType.getString(context, "player")
                                                val minutes = IntegerArgumentType.getInteger(context, "minutes")
                                                val server = context.source.server
                                                executeTransfer(sender, targetName, server, minutes.toLong())
                                            }
                                    )
                            )
                    )
                    .then(
                        Commands.literal("list")
                            .executes { context ->
                                val executor = context.source.playerOrException
                                val server = context.source.server
                                executeList(executor, server)
                            }
                    )
                    .then(
                        Commands.literal("balance")
                            .executes { context ->
                                val executor = context.source.playerOrException
                                val server = context.source.server
                                executeBalance(executor, executor, server)
                            }
                            .then(
                                Commands.argument("player", StringArgumentType.word())
                                    .suggests { context, builder -> buildPlayerSuggestions(context, builder) }
                                    .executes { context ->
                                        val executor = context.source.playerOrException
                                        val targetName = StringArgumentType.getString(context, "player")
                                        val server = context.source.server
                                        executeBalanceByName(executor, targetName, server)
                                    }
                            )
                    )
                    .then(
                        Commands.literal("add")
                            .then(
                                Commands.argument("player", StringArgumentType.word())
                                    .suggests { context, builder -> buildPlayerSuggestions(context, builder) }
                                    .then(
                                        Commands.argument("minutes", IntegerArgumentType.integer(1))
                                            .executes { context ->
                                                val executor = context.source.playerOrException
                                                val targetName = StringArgumentType.getString(context, "player")
                                                val minutes = IntegerArgumentType.getInteger(context, "minutes")
                                                val server = context.source.server
                                                executeAdd(executor, targetName, server, minutes.toLong())
                                            }
                                    )
                            )
                    )
                    .then(
                        Commands.literal("remove")
                            .then(
                                Commands.argument("player", StringArgumentType.word())
                                    .suggests { context, builder -> buildPlayerSuggestions(context, builder) }
                                    .then(
                                        Commands.argument("minutes", IntegerArgumentType.integer(1))
                                            .executes { context ->
                                                val executor = context.source.playerOrException
                                                val targetName = StringArgumentType.getString(context, "player")
                                                val minutes = IntegerArgumentType.getInteger(context, "minutes")
                                                val server = context.source.server
                                                executeRemove(executor, targetName, server, minutes.toLong())
                                            }
                                    )
                            )
                    )
                    .then(
                        Commands.literal("happyhour")
                            .then(
                                Commands.literal("start")
                                    .then(
                                        Commands.argument("minutes", IntegerArgumentType.integer(1))
                                            .executes { context ->
                                                val executor = context.source.playerOrException
                                                val minutes = IntegerArgumentType.getInteger(context, "minutes")
                                                val server = context.source.server
                                                executeHappyHourStart(executor, server, minutes.toLong())
                                            }
                                    )
                            )
                            .then(
                                Commands.literal("end")
                                    .executes { context ->
                                        val executor = context.source.playerOrException
                                        val server = context.source.server
                                        executeHappyHourEnd(executor, server)
                                    }
                            )
                    )
            )
        }
        logger.info("ChronoCommand registered")
    }

    /** Execute the transfer subcommand */
    private fun executeTransfer(sender: ServerPlayer, targetName: String, server: net.minecraft.server.MinecraftServer, minutes: Long): Int {
        val (targetPlayer, targetData) = dataManager.resolvePlayer(targetName, server)

        if (targetData == null) {
            sender.sendSystemMessage(Component.literal("§cPlayer '$targetName' not found."))
            return 0
        }

        if (sender.uuid == targetData.uuid) {
            sender.sendSystemMessage(Component.literal("§cYou cannot transfer quota to yourself."))
            return 0
        }

        val amountSeconds = minutes * 60L
        val senderData = dataManager.getOrCreate(sender.uuid)

        if (senderData.remainingTimeSeconds < amountSeconds) {
            sender.sendSystemMessage(
                Component.literal(
                    "§cYou don't have enough quota. You have ${senderData.formatRemainingTime()} remaining."
                )
            )
            return 0
        }

        val wasRevived = targetData.remainingTimeSeconds == 0L
        val transferred = senderData.transferQuotaTo(targetData, amountSeconds)
        val transferredFormatted = formatSeconds(transferred)
        val displayName = if (targetData.username.isNotEmpty()) targetData.username else targetName

        sender.sendSystemMessage(
            Component.literal(
                "§aTransferred $transferredFormatted to $displayName. You have ${senderData.formatRemainingTime()} remaining."
            )
        )

        if (targetPlayer != null) {
            targetPlayer.sendSystemMessage(
                Component.literal(
                    "§a+$transferredFormatted received from ${sender.name.string}. You have ${targetData.formatRemainingTime()} remaining."
                )
            )
        } else {
            targetData.pendingNotifications.add(
                "§aWhile you were offline, §e${sender.name.string}§a transferred §e$transferredFormatted§a to you! You now have §e${targetData.formatRemainingTime()}§a remaining."
            )
            if (wasRevived) {
                targetData.pendingNotifications.add(
                    "§aYou were revived! You can now rejoin the server."
                )
                sender.sendSystemMessage(
                    Component.literal("§a✦ $displayName has been revived and can now rejoin the server!")
                )
            }
        }

        logger.info("Quota transfer: ${sender.name.string} -> $displayName, $transferredFormatted ($transferred s)")

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

    /** Execute the list subcommand - show all players' remaining time */
    private fun executeList(executor: ServerPlayer, server: net.minecraft.server.MinecraftServer): Int {
        val allPlayers = dataManager.getAll()
        
        if (allPlayers.isEmpty()) {
            executor.sendSystemMessage(Component.literal("§cNo player data available."))
            return 1
        }

        // Sort by player name for readability
        val playerList = allPlayers
            .mapNotNull { data -> 
                val playerUuid = data.uuid
                // Try to get the online player's name; fall back to stored username, then UUID prefix
                val playerName = server.playerList.getPlayer(playerUuid)?.name?.string
                    ?: data.username.ifEmpty { playerUuid.toString().substring(0, 8) }
                Pair(playerName, data)
            }
            .sortedBy { it.first }

        // Build message
        val messageLines = mutableListOf("§6=== Player Time Quotas ===")
        for ((name, data) in playerList) {
            val timeStr = if (data.remainingTimeSeconds == 0L) "§c${data.formatRemainingTime()}" else "§a${data.formatRemainingTime()}"
            messageLines.add("§f$name: $timeStr")
        }

        // Send each line as a separate message
        for (line in messageLines) {
            executor.sendSystemMessage(Component.literal(line))
        }

        return 1
    }

    /** Execute the balance subcommand - show a player's remaining time */
    private fun executeBalance(executor: ServerPlayer, target: ServerPlayer, server: net.minecraft.server.MinecraftServer): Int {
        val playerData = dataManager.get(target.uuid)

        if (playerData == null) {
            executor.sendSystemMessage(
                Component.literal("§c${target.name.string} has no quota data yet.")
            )
            return 0
        }

        val isOwnBalance = executor.uuid == target.uuid
        val message = if (isOwnBalance) {
            "§fYour remaining time: §a${playerData.formatRemainingTime()}"
        } else {
            "§f${target.name.string}'s remaining time: §a${playerData.formatRemainingTime()}"
        }

        executor.sendSystemMessage(Component.literal(message))
        return 1
    }

    /** Execute the balance subcommand for a named player (online or offline) */
    private fun executeBalanceByName(executor: ServerPlayer, targetName: String, server: net.minecraft.server.MinecraftServer): Int {
        val (_, targetData) = dataManager.resolvePlayer(targetName, server)

        if (targetData == null) {
            executor.sendSystemMessage(Component.literal("§cPlayer '$targetName' not found."))
            return 0
        }

        val displayName = if (targetData.username.isNotEmpty()) targetData.username else targetName
        executor.sendSystemMessage(
            Component.literal("§f$displayName's remaining time: §a${targetData.formatRemainingTime()}")
        )
        return 1
    }

    /** Execute the add subcommand - admin command to grant time to a player */
    private fun executeAdd(executor: ServerPlayer, targetName: String, server: net.minecraft.server.MinecraftServer, minutes: Long): Int {
        // Check if executor is an operator
        val isOperator = try {
            server.playerList.ops.file?.let { opsFile ->
                opsFile.exists() && opsFile.readText().contains(executor.uuid.toString())
            } ?: false
        } catch (e: Exception) {
            logger.warn("Error checking operator status: ${e.message}")
            false
        }
        
        if (!isOperator) {
            executor.sendSystemMessage(Component.literal("§cYou must be an operator to use this command."))
            return 0
        }

        val (targetPlayer, targetData) = dataManager.resolvePlayer(targetName, server)

        if (targetData == null) {
            executor.sendSystemMessage(Component.literal("§cPlayer '$targetName' not found."))
            return 0
        }

        val amountSeconds = minutes * 60L
        val timeBefore = targetData.formatRemainingTime()
        val wasRevived = targetData.remainingTimeSeconds == 0L
        val displayName = if (targetData.username.isNotEmpty()) targetData.username else targetName

        targetData.addTime(amountSeconds)
        val timeAfter = targetData.formatRemainingTime()

        executor.sendSystemMessage(
            Component.literal(
                "§aAdded ${formatSeconds(amountSeconds)} to $displayName's quota. " +
                "Before: $timeBefore → After: $timeAfter"
            )
        )
        
        if (targetPlayer != null) {
            targetPlayer.sendSystemMessage(
                Component.literal(
                    "§aAn admin added ${formatSeconds(amountSeconds)} to your quota! " +
                    "Your new total: $timeAfter"
                )
            )
        } else {
            targetData.pendingNotifications.add(
                "§aAn admin added §e${formatSeconds(amountSeconds)}§a to your quota while you were offline. Your new total: §e$timeAfter"
            )
            if (wasRevived) {
                targetData.pendingNotifications.add(
                    "§aYou were revived! You can now rejoin the server."
                )
                executor.sendSystemMessage(
                    Component.literal("§a✦ $displayName has been revived and can now rejoin the server!")
                )
            }
        }

        logger.info(
            "Admin time grant: ${executor.name.string} added ${formatSeconds(amountSeconds)} " +
            "to $displayName's quota ($timeBefore → $timeAfter)"
        )

        dataManager.save()
        return 1
    }

    /** Execute the remove subcommand - admin command to take time from a player */
    private fun executeRemove(executor: ServerPlayer, targetName: String, server: net.minecraft.server.MinecraftServer, minutes: Long): Int {
        // Check if executor is an operator
        val isOperator = try {
            server.playerList.ops.file?.let { opsFile ->
                opsFile.exists() && opsFile.readText().contains(executor.uuid.toString())
            } ?: false
        } catch (e: Exception) {
            logger.warn("Error checking operator status: ${e.message}")
            false
        }
        
        if (!isOperator) {
            executor.sendSystemMessage(Component.literal("§cYou must be an operator to use this command."))
            return 0
        }

        val (targetPlayer, targetData) = dataManager.resolvePlayer(targetName, server)

        if (targetData == null) {
            executor.sendSystemMessage(Component.literal("§cPlayer '$targetName' not found."))
            return 0
        }

        val amountSeconds = minutes * 60L
        val displayName = if (targetData.username.isNotEmpty()) targetData.username else targetName
        val timeBefore = targetData.formatRemainingTime()

        // Check if player has enough time
        if (targetData.remainingTimeSeconds < amountSeconds) {
            executor.sendSystemMessage(
                Component.literal(
                    "§c$displayName doesn't have enough quota to remove. " +
                    "They have $timeBefore remaining, but you tried to remove ${formatSeconds(amountSeconds)}."
                )
            )
            return 0
        }

        targetData.decrementQuota(amountSeconds)
        val timeAfter = targetData.formatRemainingTime()

        executor.sendSystemMessage(
            Component.literal(
                "§aRemoved ${formatSeconds(amountSeconds)} from $displayName's quota. " +
                "Before: $timeBefore → After: $timeAfter"
            )
        )
        
        if (targetPlayer != null) {
            targetPlayer.sendSystemMessage(
                Component.literal(
                    "§cAn admin removed ${formatSeconds(amountSeconds)} from your quota. " +
                    "Your new total: $timeAfter"
                )
            )
        } else {
            targetData.pendingNotifications.add(
                "§cAn admin removed §e${formatSeconds(amountSeconds)}§c from your quota while you were offline. Your new total: §e$timeAfter"
            )
        }

        logger.info(
            "Admin time removal: ${executor.name.string} removed ${formatSeconds(amountSeconds)} " +
            "from $displayName's quota ($timeBefore → $timeAfter)"
        )

        dataManager.save()
        return 1
    }

    /** Build tab-completion suggestions for player arguments (online + known offline) */
    private fun buildPlayerSuggestions(
        context: com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack>,
        builder: com.mojang.brigadier.suggestion.SuggestionsBuilder
    ): java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> {
        val server = context.source.server
        val onlineNames = server.playerList.players.map { it.name.string }.toSet()
        val remaining = builder.remaining.lowercase()

        val suggestions = mutableSetOf<String>()
        suggestions.addAll(onlineNames)
        dataManager.getAll().forEach { data ->
            if (data.username.isNotEmpty()) suggestions.add(data.username)
        }

        suggestions
            .filter { it.lowercase().startsWith(remaining) }
            .sorted()
            .forEach { builder.suggest(it) }

        return builder.buildFuture()
    }

    /** Execute the happy hour start subcommand */
    private fun executeHappyHourStart(executor: ServerPlayer, server: net.minecraft.server.MinecraftServer, minutes: Long): Int {
        // Check if executor is an operator
        val isOperator = try {
            server.playerList.ops.file?.let { opsFile ->
                opsFile.exists() && opsFile.readText().contains(executor.uuid.toString())
            } ?: false
        } catch (e: Exception) {
            logger.warn("Error checking operator status: ${e.message}")
            false
        }
        
        if (!isOperator) {
            executor.sendSystemMessage(Component.literal("§cYou must be an operator to use this command."))
            return 0
        }

        if (minutes < 1) {
            executor.sendSystemMessage(Component.literal("§cDuration must be at least 1 minute."))
            return 0
        }

        val durationSeconds = minutes * 60
        happyHourManager.start(durationSeconds)
        bossbarTracker.createBossbar(durationSeconds, server)

        // Build title and subtitle components
        val titleComponent = Component.literal("§6Happy Hour!")
        val subtitleComponent = Component.literal("§aNo quota burn • §bPvP 2x transfer • §6Bossbar shows duration")

        // Send title packets to all online players
        for (player in server.playerList.players) {
            player.connection.send(net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(titleComponent))
            player.connection.send(net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(subtitleComponent))
            player.connection.send(net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(10, 60, 10))
        }

        // Send chat message to all players
        val chatMessage = "§6✦ Happy Hour started! Duration: $minutes minute(s). §aNo quota burn • §bPvP transfers are 2x!"
        for (player in server.playerList.players) {
            player.sendSystemMessage(Component.literal(chatMessage))
        }

        logger.info("Happy Hour started by ${executor.name.string} for $minutes minute(s)")
        return 1
    }

    /** Execute the happy hour end subcommand */
    private fun executeHappyHourEnd(executor: ServerPlayer, server: net.minecraft.server.MinecraftServer): Int {
        // Check if executor is an operator
        val isOperator = try {
            server.playerList.ops.file?.let { opsFile ->
                opsFile.exists() && opsFile.readText().contains(executor.uuid.toString())
            } ?: false
        } catch (e: Exception) {
            logger.warn("Error checking operator status: ${e.message}")
            false
        }
        
        if (!isOperator) {
            executor.sendSystemMessage(Component.literal("§cYou must be an operator to use this command."))
            return 0
        }

        happyHourManager.end()
        bossbarTracker.removeBossbarForAll(server)

        // Send chat message to all players
        val chatMessage = "§c✦ Happy Hour ended. Quota burning has resumed."
        for (player in server.playerList.players) {
            player.sendSystemMessage(Component.literal(chatMessage))
        }

        logger.info("Happy Hour ended by ${executor.name.string}")
        return 1
    }
}
