package com.mindaplus.android

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
    }
    
    data class ClassificationResult(
        val state: TransferState,
        val similarity: Float,
        val isConfident: Boolean
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
        val avgTopVisual: Float,
        val bestSpatial: Float,
        val score: Float,
        val scoreLegacyAdjusted: Float
    )

    private data class VisualSimilarity(
        val structural: Float,
        val color: Float,
        val combined: Float
    )

    fun classifyROI(bitmap: Bitmap, roi: LaneDetector.LaneRegion, transferId: Int): ClassificationResult {
        try {
            // Extract and normalize ROI from current frame
            val currentROI = extractROI(bitmap, roi)
            if (currentROI == null) {
                Log.w(TAG, "Failed to extract ROI for transfer $transferId")
                return ClassificationResult(TransferState.UNKNOWN, 0f, false)
            }
            val spatialContext = SpatialContext(
                transferId = transferId,
                top = roi.top,
                bottom = roi.bottom,
                frameHeight = bitmap.height
            )
            return classifyFromRoi(currentROI, spatialContext)

        } catch (e: Exception) {
            Log.e(TAG, "Error classifying ROI for transfer $transferId", e)
            return ClassificationResult(TransferState.UNKNOWN, 0f, false)
        }
    }
    
    fun classifyROI(image: Image, imageWidth: Int, imageHeight: Int, roi: LaneDetector.LaneRegion, transferId: Int): ClassificationResult {
        try {
            // Extract and normalize ROI from current frame
            val currentROI = extractROI(image, imageWidth, imageHeight, roi)
            if (currentROI == null) {
                Log.w(TAG, "Failed to extract ROI for transfer $transferId")
                return ClassificationResult(TransferState.UNKNOWN, 0f, false)
            }
            val spatialContext = SpatialContext(
                transferId = transferId,
                top = roi.top,
                bottom = roi.bottom,
                frameHeight = imageHeight
            )
            return classifyFromRoi(currentROI, spatialContext)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error classifying ROI for transfer $transferId", e)
            return ClassificationResult(TransferState.UNKNOWN, 0f, false)
        }
    }

    private fun classifyFromRoi(currentROI: Bitmap, spatialContext: SpatialContext): ClassificationResult {
        val transferId = spatialContext.transferId
        val candidates = mutableListOf<CandidateScore>()
        val classSampleCounts = mutableMapOf<TransferState, Int>()
        val laneCoherence = computeTransferLaneCoherence(spatialContext)

        Log.d(
            TAG,
            "Transfer $transferId context top=${spatialContext.top} bottom=${spatialContext.bottom} centerY=${"%.1f".format(spatialContext.centerY)} centerYNorm=${"%.4f".format(spatialContext.centerYNorm)} roiHeightNorm=${"%.4f".format(spatialContext.roiHeightNorm)} laneCoherence=${"%.4f".format(laneCoherence)} visualMode=color_aware(structural+lchroma)"
        )

        val enabledStates = MonitoringMode.enabledTrainingStates
        for (state in enabledStates) {
            val samples = templateStorage.loadTemplateSamples(transferId, state)
            classSampleCounts[state] = samples.size

            if (samples.isEmpty()) {
                continue
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
            val avgTopVisual = perSampleScores.map { it.first.combined }.sortedDescending().take(2).average().toFloat()
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
                avgTopVisual = avgTopVisual,
                bestSpatial = bestSpatial,
                score = score,
                scoreLegacyAdjusted = laneAdjusted
            )

            Log.d(
                TAG,
                "Transfer $transferId state=$state samples=${samples.size} withSpatial=$withSpatialCount bestVisual=${"%.4f".format(bestVisual)} bestStructural=${"%.4f".format(bestStructural)} bestColor=${"%.4f".format(bestColor)} avgTopVisual=${"%.4f".format(avgTopVisual)} bestSpatial=${"%.4f".format(bestSpatial)} score=${"%.4f".format(score)} laneAdj=${"%.4f".format(laneAdjusted)}"
            )
        }

        if (candidates.isEmpty()) {
            Log.w(TAG, "No templates found for transfer $transferId (all classes empty)")
            return ClassificationResult(TransferState.UNKNOWN, 0f, false)
        }

        val ranked = candidates.sortedByDescending { it.scoreLegacyAdjusted }
        val best = ranked[0]
        val second = ranked.getOrNull(1)
        val margin = if (second != null) best.scoreLegacyAdjusted - second.scoreLegacyAdjusted else best.scoreLegacyAdjusted

        val okCandidate = candidates.firstOrNull { it.state == TransferState.OK }
        val alertCandidates = candidates.filter { it.state != TransferState.OK }
        val bestAlert = alertCandidates.maxByOrNull { it.scoreLegacyAdjusted }

        if (bestAlert != null) {
            val okScore = okCandidate?.scoreLegacyAdjusted ?: 0f
            val alertOverOk = bestAlert.scoreLegacyAdjusted - okScore
            val alertStrong = bestAlert.scoreLegacyAdjusted >= ALERT_MATCH_THRESHOLD
            if (alertStrong && alertOverOk >= ALERT_OVER_OK_MARGIN && margin >= AMBIGUITY_MARGIN) {
                Log.d(
                    TAG,
                    "Transfer $transferId decision=${bestAlert.state} reason=strong_alert score=${"%.4f".format(bestAlert.scoreLegacyAdjusted)} overOk=${"%.4f".format(alertOverOk)} margin=${"%.4f".format(margin)}"
                )
                return ClassificationResult(bestAlert.state, bestAlert.scoreLegacyAdjusted, true)
            }
        }

        if (okCandidate != null && laneCoherence >= LANE_COHERENCE_MIN_FOR_OK) {
            val bestAlertScore = bestAlert?.scoreLegacyAdjusted ?: 0f
            val noStrongAlert = bestAlertScore < ALERT_MATCH_THRESHOLD
            val okByDiscardScore = max(okCandidate.scoreLegacyAdjusted, (okCandidate.score * 0.75f) + (laneCoherence * 0.25f))
            if (noStrongAlert && okByDiscardScore >= OK_BASELINE_MIN) {
                Log.d(
                    TAG,
                    "Transfer $transferId decision=OK reason=baseline_discard okScore=${"%.4f".format(okCandidate.scoreLegacyAdjusted)} okDiscard=${"%.4f".format(okByDiscardScore)} bestAlert=${"%.4f".format(bestAlertScore)} laneCoherence=${"%.4f".format(laneCoherence)}"
                )
                return ClassificationResult(TransferState.OK, okByDiscardScore, true)
            }
        }

        val isConfident = best.scoreLegacyAdjusted >= SIMILARITY_THRESHOLD && margin >= AMBIGUITY_MARGIN
        if (!isConfident) {
            Log.w(
                TAG,
                "Transfer $transferId ambiguous/low confidence. best=${best.state}:${"%.4f".format(best.scoreLegacyAdjusted)} second=${second?.state}:${"%.4f".format(second?.scoreLegacyAdjusted ?: 0f)} margin=${"%.4f".format(margin)} laneCoherence=${"%.4f".format(laneCoherence)} samples=$classSampleCounts reason=unknown_ambiguity"
            )
            return ClassificationResult(TransferState.UNKNOWN, best.scoreLegacyAdjusted, false)
        }

        Log.d(
            TAG,
            "Transfer $transferId classified=${best.state} score=${"%.4f".format(best.scoreLegacyAdjusted)} margin=${"%.4f".format(margin)} samples=$classSampleCounts reason=best_ranked"
        )
        return ClassificationResult(best.state, best.scoreLegacyAdjusted, true)
    }

    private fun calculateVisualSimilarityWithTemplateFallback(currentRoi: Bitmap, templateBitmap: Bitmap): VisualSimilarity {
        val asIs = calculateVisualSimilarity(currentRoi, templateBitmap)
        val focusedTemplate = if (MonitoringMode.focusedSubRoiEnabled) {
            focusRoi(templateBitmap, "template", logDetails = false)
        } else {
            null
        }
        val focusedScore = focusedTemplate?.let { calculateVisualSimilarity(currentRoi, it) } ?: asIs
        return if (focusedScore.combined >= asIs.combined) focusedScore else asIs
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
    
    private fun extractROI(image: Image, imageWidth: Int, imageHeight: Int, roi: LaneDetector.LaneRegion): Bitmap? {
        try {
            if (image.format != ImageFormat.YUV_420_888) {
                Log.w(TAG, "Unsupported image format: ${image.format}")
                return null
            }
            
            val yPlane = image.planes[0]
            val yBuffer = yPlane.buffer
            val yRowStride = yPlane.rowStride
            
            // Ensure ROI is within bounds
            val left = max(0, min(roi.left, imageWidth - 1))
            val top = max(0, min(roi.top, imageHeight - 1))
            val right = max(left + 1, min(roi.right, imageWidth))
            val bottom = max(top + 1, min(roi.bottom, imageHeight))
            
            val roiWidth = right - left
            val roiHeight = bottom - top
            
            if (roiWidth <= 0 || roiHeight <= 0) {
                Log.w(TAG, "Invalid ROI dimensions: ${roiWidth}x${roiHeight}")
                return null
            }
            
            // Create color bitmap from YUV planes
            val bitmap = Bitmap.createBitmap(roiWidth, roiHeight, Bitmap.Config.ARGB_8888)
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer
            val yPixelStride = yPlane.pixelStride
            val uPixelStride = uPlane.pixelStride
            val vPixelStride = vPlane.pixelStride
            val uRowStride = uPlane.rowStride
            val vRowStride = vPlane.rowStride
            
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
                    val rgb = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    bitmap.setPixel(x - left, y - top, rgb)
                }
            }
            
            val focused = focusRoi(bitmap, "current_yuv", logDetails = true)
            return Bitmap.createScaledBitmap(focused, TEMPLATE_WIDTH, TEMPLATE_HEIGHT, true)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting ROI", e)
            return null
        }
    }

    private fun extractROI(bitmap: Bitmap, roi: LaneDetector.LaneRegion): Bitmap? {
        try {
            val imageWidth = bitmap.width
            val imageHeight = bitmap.height

            // Ensure ROI is within bounds
            val left = max(0, min(roi.left, imageWidth - 1))
            val top = max(0, min(roi.top, imageHeight - 1))
            val right = max(left + 1, min(roi.right, imageWidth))
            val bottom = max(top + 1, min(roi.bottom, imageHeight))

            val roiWidth = right - left
            val roiHeight = bottom - top

            if (roiWidth <= 0 || roiHeight <= 0) {
                Log.w(TAG, "Invalid ROI dimensions: ${roiWidth}x${roiHeight}")
                return null
            }

            val roiBitmap = Bitmap.createBitmap(bitmap, left, top, roiWidth, roiHeight)
            val focused = focusRoi(roiBitmap, "current_bitmap", logDetails = true)
            return Bitmap.createScaledBitmap(focused, TEMPLATE_WIDTH, TEMPLATE_HEIGHT, true)

        } catch (e: Exception) {
            Log.e(TAG, "Error extracting ROI from bitmap", e)
            return null
        }
    }
    
    private fun calculateVisualSimilarity(bitmap1: Bitmap, bitmap2: Bitmap): VisualSimilarity {
        try {
            if (bitmap1.width != bitmap2.width || bitmap1.height != bitmap2.height) {
                Log.w(TAG, "Bitmap dimensions mismatch: ${bitmap1.width}x${bitmap1.height} vs ${bitmap2.width}x${bitmap2.height}")
                return VisualSimilarity(0f, 0f, 0f)
            }
            
            val width = bitmap1.width
            val height = bitmap1.height
            
            var structuralDiffSum = 0f
            var colorDiffSum = 0f
            var pixelCount = 0
            
            // Sample every 4th pixel for performance
            for (y in 0 until height step 4) {
                for (x in 0 until width step 4) {
                    val pixel1 = bitmap1.getPixel(x, y)
                    val pixel2 = bitmap2.getPixel(x, y)

                    val r1 = ((pixel1 shr 16) and 0xFF).toFloat()
                    val g1 = ((pixel1 shr 8) and 0xFF).toFloat()
                    val b1 = (pixel1 and 0xFF).toFloat()
                    val r2 = ((pixel2 shr 16) and 0xFF).toFloat()
                    val g2 = ((pixel2 shr 8) and 0xFF).toFloat()
                    val b2 = (pixel2 and 0xFF).toFloat()

                    val lum1 = (0.299f * r1) + (0.587f * g1) + (0.114f * b1)
                    val lum2 = (0.299f * r2) + (0.587f * g2) + (0.114f * b2)
                    structuralDiffSum += abs(lum1 - lum2) / 255f

                    val sum1 = (r1 + g1 + b1).coerceAtLeast(1f)
                    val sum2 = (r2 + g2 + b2).coerceAtLeast(1f)
                    val nr1 = r1 / sum1
                    val ng1 = g1 / sum1
                    val nb1 = b1 / sum1
                    val nr2 = r2 / sum2
                    val ng2 = g2 / sum2
                    val nb2 = b2 / sum2
                    val chromaDiff = (abs(nr1 - nr2) + abs(ng1 - ng2) + abs(nb1 - nb2)) / 3f

                    val sat1 = ((max(r1, max(g1, b1)) - min(r1, min(g1, b1))) / max(r1, max(g1, b1)).coerceAtLeast(1f))
                    val sat2 = ((max(r2, max(g2, b2)) - min(r2, min(g2, b2))) / max(r2, max(g2, b2)).coerceAtLeast(1f))
                    val saturationDiff = abs(sat1 - sat2)

                    colorDiffSum += ((chromaDiff * 0.8f) + (saturationDiff * 0.2f)).coerceIn(0f, 1f)
                    pixelCount++
                }
            }
            
            if (pixelCount == 0) {
                return VisualSimilarity(0f, 0f, 0f)
            }

            val structuralSimilarity = (1f - (structuralDiffSum / pixelCount.toFloat())).coerceIn(0f, 1f)
            val colorSimilarity = (1f - (colorDiffSum / pixelCount.toFloat())).coerceIn(0f, 1f)
            val combined = ((structuralSimilarity * STRUCTURAL_WEIGHT) + (colorSimilarity * COLOR_WEIGHT)).coerceIn(0f, 1f)
            return VisualSimilarity(
                structural = structuralSimilarity,
                color = colorSimilarity,
                combined = combined
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating color-aware similarity", e)
            return VisualSimilarity(0f, 0f, 0f)
        }
    }

    private fun focusRoi(input: Bitmap, source: String, logDetails: Boolean): Bitmap {
        if (!MonitoringMode.focusedSubRoiEnabled) {
            return input
        }

        val focused = ImageUtils.cropCenteredByRatio(
            input,
            MonitoringMode.focusedSubRoiWidthRatio,
            MonitoringMode.focusedSubRoiHeightRatio
        )

        if (focused == null) {
            if (logDetails) {
                Log.w(
                    TAG,
                    "ROI focus fallback source=$source strategy=center_crop laneRoi=${input.width}x${input.height} (invalid subROI)"
                )
            }
            return input
        }

        if (logDetails) {
            Log.d(
                TAG,
                "ROI focus source=$source strategy=center_crop laneRoi=${input.width}x${input.height} subRoi=${focused.width}x${focused.height} ratios=${MonitoringMode.focusedSubRoiWidthRatio}x${MonitoringMode.focusedSubRoiHeightRatio}"
            )
        }
        return focused
    }
}
