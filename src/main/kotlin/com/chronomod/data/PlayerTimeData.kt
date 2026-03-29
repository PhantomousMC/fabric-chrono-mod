package com.chronomod.data

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Represents a player's time quota data.
 *
 * @property uuid Player's unique identifier
 * @property remainingTimeSeconds Remaining quota in seconds
 * @property lastWeeklyAllotment Timestamp when last weekly allotment was received
 */
@Serializable
data class PlayerTimeData(
        @Contextual val uuid: UUID,
        var remainingTimeSeconds: Long,
        @Contextual var lastWeeklyAllotment: Instant
) {
    /**
     * Session-scoped set of advancement IDs that have already granted time.
     * Not serialized to JSON — resets per server restart/player disconnect.
     */
    @Transient
    private val awardedAdvancements: MutableSet<String> = mutableSetOf()
    companion object {
        // Legacy constants - kept for reference only
        // Actual values should be loaded from ModConfig
        @Deprecated("Use ModConfig.initialQuotaSeconds instead")
        const val INITIAL_QUOTA_SECONDS = 8L * 60 * 60 // 8 hours
        @Deprecated("Use ModConfig.periodicAllotmentSeconds instead")
        const val WEEKLY_ALLOTMENT_SECONDS = 8L * 60 * 60 // 8 hours
        @Deprecated("Use ModConfig.pvpTransferSeconds instead")
        const val PVP_TRANSFER_SECONDS = 1L * 60 * 60 // 1 hour
        @Deprecated("Use ModConfig.allotmentPeriodLength instead")
        const val WEEK_IN_SECONDS = 7L * 24 * 60 * 60 // 7 days

        /**
         * Creates a new player data with the given initial quota.
         *
         * @param uuid Player's unique identifier
         * @param initialQuotaSeconds Starting quota in seconds
         */
        fun createNew(uuid: UUID, initialQuotaSeconds: Long): PlayerTimeData {
            return PlayerTimeData(
                    uuid = uuid,
                    remainingTimeSeconds = initialQuotaSeconds,
                    lastWeeklyAllotment = Instant.now()
            )
        }
    }

    /**
     * Check if the player is eligible for an allotment based on a configurable period.
     *
     * @param allotmentPeriodLength Period length in seconds
     */
    fun isEligibleForAllotment(allotmentPeriodLength: Long): Boolean {
        val timeSinceLastAllotment = Instant.now().epochSecond - lastWeeklyAllotment.epochSecond
        return timeSinceLastAllotment >= allotmentPeriodLength
    }

    /**
     * Grant an allotment of the given size.
     *
     * @param allotmentSeconds Amount to add in seconds
     */
    fun grantAllotment(allotmentSeconds: Long) {
        remainingTimeSeconds += allotmentSeconds
        lastWeeklyAllotment = Instant.now()
    }

    /**
     * Decrease quota by specified seconds
     * @return true if player still has time, false if depleted
     */
    fun decrementQuota(seconds: Long = 1): Boolean {
        remainingTimeSeconds = maxOf(0, remainingTimeSeconds - seconds)
        return remainingTimeSeconds > 0
    }

    /**
     * Add quota from an advancement reward. Does not update [lastWeeklyAllotment].
     * @param seconds Amount to add in seconds
     */
    fun addTime(seconds: Long) {
        remainingTimeSeconds += seconds
    }

    /**
     * Transfer quota to another player (used in PvP)
     * @param other The player to transfer quota to
     * @param amount Amount of quota to transfer in seconds
     * @return actual amount transferred (may be less than requested if not enough quota)
     */
    fun transferQuotaTo(other: PlayerTimeData, amount: Long): Long {
        val actualTransfer = minOf(remainingTimeSeconds, amount)
        remainingTimeSeconds -= actualTransfer
        other.remainingTimeSeconds += actualTransfer
        return actualTransfer
    }

    /** Format remaining time as HH:MM:SS */
    fun formatRemainingTime(): String {
        val hours = remainingTimeSeconds / 3600
        val minutes = (remainingTimeSeconds % 3600) / 60
        val seconds = remainingTimeSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    /**
     * Check if an advancement has already been awarded this session.
     * @param advancementId Advancement identifier
     */
    fun hasAwardedAdvancement(advancementId: String): Boolean {
        return awardedAdvancements.contains(advancementId)
    }

    /**
     * Mark an advancement as awarded this session.
     * @param advancementId Advancement identifier
     */
    fun markAdvancementAwarded(advancementId: String) {
        awardedAdvancements.add(advancementId)
    }

    /**
     * Clear awarded advancements set (called on disconnect to free memory).
     */
    fun clearAwardedAdvancements() {
        awardedAdvancements.clear()
    }
}
