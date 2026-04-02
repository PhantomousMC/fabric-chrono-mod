package com.chronomod.systems

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import org.slf4j.Logger

/**
 * Manages the happy hour countdown display via action bar messages.
 *
 * - Displays countdown via persistent action bar messages every 1 second
 * - Shows remaining time in minutes and seconds format with visual progress ▓
 * - Automatically shows players who join during happy hour
 * - Updates frequently (1 second) for smooth countdown experience
 */
class BossbarTracker(
    private val happyHourManager: HappyHourManager,
    private val logger: Logger,
) {
    private var tickCounter: Int = 0
    private var lastDisplayedTime: Long = -1L
    private val playerUuidsWithBossbar = ConcurrentHashMap.newKeySet<UUID>()

    /** Register event listeners. */
    fun register() {
        ServerTickEvents.END_SERVER_TICK.register(::onEndServerTick)
    }

    /** Update the countdown display every 1 second (20 ticks). */
    private fun onEndServerTick(server: MinecraftServer) {
        tickCounter++
        if (tickCounter < 20) return
        tickCounter = 0

        update(server)
    }

    /** Create a bossbar for the happy hour and send to all online players. */
    fun createBossbar(durationSeconds: Long, server: MinecraftServer) {
        lastDisplayedTime = durationSeconds

        val minutes = durationSeconds / 60
        val seconds = durationSeconds % 60
        val timeStr = if (minutes > 0) {
            "$minutes:${String.format("%02d", seconds)}"
        } else {
            "${seconds}s"
        }

        val progressBar = "§6" + "▓".repeat((durationSeconds / 6).toInt().coerceAtMost(20))

        val message = Component.literal("§6▶ Happy Hour: §e$timeStr§6 ▓▓▓▓▓▓▓▓▓▓")

        // Send to all online players
        for (player in server.playerList.players) {
            player.sendSystemMessage(message, true)
            playerUuidsWithBossbar.add(player.uuid)
        }

        logger.info("Created happy hour countdown for $durationSeconds seconds")
    }

    /** Update countdown display on all action bar-enabled players. */
    private fun update(server: MinecraftServer) {
        if (!happyHourManager.isActive() || playerUuidsWithBossbar.isEmpty()) {
            if (playerUuidsWithBossbar.isNotEmpty()) {
                removeBossbarForAll(server)
            }
            return
        }

        val remaining = happyHourManager.getSafeRemainingSeconds()
        
        // Skip display if time hasn't visibly changed
        if (remaining == lastDisplayedTime) {
            return
        }

        lastDisplayedTime = remaining

        val minutes = remaining / 60
        val seconds = remaining % 60
        val quarterFilled = (remaining / 3).toInt() % 4

        val progressChars = when {
            remaining > 10 -> "▓▓▓▓▓▓▓▓▓▓"
            remaining > 8 -> "▓▓▓▓▓▓▓▓▒░"
            remaining > 6 -> "▓▓▓▓▓▓▒░░░"
            remaining > 4 -> "▓▓▓▓▒░░░░░"
            remaining > 2 -> "▓▓▒░░░░░░░"
            else -> "§c▓▒░░░░░░░░"
        }

        val timeStr = if (minutes > 0) {
            "$minutes:${String.format("%02d", seconds)}"
        } else {
            "${seconds}s"
        }

        val message = if (remaining <= 5) {
            Component.literal("§c▶ Happy Hour: §e$timeStr§c $progressChars")
        } else {
            Component.literal("§6▶ Happy Hour: §e$timeStr§6 $progressChars")
        }

        // Send to all players with countdown active
        for (player in server.playerList.players) {
            if (playerUuidsWithBossbar.contains(player.uuid)) {
                player.sendSystemMessage(message, true)
            }
        }
    }

    /** Remove bossbar from all players. */
    fun removeBossbarForAll(server: MinecraftServer) {
        // Clear action bars by sending empty message
        val emptyMessage = Component.literal("")
        for (player in server.playerList.players) {
            if (playerUuidsWithBossbar.contains(player.uuid)) {
                player.sendSystemMessage(emptyMessage, true)
            }
        }

        playerUuidsWithBossbar.clear()
        lastDisplayedTime = -1L
        logger.info("Removed happy hour countdown from all players")
    }

    /** Send bossbar to a player who just joined during happy hour. */
    fun showToPlayer(playerUuid: UUID, server: MinecraftServer) {
        if (!happyHourManager.isActive() || playerUuidsWithBossbar.isEmpty()) {
            return // No active happy hour
        }

        val player = server.playerList.getPlayer(playerUuid) ?: return

        val remaining = happyHourManager.getSafeRemainingSeconds()
        val minutes = remaining / 60
        val seconds = remaining % 60

        val timeStr = if (minutes > 0) {
            "$minutes:${String.format("%02d", seconds)}"
        } else {
            "${seconds}s"
        }

        val message = Component.literal("§6▶ Happy Hour: §e$timeStr§6 remaining")
        player.sendSystemMessage(message, true)
        playerUuidsWithBossbar.add(playerUuid)
        logger.info("Showed happy hour countdown to joining player: $playerUuid")
    }

    /** Remove bossbar from a specific player on disconnect. */
    fun removeFromPlayer(playerUuid: UUID, server: MinecraftServer) {
        if (!playerUuidsWithBossbar.contains(playerUuid)) {
            return // Player doesn't have countdown
        }

        val player = server.playerList.getPlayer(playerUuid)
        player?.sendSystemMessage(Component.literal(""), true)

        playerUuidsWithBossbar.remove(playerUuid)
    }
}


