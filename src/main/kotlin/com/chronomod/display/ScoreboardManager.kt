package com.chronomod.display

import com.chronomod.data.PlayerDataManager
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.numbers.BlankFormat
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetScorePacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.criteria.ObjectiveCriteria
import org.slf4j.Logger
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Manages the scoreboard display for player time quotas */
class ScoreboardManager(private val dataManager: PlayerDataManager, private val logger: Logger) {
    private var objective: Objective? = null

    /**
     * Packet deduplication cache: stores the last HH:MM:SS string sent to each player.
     * A new score packet is only sent when the formatted time actually changes, preventing
     * a redundant network packet every second when the value is unchanged (e.g. the player
     * is paused between seconds).  Entries are cleared when the player disconnects.
     */
    private val lastSentTime = ConcurrentHashMap<UUID, String>()

    companion object {
        private const val OBJECTIVE_NAME = "time_quota"

        /**
         * Owner name used for the per-player score packet entry.
         *
         * Using the Minecraft reset-formatting code "§r" produces a virtually
         * invisible owner name in vanilla rendering, which keeps the sidebar clean
         * because the actual displayed text is supplied via the packet's [display]
         * field override.  The value is the same for every player because each
         * player only receives their own packets and therefore only ever has one
         * entry in their local sidebar.
         */
        private const val SCORE_ENTRY_KEY = "§r"

        /** Color prefix applied to the HH:MM:SS time string (Minecraft gold). */
        private const val TIME_COLOR = "§6"
    }

    /** Register the scoreboard manager */
    fun register() {
        // Send per-player display packet when a player joins
        ServerPlayConnectionEvents.JOIN.register(
                ServerPlayConnectionEvents.Join { handler, _, _ ->
                    initPlayerDisplay(handler.player)
                }
        )

        // Clean up deduplication cache when a player disconnects
        ServerPlayConnectionEvents.DISCONNECT.register(
                ServerPlayConnectionEvents.Disconnect { handler, _ ->
                    lastSentTime.remove(handler.player.uuid)
                }
        )

        logger.info("ScoreboardManager registered")
    }

    /** Initialize the scoreboard on server start */
    fun initialize(server: MinecraftServer) {
        val scoreboard = server.scoreboard

        // Remove existing objective if it exists
        scoreboard.getObjective(OBJECTIVE_NAME)?.let { scoreboard.removeObjective(it) }

        // Create new objective – do NOT set a global display slot so that only
        // per-player display packets (sent in initPlayerDisplay) control visibility.
        objective =
                scoreboard.addObjective(
                        OBJECTIVE_NAME,
                        ObjectiveCriteria.DUMMY,
                        Component.literal("§6⏰ Time Remaining"),
                        ObjectiveCriteria.RenderType.INTEGER,
                        true,
                        null
                )

        logger.info("Scoreboard objective initialized")
    }

    /** Send this player their personal sidebar display and initial score */
    private fun initPlayerDisplay(player: ServerPlayer) {
        val currentObjective = objective ?: return

        // Show the objective in this player's sidebar only
        player.connection.send(
                ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, currentObjective)
        )

        // Send initial score immediately so the sidebar is populated on join
        sendPlayerScore(player)
    }

    /**
     * Update the sidebar display for [player].
     *
     * Called by [com.chronomod.systems.QuotaTracker] once per second after each quota burn,
     * so no independent tick loop is needed here.
     */
    fun updatePlayerDisplay(player: ServerPlayer) {
        sendPlayerScore(player)
    }

    /**
     * Send a score packet directly to [player] showing their quota in HH:MM:SS format.
     *
     * The packet uses a [BlankFormat] number format so no raw integer is shown, and a
     * [display][ClientboundSetScorePacket.display] component override to render the
     * formatted time string in the entry's left column.
     */
    private fun sendPlayerScore(player: ServerPlayer) {
        val currentObjective = objective ?: return
        val playerData = dataManager.get(player.uuid) ?: return

        val timeFormatted = playerData.formatRemainingTime()

        // Deduplication: skip sending a packet if the displayed value has not changed
        if (lastSentTime[player.uuid] == timeFormatted) return
        lastSentTime[player.uuid] = timeFormatted

        player.connection.send(
                ClientboundSetScorePacket(
                        SCORE_ENTRY_KEY,
                        currentObjective.name,
                        // Score integer is irrelevant because BlankFormat hides it; 0 is used
                        // as a stable sentinel that won't trigger unexpected client behavior.
                        0,
                        Optional.of(Component.literal("$TIME_COLOR$timeFormatted")),
                        Optional.of(BlankFormat.INSTANCE)
                )
        )
    }

    /** Force-refresh a specific player's scoreboard entry immediately */
    fun updatePlayerScore(server: MinecraftServer, playerUuid: UUID) {
        val player = server.playerList.getPlayer(playerUuid) ?: return
        // Clear deduplication cache so the packet is always sent
        lastSentTime.remove(playerUuid)
        sendPlayerScore(player)
    }
}

