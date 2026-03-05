package com.mindaplus.android

import android.util.Log

class StateConfirmation {
    companion object {
        private const val TAG = "StateConfirmation"
        private const val CONFIRMATION_TICKS = 2 // Number of consecutive ticks required
    }
    
    data class ConfirmationResult(
        val confirmedState: TransferState?,
        val pendingTicks: Int,
        val isStateChanged: Boolean
    )
    
    private val pendingConfirmations = mutableMapOf<Int, PendingState>()
    
    data class PendingState(
        var state: TransferState,
        var consecutiveTicks: Int,
        var lastConfirmedState: TransferState
    )
    
    fun processState(transferId: Int, detectedState: TransferState): ConfirmationResult {
        try {
            // Initialize pending state if not exists
            if (!pendingConfirmations.containsKey(transferId)) {
                pendingConfirmations[transferId] = PendingState(
                    state = TransferState.OK,
                    consecutiveTicks = 0,
                    lastConfirmedState = TransferState.OK
                )
            }
            
            val pending = pendingConfirmations[transferId]!!
            
            // Handle UNKNOWN state - do not modify counters
            if (detectedState == TransferState.UNKNOWN) {
                Log.d(TAG, "Transfer $transferId: UNKNOWN state detected, ignoring sample")
                return ConfirmationResult(
                    confirmedState = pending.lastConfirmedState,
                    pendingTicks = pending.consecutiveTicks,
                    isStateChanged = false
                )
            }
            
            // Check if this is the same state as pending
            if (detectedState == pending.state) {
                // Increment consecutive ticks
                pending.consecutiveTicks++
                Log.d(TAG, "Transfer $transferId: $detectedState consecutive ticks: ${pending.consecutiveTicks}")
                
                // Check if we have enough ticks for confirmation
                if (pending.consecutiveTicks >= CONFIRMATION_TICKS) {
                    val oldConfirmedState = pending.lastConfirmedState
                    val newConfirmedState = detectedState
                    
                    // Update confirmed state
                    pending.lastConfirmedState = detectedState
                    pending.consecutiveTicks = 0 // Reset counter
                    
                    val isStateChanged = oldConfirmedState != newConfirmedState
                    
                    Log.d(TAG, "Transfer $transferId: State confirmed - $newConfirmedState (changed: $isStateChanged)")
                    
                    return ConfirmationResult(
                        confirmedState = newConfirmedState,
                        pendingTicks = 0,
                        isStateChanged = isStateChanged
                    )
                } else {
                    // Still waiting for confirmation
                    return ConfirmationResult(
                        confirmedState = pending.lastConfirmedState,
                        pendingTicks = pending.consecutiveTicks,
                        isStateChanged = false
                    )
                }
            } else {
                // State changed, reset counter and update pending state
                Log.d(TAG, "Transfer $transferId: State changed from ${pending.state} to $detectedState, resetting counter")
                pending.state = detectedState
                pending.consecutiveTicks = 1 // Start counting from 1 for the new state
                
                return ConfirmationResult(
                    confirmedState = pending.lastConfirmedState,
                    pendingTicks = pending.consecutiveTicks,
                    isStateChanged = false
                )
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing state for transfer $transferId", e)
            return ConfirmationResult(
                confirmedState = null,
                pendingTicks = 0,
                isStateChanged = false
            )
        }
    }
    
    fun getConfirmedStates(): Map<Int, TransferState> {
        return pendingConfirmations.mapValues { it.value.lastConfirmedState }
    }
    
    fun getPendingTicks(transferId: Int): Int {
        return pendingConfirmations[transferId]?.consecutiveTicks ?: 0
    }
    
    fun reset() {
        pendingConfirmations.clear()
        Log.d(TAG, "All state confirmations reset")
    }
    
    fun resetTransfer(transferId: Int) {
        pendingConfirmations.remove(transferId)
        Log.d(TAG, "State confirmation reset for transfer $transferId")
    }
    
    fun getStatusString(transferId: Int): String {
        val pending = pendingConfirmations[transferId] ?: return "No data"
        return "Pending: ${pending.state} (${pending.consecutiveTicks}/$CONFIRMATION_TICKS), Confirmed: ${pending.lastConfirmedState}"
    }
}