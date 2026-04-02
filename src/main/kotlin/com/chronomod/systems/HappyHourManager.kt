package com.chronomod.systems

import java.util.concurrent.atomic.AtomicReference

/**
 * Manages the happy hour state machine.
 *
 * During happy hour:
 * - Players don't burn quota
 * - PvP transfers are multiplied (configurable)
 * - A countdown bossbar is displayed to all players
 */
class HappyHourManager {
    /**
     * Sealed class representing happy hour state.
     *
     * @property Inactive No happy hour is currently active
     * @property Active(endTimeEpochMs) Happy hour is active until the specified epoch milliseconds
     */
    sealed class HappyHourState {
        object Inactive : HappyHourState()

        data class Active(val endTimeEpochMs: Long) : HappyHourState()
    }

    private val state = AtomicReference<HappyHourState>(HappyHourState.Inactive)

    /**
     * Start a happy hour with the given duration.
     *
     * @param durationSeconds Duration of the happy hour in seconds
     * @return The new Active state
     */
    fun start(durationSeconds: Long): HappyHourState.Active {
        val endTime = System.currentTimeMillis() + (durationSeconds * 1000)
        val newState = HappyHourState.Active(endTime)
        state.set(newState)
        return newState
    }

    /**
     * End the current happy hour.
     */
    fun end() {
        state.set(HappyHourState.Inactive)
    }

    /**
     * Check if happy hour is currently active.
     *
     * @return true if happy hour is active and not yet expired
     */
    fun isActive(): Boolean {
        val currentState = state.get()
        if (currentState is HappyHourState.Active) {
            if (System.currentTimeMillis() < currentState.endTimeEpochMs) {
                return true
            } else {
                // Expired, transition to Inactive
                state.compareAndSet(currentState, HappyHourState.Inactive)
                return false
            }
        }
        return false
    }

    /**
     * Get remaining seconds of happy hour. Auto-expires if past end time.
     *
     * @return Remaining seconds, or 0 if inactive
     */
    fun getSafeRemainingSeconds(): Long {
        if (!isActive()) return 0
        val currentState = state.get()
        return if (currentState is HappyHourState.Active) {
            maxOf(0, (currentState.endTimeEpochMs - System.currentTimeMillis()) / 1000)
        } else {
            0
        }
    }

    /**
     * Get the current state without auto-expiring.
     *
     * @return The current HappyHourState
     */
    fun getState(): HappyHourState {
        return state.get()
    }
}
