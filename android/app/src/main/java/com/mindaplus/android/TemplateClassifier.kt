package com.mindaplus.android

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Classifier that compares current ROI against stored templates using visual and spatial similarity.
 * Uses unified geometric canonization pipeline: both current frame and reference templates
 * pass through the same temporal focus + resize transformation before comparison.
 * This ensures geometric consistency regardless of how templates were originally captured.
 */
class TemplateClassifier(private val templateStorage: TemplateStorage) {
    companion object {
        private const val TAG = "TemplateClassifier"
        private const val TEMPLATE_WIDTH = 128
        private const val TEMPLATE_HEIGHT = 64
        private const val SIMILARITY_THRESHOLD = 0.70f
        private const val AMBIGUITY_MARGIN = 0.035f
        private const val VISUAL_WEIGHT = 0.78f
        private const val SPATIAL_WEIGHT = 0.22f
        private const val LEGACY_NO_SPATIAL_PENALTY = 0.97f
        private const val ALERT_MATCH_THRESHOLD = 0.74f
        private const val ALERT_OVER_OK_MARGIN = 0.02f
        private const val OK_BASELINE_MIN = 0.58f
        private const val LANE_COHERENCE_MIN_FOR_OK = 0.62f
        private const val MAX_CENTER_NORM_DELTA = 0.35f
        private const val MAX_HEIGHT_NORM_DELTA = 0.20f
        private const val STRUCTURAL_WEIGHT = 0.55f
        private const val COLOR_WEIGHT = 0.45f
        private const val BACKGROUND_LOW_WEIGHT = 0.35f
    }

    data class StateDebug(
        val sampleCount: Int,
        val bestVisual: Float,
        val bestStructural: Float,
        val bestColor: Float,
        val bestSpatial: Float,
        val laneAdjusted: Float
    )

    data class ClassificationResult(
        val state: TransferState,
        val similarity: Float,
        val isConfident: Boolean,
        val reason: String = "unknown",
        val stateDebug: Map<TransferState, StateDebug> = emptyMap()
    )

    data class RoiRectPx(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    data class DebugSnapshot(
        val transferId: Int,
        val strategy: String,
        val laneBitmap: Bitmap,
        val comparedBitmap: Bitmap,
        val roiRectPx: RoiRectPx?,
        val okReference: Bitmap?,
        val obstaculoReference: Bitmap?,
        val stateDebug: Map<TransferState, StateDebug>,
        val finalState: TransferState,
        val finalScore: Float,
        val finalReason: String
    )

    private data class SpatialContext(
        val transferId: Int,
        val top: Int,
        val bottom: Int,
        val frameHeight: Int
    ) {
        val centerY: Float
            get() = (top + bottom) / 2f

        val roiHeight: Int
            get() = (bottom - top).coerceAtLeast(1)

        val centerYNorm: Float
            get() = if (frameHeight > 0) centerY / frameHeight.toFloat() else 0f

        val roiHeightNorm: Float
            get() = if (frameHeight > 0) roiHeight / frameHeight.toFloat() else 0f
    }

    private data class CandidateScore(
        val state: TransferState,
        val sampleCount: Int,
        val bestVisual: Float,
        val bestStructural: Float,
        val bestColor: Float,
        val bestSpatial: Float,
        val score: Float,
        val laneAdjusted: Float
    )

    private data class VisualSimilarity(
        val structural: Float,
        val color: Float,
        val combined: Float
    )

    private data class ExtractionResult(
        val laneBitmap: Bitmap,
        val comparisonBitmap: Bitmap,
        val strategy: String,
        val roiRectPx: RoiRectPx?
    )

    @Volatile
    private var latestDebugSnapshot: DebugSnapshot? = null

    fun getLatestDebugSnapshot(): DebugSnapshot? = latestDebugSnapshot

    fun classifyROI(bitmap: Bitmap, roi: LaneDetector.LaneRegion, transferId: Int): ClassificationResult {
        return try {
            val extraction = extractFromBitmap(bitmap, roi, transferId)
            if (extraction == null) {
                Log.w(TAG, "Failed to extract ROI for transfer $transferId")
                ClassificationResult(TransferState.UNKNOWN, 0f, false, "extract_failed")
            } else {
                val spatialContext = SpatialContext(
                    transferId = transferId,
                    top = roi.top,
                    bottom = roi.bottom,
                    frameHeight = bitmap.height
                )
                classifyFromExtraction(extraction, spatialContext)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error classifying ROI for transfer $transferId", e)
            ClassificationResult(TransferState.UNKNOWN, 0f, false, "exception")
        }
    }

    fun classifyROI(
        image: Image,
        imageWidth: Int,
        imageHeight: Int,
        roi: LaneDetector.LaneRegion,
        transferId: Int
    ): ClassificationResult {
        return try {
            val extraction = extractFromImage(image, imageWidth, imageHeight, roi, transferId)
            if (extraction == null) {
                Log.w(TAG, "Failed to extract ROI for transfer $transferId")
                ClassificationResult(TransferState.UNKNOWN, 0f, false, "extract_failed")
            } else {
                val spatialContext = SpatialContext(
                    transferId = transferId,
                    top = roi.top,
                    bottom = roi.bottom,
                    frameHeight = imageHeight
                )
                classifyFromExtraction(extraction, spatialContext)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error classifying ROI for transfer $transferId", e)
            ClassificationResult(TransferState.UNKNOWN, 0f, false, "exception")
        }
    }

    private fun classifyFromExtraction(
        extraction: ExtractionResult,
        spatialContext: SpatialContext
    ): ClassificationResult {
        val transferId = spatialContext.transferId
        val currentROI = extraction.comparisonBitmap
        val candidates = mutableListOf<CandidateScore>()
        val laneCoherence = computeTransferLaneCoherence(spatialContext)
        val enabledStates = MonitoringMode.enabledTrainingStates
        var okReference: Bitmap? = null
        var obstReference: Bitmap? = null

        Log.d(
            TAG,
            "Transfer $transferId context strategy=${extraction.strategy} lane=${extraction.laneBitmap.width}x${extraction.laneBitmap.height} compared=${currentROI.width}x${currentROI.height} manualRoiPx=${extraction.roiRectPx} laneCoherence=${"%.4f".format(laneCoherence)} visualMode=color_aware(structural+lchroma) backgroundAttenuation=enabled"
        )

        for (state in enabledStates) {
            val samples = templateStorage.loadTemplateSamples(transferId, state)
            if (samples.isEmpty()) continue
            // Store canonized versions for debug to show what is actually being compared
            if (state == TransferState.OK && okReference == null) {
                okReference = samples.first().bitmap?.let { canonizeForComparison(it) }
            }
            if (state == TransferState.OBSTACULO && obstReference == null) {
                obstReference = samples.first().bitmap?.let { canonizeForComparison(it) }
            }

            var withSpatialCount = 0
            val perSampleScores = samples.map { sample ->
                val visualSimilarity = calculateVisualSimilarityWithTemplateFallback(currentROI, sample.bitmap)
                val spatialSimilarity = sample.spatialMetadata?.let {
                    withSpatialCount += 1
                    calculateSpatialSimilarity(spatialContext, it)
                }
                val combined = if (spatialSimilarity != null) {
                    (visualSimilarity.combined * VISUAL_WEIGHT) + (spatialSimilarity * SPATIAL_WEIGHT)
                } else {
                    visualSimilarity.combined * LEGACY_NO_SPATIAL_PENALTY
                }
                Triple(visualSimilarity, spatialSimilarity, combined)
            }

            val bestVisual = perSampleScores.maxOfOrNull { it.first.combined } ?: 0f
            val bestStructural = perSampleScores.maxOfOrNull { it.first.structural } ?: 0f
            val bestColor = perSampleScores.maxOfOrNull { it.first.color } ?: 0f
            val bestSpatial = perSampleScores.mapNotNull { it.second }.maxOrNull() ?: 0f
            val top2Combined = perSampleScores.map { it.third }.sortedDescending().take(2)
            val avgTopCombined = top2Combined.average().toFloat()
            val bestCombined = top2Combined.firstOrNull() ?: 0f
            val score = (bestCombined * 0.7f) + (avgTopCombined * 0.3f)
            val laneAdjusted = applyLaneCoherence(score, laneCoherence, state)

            candidates += CandidateScore(
                state = state,
                sampleCount = samples.size,
                bestVisual = bestVisual,
                bestStructural = bestStructural,
                bestColor = bestColor,
                bestSpatial = bestSpatial,
                score = score,
                laneAdjusted = laneAdjusted
            )

            Log.d(
                TAG,
                "Transfer $transferId state=$state samples=${samples.size} withSpatial=$withSpatialCount bestVisual=${"%.4f".format(bestVisual)} bestStructural=${"%.4f".format(bestStructural)} bestColor=${"%.4f".format(bestColor)} bestSpatial=${"%.4f".format(bestSpatial)} score=${"%.4f".format(score)} laneAdj=${"%.4f".format(laneAdjusted)}"
            )
        }

        if (candidates.isEmpty()) {
            val result = ClassificationResult(TransferState.UNKNOWN, 0f, false, "no_templates")
            latestDebugSnapshot = buildDebugSnapshot(transferId, extraction, okReference, obstReference, result, emptyMap())
            return result
        }

        val stateDebug = candidates.associate {
            it.state to StateDebug(
                sampleCount = it.sampleCount,
                bestVisual = it.bestVisual,
                bestStructural = it.bestStructural,
                bestColor = it.bestColor,
                bestSpatial = it.bestSpatial,
                laneAdjusted = it.laneAdjusted
            )
        }

        val ranked = candidates.sortedByDescending { it.laneAdjusted }
        val best = ranked[0]
        val second = ranked.getOrNull(1)
        val margin = if (second != null) best.laneAdjusted - second.laneAdjusted else best.laneAdjusted
        val okCandidate = candidates.firstOrNull { it.state == TransferState.OK }
        val bestAlert = candidates.filter { it.state != TransferState.OK }.maxByOrNull { it.laneAdjusted }

        val result = when {
            bestAlert != null && bestAlert.laneAdjusted >= ALERT_MATCH_THRESHOLD &&
                (bestAlert.laneAdjusted - (okCandidate?.laneAdjusted ?: 0f)) >= ALERT_OVER_OK_MARGIN &&
                margin >= AMBIGUITY_MARGIN -> {
                ClassificationResult(bestAlert.state, bestAlert.laneAdjusted, true, "strong_alert", stateDebug)
            }
            okCandidate != null && laneCoherence >= LANE_COHERENCE_MIN_FOR_OK &&
                (bestAlert?.laneAdjusted ?: 0f) < ALERT_MATCH_THRESHOLD -> {
                val okByDiscard = max(okCandidate.laneAdjusted, (okCandidate.score * 0.75f) + (laneCoherence * 0.25f))
                if (okByDiscard >= OK_BASELINE_MIN) {
                    ClassificationResult(TransferState.OK, okByDiscard, true, "baseline_discard", stateDebug)
                } else {
                    ClassificationResult(TransferState.UNKNOWN, best.laneAdjusted, false, "unknown_ambiguity", stateDebug)
                }
            }
            best.laneAdjusted >= SIMILARITY_THRESHOLD && margin >= AMBIGUITY_MARGIN -> {
                ClassificationResult(best.state, best.laneAdjusted, true, "best_ranked", stateDebug)
            }
            else -> {
                ClassificationResult(TransferState.UNKNOWN, best.laneAdjusted, false, "unknown_ambiguity", stateDebug)
            }
        }

        Log.d(
            TAG,
            "Transfer $transferId decision=${result.state} reason=${result.reason} score=${"%.4f".format(result.similarity)} margin=${"%.4f".format(margin)}"
        )
        latestDebugSnapshot = buildDebugSnapshot(transferId, extraction, okReference, obstReference, result, stateDebug)
        return result
    }

    private fun buildDebugSnapshot(
        transferId: Int,
        extraction: ExtractionResult,
        okReference: Bitmap?,
        obstReference: Bitmap?,
        result: ClassificationResult,
        stateDebug: Map<TransferState, StateDebug>
    ): DebugSnapshot {
        return DebugSnapshot(
            transferId = transferId,
            strategy = extraction.strategy,
            laneBitmap = extraction.laneBitmap,
            comparedBitmap = extraction.comparisonBitmap,
            roiRectPx = extraction.roiRectPx,
            okReference = okReference,
            obstaculoReference = obstReference,
            stateDebug = stateDebug,
            finalState = result.state,
            finalScore = result.similarity,
            finalReason = result.reason
        )
    }

    /**
     * Calculates visual similarity using unified geometric canonization.
     * Both current frame and template pass through the same pipeline (focus + resize)
     * before comparison, ensuring geometric consistency.
     */
    private fun calculateVisualSimilarityWithTemplateFallback(
        currentRoi: Bitmap,
        templateBitmap: Bitmap
    ): VisualSimilarity {
        // Unified pipeline: both images canonized with same geometric transformation
        val currentCanonized = canonizeForComparison(currentRoi)
        val templateCanonized = canonizeForComparison(templateBitmap)
        return calculateVisualSimilarity(currentCanonized, templateCanonized)
    }

    private fun computeTransferLaneCoherence(context: SpatialContext): Float {
        val expectedCenterNorm = when (context.transferId) {
            100 -> 1f / 6f
            200 -> 3f / 6f
            300 -> 5f / 6f
            else -> context.centerYNorm
        }
        val diff = abs(context.centerYNorm - expectedCenterNorm)
        return (1f - (diff / MAX_CENTER_NORM_DELTA)).coerceIn(0f, 1f)
    }

    private fun applyLaneCoherence(score: Float, laneCoherence: Float, state: TransferState): Float {
        if (state == TransferState.UNKNOWN) return score
        val adjustment = 0.92f + (laneCoherence * 0.16f)
        return (score * adjustment).coerceIn(0f, 1f)
    }

    private fun calculateSpatialSimilarity(
        context: SpatialContext,
        sampleMeta: TemplateStorage.SpatialMetadata
    ): Float {
        val centerDelta = abs(context.centerYNorm - sampleMeta.centerYNorm)
        val heightDelta = abs(context.roiHeightNorm - sampleMeta.roiHeightNorm)
        val centerScore = (1f - (centerDelta / MAX_CENTER_NORM_DELTA)).coerceIn(0f, 1f)
        val heightScore = (1f - (heightDelta / MAX_HEIGHT_NORM_DELTA)).coerceIn(0f, 1f)
        return (centerScore * 0.75f + heightScore * 0.25f).coerceIn(0f, 1f)
    }

    private fun extractFromBitmap(
        frame: Bitmap,
        lane: LaneDetector.LaneRegion,
        transferId: Int
    ): ExtractionResult? {
        val imageWidth = frame.width
        val imageHeight = frame.height
        val left = max(0, min(lane.left, imageWidth - 1))
        val top = max(0, min(lane.top, imageHeight - 1))
        val right = max(left + 1, min(lane.right, imageWidth))
        val bottom = max(top + 1, min(lane.bottom, imageHeight))
        val laneWidth = right - left
        val laneHeight = bottom - top
        if (laneWidth <= 0 || laneHeight <= 0) return null

        val laneBitmap = Bitmap.createBitmap(frame, left, top, laneWidth, laneHeight)
        return buildExtraction(transferId, laneBitmap, "current_bitmap")
    }

    private fun extractFromImage(
        image: Image,
        imageWidth: Int,
        imageHeight: Int,
        lane: LaneDetector.LaneRegion,
        transferId: Int
    ): ExtractionResult? {
        if (image.format != ImageFormat.YUV_420_888) {
            Log.w(TAG, "Unsupported image format: ${image.format}")
            return null
        }

        val left = max(0, min(lane.left, imageWidth - 1))
        val top = max(0, min(lane.top, imageHeight - 1))
        val right = max(left + 1, min(lane.right, imageWidth))
        val bottom = max(top + 1, min(lane.bottom, imageHeight))
        val laneWidth = right - left
        val laneHeight = bottom - top
        if (laneWidth <= 0 || laneHeight <= 0) return null

        val laneBitmap = Bitmap.createBitmap(laneWidth, laneHeight, Bitmap.Config.ARGB_8888)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        for (y in top until bottom) {
            val rowStart = y * yRowStride
            for (x in left until right) {
                val yValue = yBuffer.get(rowStart + (x * yPixelStride)).toInt() and 0xFF
                val uvX = x / 2
                val uvY = y / 2
                val uValue = uBuffer.get(uvY * uRowStride + uvX * uPixelStride).toInt() and 0xFF
                val vValue = vBuffer.get(uvY * vRowStride + uvX * vPixelStride).toInt() and 0xFF

                val c = (yValue - 16).coerceAtLeast(0)
                val d = uValue - 128
                val e = vValue - 128
                val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
                laneBitmap.setPixel(x - left, y - top, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }

        return buildExtraction(transferId, laneBitmap, "current_yuv")
    }

    /**
     * Canonizes a bitmap for visual comparison by applying the same geometric transformation
     * used for both current frames and reference templates.
     * Pipeline: focusRoi (temporal center crop) -> resize to template dimensions
     */
    private fun canonizeForComparison(bitmap: Bitmap): Bitmap {
        val focused = if (MonitoringMode.focusedSubRoiEnabled) {
            ImageUtils.cropCenteredByRatio(
                bitmap,
                MonitoringMode.focusedSubRoiWidthRatio,
                MonitoringMode.focusedSubRoiHeightRatio
            ) ?: bitmap
        } else {
            bitmap
        }
        return Bitmap.createScaledBitmap(focused, TEMPLATE_WIDTH, TEMPLATE_HEIGHT, true)
    }

    /**
     * Calculates the pixel coordinates of the focused ROI for debug visualization.
     * Mirrors the logic in ImageUtils.cropCenteredByRatio.
     */
    private fun calculateFocusRoiRect(bitmap: Bitmap): RoiRectPx {
        val cropWidth = (bitmap.width * MonitoringMode.focusedSubRoiWidthRatio).toInt()
            .coerceIn(1, bitmap.width)
        val cropHeight = (bitmap.height * MonitoringMode.focusedSubRoiHeightRatio).toInt()
            .coerceIn(1, bitmap.height)
        val left = (bitmap.width - cropWidth) / 2
        val top = (bitmap.height - cropHeight) / 2
        return RoiRectPx(left, top, left + cropWidth, top + cropHeight)
    }

    private fun buildExtraction(transferId: Int, laneBitmap: Bitmap, source: String): ExtractionResult {
        // Temporal canonization pipeline: lane -> focusRoi -> resize
        // Both current frame and templates use this same pipeline for geometric consistency
        val canonized = canonizeForComparison(laneBitmap)
        
        // Calculate ROI rect for debug visualization (focused region before resize)
        val roiRectPx = if (MonitoringMode.focusedSubRoiEnabled) {
            calculateFocusRoiRect(laneBitmap)
        } else {
            RoiRectPx(0, 0, laneBitmap.width, laneBitmap.height)
        }
        
        Log.d(
            TAG,
            "ROI strategy=temporal_canonization lane=${laneBitmap.width}x${laneBitmap.height} roiPx=$roiRectPx canonized=${canonized.width}x${canonized.height}"
        )
        
        return ExtractionResult(
            laneBitmap = laneBitmap,
            comparisonBitmap = canonized,
            strategy = "temporal_canonization",
            roiRectPx = roiRectPx
        )
    }

    private fun cropByNormalized(
        laneBitmap: Bitmap,
        roi: TemplateStorage.ManualRoi
    ): Pair<Bitmap, RoiRectPx>? {
        if (!roi.isValid()) return null
        val left = (roi.leftNorm * laneBitmap.width).toInt().coerceIn(0, laneBitmap.width - 1)
        val top = (roi.topNorm * laneBitmap.height).toInt().coerceIn(0, laneBitmap.height - 1)
        val right = (roi.rightNorm * laneBitmap.width).toInt().coerceIn(left + 1, laneBitmap.width)
        val bottom = (roi.bottomNorm * laneBitmap.height).toInt().coerceIn(top + 1, laneBitmap.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return null
        val cropped = Bitmap.createBitmap(laneBitmap, left, top, width, height)
        return cropped to RoiRectPx(left, top, right, bottom)
    }

    private fun calculateVisualSimilarity(bitmap1: Bitmap, bitmap2: Bitmap): VisualSimilarity {
        if (bitmap1.width != bitmap2.width || bitmap1.height != bitmap2.height) {
            Log.w(TAG, "Bitmap dimensions mismatch: ${bitmap1.width}x${bitmap1.height} vs ${bitmap2.width}x${bitmap2.height}")
            return VisualSimilarity(0f, 0f, 0f)
        }

        var structuralDiffSum = 0f
        var colorDiffSum = 0f
        var totalWeight = 0f

        for (y in 0 until bitmap1.height step 4) {
            for (x in 0 until bitmap1.width step 4) {
                val p1 = bitmap1.getPixel(x, y)
                val p2 = bitmap2.getPixel(x, y)

                val r1 = ((p1 shr 16) and 0xFF).toFloat()
                val g1 = ((p1 shr 8) and 0xFF).toFloat()
                val b1 = (p1 and 0xFF).toFloat()
                val r2 = ((p2 shr 16) and 0xFF).toFloat()
                val g2 = ((p2 shr 8) and 0xFF).toFloat()
                val b2 = (p2 and 0xFF).toFloat()

                val lum1 = (0.299f * r1) + (0.587f * g1) + (0.114f * b1)
                val lum2 = (0.299f * r2) + (0.587f * g2) + (0.114f * b2)
                val structuralDiff = abs(lum1 - lum2) / 255f

                val sum1 = (r1 + g1 + b1).coerceAtLeast(1f)
                val sum2 = (r2 + g2 + b2).coerceAtLeast(1f)
                val chromaDiff = (
                    abs((r1 / sum1) - (r2 / sum2)) +
                        abs((g1 / sum1) - (g2 / sum2)) +
                        abs((b1 / sum1) - (b2 / sum2))
                    ) / 3f

                val sat1 = calculateSaturation(r1, g1, b1)
                val sat2 = calculateSaturation(r2, g2, b2)
                val saturationDiff = abs(sat1 - sat2)
                val colorDiff = ((chromaDiff * 0.8f) + (saturationDiff * 0.2f)).coerceIn(0f, 1f)

                val backgroundWeight = min(
                    computeBackgroundWeight(lum1, sat1),
                    computeBackgroundWeight(lum2, sat2)
                )

                structuralDiffSum += structuralDiff * backgroundWeight
                colorDiffSum += colorDiff * backgroundWeight
                totalWeight += backgroundWeight
            }
        }

        if (totalWeight <= 0f) return VisualSimilarity(0f, 0f, 0f)

        val structuralSimilarity = (1f - (structuralDiffSum / totalWeight)).coerceIn(0f, 1f)
        val colorSimilarity = (1f - (colorDiffSum / totalWeight)).coerceIn(0f, 1f)
        val combined = ((structuralSimilarity * STRUCTURAL_WEIGHT) + (colorSimilarity * COLOR_WEIGHT)).coerceIn(0f, 1f)
        return VisualSimilarity(structuralSimilarity, colorSimilarity, combined)
    }

    private fun calculateSaturation(r: Float, g: Float, b: Float): Float {
        val maxChannel = max(r, max(g, b))
        val minChannel = min(r, min(g, b))
        return if (maxChannel <= 0f) 0f else (maxChannel - minChannel) / maxChannel
    }

    private fun computeBackgroundWeight(luminance: Float, saturation: Float): Float {
        val lumNorm = (luminance / 255f).coerceIn(0f, 1f)
        val isNeutral = saturation < 0.12f
        val isVeryBright = lumNorm > 0.78f
        val isVeryDark = lumNorm < 0.22f
        return if (isNeutral && (isVeryBright || isVeryDark)) BACKGROUND_LOW_WEIGHT else 1f
    }

    private fun focusRoi(input: Bitmap, source: String, logDetails: Boolean): Bitmap {
        if (!MonitoringMode.focusedSubRoiEnabled) return input
        val focused = ImageUtils.cropCenteredByRatio(
            input,
            MonitoringMode.focusedSubRoiWidthRatio,
            MonitoringMode.focusedSubRoiHeightRatio
        ) ?: return input

        if (logDetails) {
            Log.d(
                TAG,
                "ROI focus source=$source strategy=center_crop laneRoi=${input.width}x${input.height} subRoi=${focused.width}x${focused.height} ratios=${MonitoringMode.focusedSubRoiWidthRatio}x${MonitoringMode.focusedSubRoiHeightRatio}"
            )
        }
        return focused
    }
}
