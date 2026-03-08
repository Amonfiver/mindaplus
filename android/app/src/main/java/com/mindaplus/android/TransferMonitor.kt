package com.mindaplus.android

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.util.Log

class TransferMonitor(
    private val context: android.content.Context
) {
    companion object {
        private const val TAG = "TransferMonitor"
        private val ALL_TRANSFERS = listOf(100, 200, 300)
    }
    
    // Core components
    private val laneDetector = LaneDetector()
    private val templateStorage = TemplateStorage(context)
    private val templateClassifier = TemplateClassifier(templateStorage)
    private val stateConfirmation = StateConfirmation()

    fun analyzeFrame(bitmap: Bitmap): Map<Int, TransferState> {
        try {
            val analysisTs = System.currentTimeMillis()
            Log.d(TAG, "Analyzing bitmap frame ${bitmap.width}x${bitmap.height} at ts=$analysisTs")

            // Step 1: Auto-detect lanes (calibrate once and freeze)
            val laneDetection = laneDetector.detectLanes(bitmap)

            if (laneDetection.requiresCalibration || laneDetection.lanes == null) {
                Log.w(TAG, "Lane detection failed or requires calibration")
                return getDefaultStates()
            }

            val lanes = laneDetection.lanes
            if (lanes.size != 3) {
                Log.w(TAG, "Expected 3 lanes, got ${lanes.size}")
                return getDefaultStates()
            }

            Log.d(TAG, "Detected ${lanes.size} lanes successfully")

            // Step 2: Classify each lane using template matching
            val results = mutableMapOf<Int, TransferState>()
            val transferIds = MonitoringMode.enabledTransfers

            for (transferId in transferIds) {
                val laneIndex = ALL_TRANSFERS.indexOf(transferId)
                if (laneIndex < 0 || laneIndex >= lanes.size) {
                    Log.w(TAG, "Transfer $transferId skipped: lane index $laneIndex out of range (${lanes.size})")
                    results[transferId] = TransferState.UNKNOWN
                    continue
                }
                val lane = lanes[laneIndex]
                val laneCenterY = (lane.top + lane.bottom) / 2f
                val laneCenterNorm = laneCenterY / bitmap.height.toFloat()
                Log.d(
                    TAG,
                    "Transfer $transferId laneContext bitmap top=${lane.top} bottom=${lane.bottom} centerY=${"%.1f".format(laneCenterY)} centerYNorm=${"%.4f".format(laneCenterNorm)}"
                )

                // Classify the ROI
                val classification = templateClassifier.classifyROI(
                    bitmap, lane, transferId
                )

                Log.d(TAG, "Transfer $transferId: detected=${classification.state}, similarity=${classification.similarity}, confident=${classification.isConfident}")

                // Use detected state only if confident, otherwise UNKNOWN
                val detectedState = if (classification.isConfident) {
                    classification.state
                } else {
                    TransferState.UNKNOWN
                }

                // Step 3: Apply 2-tick confirmation logic
                val confirmation = stateConfirmation.processState(transferId, detectedState)

                Log.d(TAG, "Transfer $transferId: confirmed=${confirmation.confirmedState}, pending=${confirmation.pendingTicks}, changed=${confirmation.isStateChanged}")

                // Use confirmed state, or previous state if not confirmed
                results[transferId] = confirmation.confirmedState ?: TransferState.UNKNOWN
            }

            for (transferId in ALL_TRANSFERS) {
                if (!MonitoringMode.isTransferEnabled(transferId)) {
                    results[transferId] = TransferState.UNKNOWN
                }
            }

            Log.d(TAG, "Bitmap frame analysis complete at ts=$analysisTs: $results")
            return results

        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing bitmap frame", e)
            return getDefaultStates()
        }
    }
    
    fun analyzeFrame(image: Image, imageWidth: Int, imageHeight: Int): Map<Int, TransferState> {
        try {
            val analysisTs = System.currentTimeMillis()
            Log.d(TAG, "Analyzing frame ${imageWidth}x${imageHeight} at ts=$analysisTs")
            
            if (image.format != ImageFormat.YUV_420_888) {
                Log.w(TAG, "Unsupported image format: ${image.format}")
                return getDefaultStates()
            }
            
            // Step 1: Auto-detect lanes (calibrate once and freeze)
            val laneDetection = laneDetector.detectLanes(image, imageWidth, imageHeight)
            
            if (laneDetection.requiresCalibration || laneDetection.lanes == null) {
                Log.w(TAG, "Lane detection failed or requires calibration")
                return getDefaultStates()
            }
            
            val lanes = laneDetection.lanes
            if (lanes.size != 3) {
                Log.w(TAG, "Expected 3 lanes, got ${lanes.size}")
                return getDefaultStates()
            }
            
            Log.d(TAG, "Detected ${lanes.size} lanes successfully")
            
            // Step 2: Classify each lane using template matching
            val results = mutableMapOf<Int, TransferState>()
            val transferIds = MonitoringMode.enabledTransfers
            
            for (transferId in transferIds) {
                val laneIndex = ALL_TRANSFERS.indexOf(transferId)
                if (laneIndex < 0 || laneIndex >= lanes.size) {
                    Log.w(TAG, "Transfer $transferId skipped: lane index $laneIndex out of range (${lanes.size})")
                    results[transferId] = TransferState.UNKNOWN
                    continue
                }
                val lane = lanes[laneIndex]
                val laneCenterY = (lane.top + lane.bottom) / 2f
                val laneCenterNorm = laneCenterY / imageHeight.toFloat()
                Log.d(
                    TAG,
                    "Transfer $transferId laneContext yuv top=${lane.top} bottom=${lane.bottom} centerY=${"%.1f".format(laneCenterY)} centerYNorm=${"%.4f".format(laneCenterNorm)}"
                )
                
                // Classify the ROI
                val classification = templateClassifier.classifyROI(
                    image, imageWidth, imageHeight, lane, transferId
                )
                
                Log.d(TAG, "Transfer $transferId: detected=${classification.state}, similarity=${classification.similarity}, confident=${classification.isConfident}")
                
                // Use detected state only if confident, otherwise UNKNOWN
                val detectedState = if (classification.isConfident) {
                    classification.state
                } else {
                    TransferState.UNKNOWN
                }
                
                // Step 3: Apply 2-tick confirmation logic
                val confirmation = stateConfirmation.processState(transferId, detectedState)
                
                Log.d(TAG, "Transfer $transferId: confirmed=${confirmation.confirmedState}, pending=${confirmation.pendingTicks}, changed=${confirmation.isStateChanged}")
                
                // Use confirmed state, or previous state if not confirmed
                results[transferId] = confirmation.confirmedState ?: TransferState.UNKNOWN
            }

            for (transferId in ALL_TRANSFERS) {
                if (!MonitoringMode.isTransferEnabled(transferId)) {
                    results[transferId] = TransferState.UNKNOWN
                }
            }
            
            Log.d(TAG, "Frame analysis complete at ts=$analysisTs: $results")
            return results
            
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing frame", e)
            return getDefaultStates()
        }
    }
    
    fun isTrained(): Boolean {
        return templateStorage.isTrained()
    }
    
    fun getTrainingProgress(): Pair<Int, Int> {
        return templateStorage.getTrainingProgress()
    }

    fun getTrainingStats(): TemplateStorage.TrainingStats {
        return templateStorage.getTrainingStats()
    }

    fun addLabeledSample(transferId: Int, state: TransferState, bitmap: Bitmap): Boolean {
        return addLabeledSample(transferId, state, bitmap, null, bitmap.width, bitmap.height)
    }

    fun addLabeledSample(
        transferId: Int,
        state: TransferState,
        bitmap: Bitmap,
        laneRegion: LaneDetector.LaneRegion?,
        frameWidth: Int,
        frameHeight: Int
    ): Boolean {
        if (state == TransferState.UNKNOWN) {
            Log.w(TAG, "Rejected labeled sample for UNKNOWN state")
            return false
        }
        if (!MonitoringMode.isTransferEnabled(transferId)) {
            Log.w(TAG, "Rejected labeled sample for disabled transfer T$transferId (singleLaneTestMode=${MonitoringMode.singleLaneTestMode})")
            return false
        }
        if (!MonitoringMode.isStateEnabled(state)) {
            Log.w(TAG, "Rejected labeled sample for disabled state $state (singleLaneTestMode=${MonitoringMode.singleLaneTestMode})")
            return false
        }

        val spatialMetadata = laneRegion?.let {
            TemplateStorage.SpatialMetadata(
                left = it.left,
                top = it.top,
                right = it.right,
                bottom = it.bottom,
                frameWidth = frameWidth,
                frameHeight = frameHeight
            )
        }

        val success = templateStorage.saveTemplate(transferId, state, bitmap, spatialMetadata)
        Log.d(
            TAG,
            "Labeled sample save result transfer=$transferId state=$state success=$success roi=${laneRegion ?: "legacy/no-roi"} frame=${frameWidth}x${frameHeight}"
        )
        return success
    }
    
    fun clearTraining() {
        templateStorage.clearAllTemplates()
        laneDetector.clearCache()
        stateConfirmation.reset()
        Log.d(TAG, "Training data cleared")
    }
    
    fun recalibrateLanes() {
        laneDetector.clearCache()
        stateConfirmation.reset()
        Log.d(TAG, "Lane calibration reset")
    }
    
    private fun getDefaultStates(): Map<Int, TransferState> {
        return mapOf(
            100 to TransferState.UNKNOWN,
            200 to TransferState.UNKNOWN,
            300 to TransferState.UNKNOWN
        )
    }
}
