package com.mindaplus.android

object MonitoringMode {
    // Temporal field-validation mode: single lane/state subset.
    const val singleLaneTestMode: Boolean = true

    val enabledTransfers: List<Int> = if (singleLaneTestMode) {
        listOf(100)
    } else {
        listOf(100, 200, 300)
    }

    val enabledTrainingStates: List<TransferState> = if (singleLaneTestMode) {
        listOf(TransferState.OK, TransferState.OBSTACULO)
    } else {
        listOf(TransferState.OK, TransferState.OBSTACULO, TransferState.FALLO)
    }

    val issueStatesForAlerts: Set<TransferState> = if (singleLaneTestMode) {
        setOf(TransferState.OBSTACULO)
    } else {
        setOf(TransferState.OBSTACULO, TransferState.FALLO)
    }

    // Focus strategy for template visual matching in temporal mode.
    val focusedSubRoiEnabled: Boolean = singleLaneTestMode
    const val focusedSubRoiWidthRatio: Float = 0.52f
    const val focusedSubRoiHeightRatio: Float = 0.72f

    // Manager/superuser tooling flags (temporary in development).
    const val managerToolsEnabled: Boolean = true
    const val debugPanelEnabled: Boolean = managerToolsEnabled

    fun isTransferEnabled(transferId: Int): Boolean = transferId in enabledTransfers
    fun isStateEnabled(state: TransferState): Boolean = state in enabledTrainingStates
}
