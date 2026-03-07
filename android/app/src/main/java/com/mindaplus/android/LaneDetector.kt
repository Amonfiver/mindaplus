package com.mindaplus.android

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min

class LaneDetector {
    companion object {
        private const val TAG = "LaneDetector"
        private const val DOWNSAMPLE_FACTOR = 4
        private const val MIN_LANE_WIDTH_PX = 50
        private const val MIN_CONTRAST_THRESHOLD = 30
        private const val LANE_DETECTION_ROWS = 20 // Number of rows to analyze
        private const val ANALYSIS_X_START_RATIO = 0.20f
        private const val ANALYSIS_X_END_RATIO = 0.80f
        private const val DEFAULT_X_START_RATIO = 0.22f
        private const val DEFAULT_X_END_RATIO = 0.78f
        private const val DEFAULT_Y_START_RATIO = 0.22f
        private const val DEFAULT_Y_END_RATIO = 0.78f
    }
    
    data class LaneRegion(val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class DetectionResult(val lanes: List<LaneRegion>?, val requiresCalibration: Boolean)
    
    private var cachedLanes: List<LaneRegion>? = null
    
    /**
     * Detecta carriles desde un Bitmap
     */
    fun detectLanes(bitmap: Bitmap): DetectionResult {
        return try {
            val imageWidth = bitmap.width
            val imageHeight = bitmap.height
            
            // Use cached lanes if available (calibrate once and freeze)
            if (cachedLanes != null) {
                return DetectionResult(cachedLanes, false)
            }

            val yBuffer = extractYChannelFromBitmap(bitmap)
            val autoLanes = detectHorizontalLanesFromY(yBuffer, imageWidth, imageHeight, "Bitmap")
            if (autoLanes != null) {
                cachedLanes = autoLanes
                Log.d(TAG, "Auto lane detection success from Bitmap: ${autoLanes.joinToString()}")
                return DetectionResult(autoLanes, false)
            }

            val fallbackLanes = buildFallbackLanes(imageWidth, imageHeight)
            cachedLanes = fallbackLanes
            Log.w(TAG, "Lane auto-detection failed from Bitmap. Using fallback lanes: ${fallbackLanes.joinToString()}")
            DetectionResult(fallbackLanes, false)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting lanes from Bitmap", e)
            return DetectionResult(null, true)
        }
    }
    
    /**
     * Detecta carriles desde una Image (método original)
     */
    fun detectLanes(image: Image, imageWidth: Int, imageHeight: Int): DetectionResult {
        try {
            // Use cached lanes if available (calibrate once and freeze)
            if (cachedLanes != null) {
                return DetectionResult(cachedLanes, false)
            }
            
            if (image.format != ImageFormat.YUV_420_888) {
                Log.w(TAG, "Unsupported image format: ${image.format}")
                return DetectionResult(null, true)
            }

            val yBuffer = extractYChannelFromImage(image, imageWidth, imageHeight)
            val autoLanes = detectHorizontalLanesFromY(yBuffer, imageWidth, imageHeight, "Image")
            if (autoLanes != null) {
                cachedLanes = autoLanes
                Log.d(TAG, "Auto lane detection success from Image: ${autoLanes.joinToString()}")
                return DetectionResult(autoLanes, false)
            }

            val fallbackLanes = buildFallbackLanes(imageWidth, imageHeight)
            cachedLanes = fallbackLanes
            Log.w(TAG, "Lane auto-detection failed from Image. Using fallback lanes: ${fallbackLanes.joinToString()}")
            return DetectionResult(fallbackLanes, false)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting lanes", e)
            return DetectionResult(null, true)
        }
    }

    private fun detectHorizontalLanesFromY(
        yBuffer: ByteArray,
        imageWidth: Int,
        imageHeight: Int,
        source: String
    ): List<LaneRegion>? {
        if (imageWidth <= 0 || imageHeight <= 0) {
            Log.w(TAG, "$source lane detection aborted: invalid frame size ${imageWidth}x${imageHeight}")
            return null
        }

        val xStart = (imageWidth * ANALYSIS_X_START_RATIO).toInt().coerceIn(0, imageWidth - 1)
        val xEnd = (imageWidth * ANALYSIS_X_END_RATIO).toInt().coerceIn(xStart + 1, imageWidth)
        if (xEnd - xStart < MIN_LANE_WIDTH_PX) {
            Log.w(TAG, "$source lane detection aborted: analysis strip too narrow (${xEnd - xStart}px)")
            return null
        }

        val gradientProfile = IntArray(imageHeight)
        for (y in 1 until imageHeight - 1) {
            var sum = 0
            var x = xStart
            while (x < xEnd) {
                val above = yBuffer[(y - 1) * imageWidth + x].toInt() and 0xFF
                val below = yBuffer[(y + 1) * imageWidth + x].toInt() and 0xFF
                sum += abs(below - above)
                x += 2
            }
            gradientProfile[y] = sum
        }

        val smoothedProfile = smoothProfile(gradientProfile, max(3, imageHeight / 40))
        val threshold = computeAdaptiveThreshold(smoothedProfile)
        val minDistance = max(24, imageHeight / 8)
        val centers = findTopPeaks(smoothedProfile, threshold, minDistance, 3)

        val boundariesFound = centers.size * 2
        Log.d(
            TAG,
            "$source lane detection diagnostics: boundariesFound=$boundariesFound, centersFound=${centers.size}, threshold=${"%.2f".format(threshold)}, analysisX=[$xStart,$xEnd)"
        )

        if (centers.size != 3) {
            Log.w(TAG, "$source lane detection failed: expected 3 centers, got ${centers.size}")
            return null
        }

        val sortedCenters = centers.sorted()
        val laneHeight = (imageHeight * 0.14f).toInt().coerceIn(36, imageHeight / 3)
        val halfLane = laneHeight / 2
        val left = (imageWidth * DEFAULT_X_START_RATIO).toInt().coerceIn(0, imageWidth - 1)
        val right = (imageWidth * DEFAULT_X_END_RATIO).toInt().coerceIn(left + 1, imageWidth)

        val lanes = sortedCenters.map { centerY ->
            val top = (centerY - halfLane).coerceIn(0, imageHeight - 2)
            val bottom = (centerY + halfLane).coerceIn(top + 1, imageHeight)
            LaneRegion(left, top, right, bottom)
        }

        if (!isLaneSetValid(lanes)) {
            Log.w(TAG, "$source lane detection failed validation. Regions=$lanes")
            return null
        }

        Log.d(TAG, "$source lane detection final regions: ${lanes.joinToString()}")
        return lanes
    }

    private fun extractYChannelFromImage(image: Image, imageWidth: Int, imageHeight: Int): ByteArray {
        val yPlane = image.planes[0]
        val yRowStride = yPlane.rowStride
        val source = yPlane.buffer
        val output = ByteArray(imageWidth * imageHeight)

        for (y in 0 until imageHeight) {
            val rowStart = y * yRowStride
            for (x in 0 until imageWidth) {
                output[y * imageWidth + x] = source.get(rowStart + x)
            }
        }

        return output
    }

    private fun smoothProfile(values: IntArray, radius: Int): IntArray {
        val safeRadius = radius.coerceAtLeast(1)
        val out = IntArray(values.size)

        for (i in values.indices) {
            val start = max(0, i - safeRadius)
            val end = min(values.size - 1, i + safeRadius)
            var sum = 0L
            var count = 0
            for (j in start..end) {
                sum += values[j].toLong()
                count++
            }
            out[i] = if (count > 0) (sum / count).toInt() else 0
        }

        return out
    }

    private fun computeAdaptiveThreshold(values: IntArray): Float {
        if (values.isEmpty()) return 0f

        var sum = 0.0
        for (v in values) sum += v.toDouble()
        val mean = sum / values.size.toDouble()

        var varianceSum = 0.0
        for (v in values) {
            val diff = v.toDouble() - mean
            varianceSum += diff * diff
        }
        val std = sqrt(varianceSum / values.size.toDouble())
        return (mean + std * 0.8).toFloat()
    }

    private fun findTopPeaks(
        profile: IntArray,
        threshold: Float,
        minDistance: Int,
        maxPeaks: Int
    ): List<Int> {
        if (profile.size < 3) return emptyList()

        val candidates = mutableListOf<Pair<Int, Int>>()
        for (i in 1 until profile.size - 1) {
            val current = profile[i]
            if (current < threshold) continue
            if (current >= profile[i - 1] && current >= profile[i + 1]) {
                candidates.add(i to current)
            }
        }

        val selected = mutableListOf<Pair<Int, Int>>()
        for ((idx, value) in candidates.sortedByDescending { it.second }) {
            val tooClose = selected.any { abs(it.first - idx) < minDistance }
            if (!tooClose) {
                selected.add(idx to value)
                if (selected.size == maxPeaks) break
            }
        }

        return selected.map { it.first }.sorted()
    }

    private fun isLaneSetValid(lanes: List<LaneRegion>): Boolean {
        if (lanes.size != 3) return false
        if (lanes.any { it.right <= it.left || it.bottom <= it.top }) return false

        val sorted = lanes.sortedBy { it.top }
        for (i in 0 until sorted.size - 1) {
            if (sorted[i].bottom >= sorted[i + 1].bottom) {
                return false
            }
        }
        return true
    }

    private fun buildFallbackLanes(imageWidth: Int, imageHeight: Int): List<LaneRegion> {
        val left = (imageWidth * DEFAULT_X_START_RATIO).toInt().coerceIn(0, imageWidth - 1)
        val right = (imageWidth * DEFAULT_X_END_RATIO).toInt().coerceIn(left + 1, imageWidth)
        val yStart = (imageHeight * DEFAULT_Y_START_RATIO).toInt().coerceIn(0, imageHeight - 1)
        val yEnd = (imageHeight * DEFAULT_Y_END_RATIO).toInt().coerceIn(yStart + 1, imageHeight)
        val laneSpan = max(1, (yEnd - yStart) / 3)

        val lanes = mutableListOf<LaneRegion>()
        for (i in 0 until 3) {
            val top = (yStart + i * laneSpan).coerceIn(0, imageHeight - 2)
            val bottomBase = if (i == 2) yEnd else (yStart + (i + 1) * laneSpan)
            val bottom = bottomBase.coerceIn(top + 1, imageHeight)
            lanes.add(LaneRegion(left, top, right, bottom))
        }

        Log.w(TAG, "Fallback lane regions generated: ${lanes.joinToString()}")
        return lanes
    }
    
    /**
     * Extrae el canal Y de un Bitmap (luminancia)
     */
    private fun extractYChannelFromBitmap(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val yBuffer = ByteArray(width * height)
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                
                // Convertir RGB a Y (luminancia) usando fórmula estándar
                val yValue = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
                yBuffer[y * width + x] = yValue.toByte()
            }
        }
        
        return yBuffer
    }
    
    /**
     * Analiza el contraste de una fila desde un buffer Y
     */
    private fun analyzeRowContrastFromYBuffer(rowY: Int, yBuffer: ByteArray, imageWidth: Int, downsampledWidth: Int): IntArray {
        val contrastProfile = IntArray(downsampledWidth)
        
        try {
            for (x in 0 until imageWidth step DOWNSAMPLE_FACTOR) {
                val downsampledX = x / DOWNSAMPLE_FACTOR
                if (downsampledX >= downsampledWidth) break
                
                // Calculate local contrast using Sobel-like operator
                val left = max(0, x - DOWNSAMPLE_FACTOR)
                val right = min(imageWidth - 1, x + DOWNSAMPLE_FACTOR)
                
                if (left >= right) continue
                
                val leftY = yBuffer[rowY * imageWidth + left].toInt() and 0xFF
                val rightY = yBuffer[rowY * imageWidth + right].toInt() and 0xFF
                val centerY = yBuffer[rowY * imageWidth + x].toInt() and 0xFF
                
                val contrast = abs(centerY - leftY) + abs(centerY - rightY)
                contrastProfile[downsampledX] = contrast
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error analyzing row contrast from Y buffer at y=$rowY", e)
        }
        
        return contrastProfile
    }
    
    private fun analyzeRowContrast(rowY: Int, yBuffer: java.nio.ByteBuffer, yRowStride: Int, imageWidth: Int, downsampledWidth: Int): IntArray {
        val contrastProfile = IntArray(downsampledWidth)
        
        try {
            val rowStart = rowY * yRowStride
            
            for (x in 0 until imageWidth step DOWNSAMPLE_FACTOR) {
                val downsampledX = x / DOWNSAMPLE_FACTOR
                if (downsampledX >= downsampledWidth) break
                
                // Calculate local contrast using Sobel-like operator
                val left = max(0, x - DOWNSAMPLE_FACTOR)
                val right = min(imageWidth - 1, x + DOWNSAMPLE_FACTOR)
                
                if (left >= right) continue
                
                val leftY = yBuffer.get(rowStart + left).toInt() and 0xFF
                val rightY = yBuffer.get(rowStart + right).toInt() and 0xFF
                val centerY = yBuffer.get(rowStart + x).toInt() and 0xFF
                
                val contrast = abs(centerY - leftY) + abs(centerY - rightY)
                contrastProfile[downsampledX] = contrast
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error analyzing row contrast at y=$rowY", e)
        }
        
        return contrastProfile
    }
    
    private fun findLaneBoundaries(contrastProfiles: List<IntArray>, width: Int): List<Int> {
        // Average all contrast profiles
        val avgProfile = IntArray(width)
        for (x in 0 until width) {
            var sum = 0
            var count = 0
            for (profile in contrastProfiles) {
                if (x < profile.size) {
                    sum += profile[x]
                    count++
                }
            }
            avgProfile[x] = if (count > 0) sum / count else 0
        }
        
        // Find peaks (high contrast regions indicating lane boundaries)
        val boundaries = mutableListOf<Int>()
        val minPeakHeight = MIN_CONTRAST_THRESHOLD
        
        for (x in 1 until width - 1) {
            val current = avgProfile[x]
            val prev = avgProfile[x - 1]
            val next = avgProfile[x + 1]
            
            if (current > prev && current > next && current > minPeakHeight) {
                boundaries.add(x)
            }
        }
        
        // Sort by contrast strength and take top 6 boundaries
        val boundaryContrasts = boundaries.map { x -> x to avgProfile[x] }.sortedByDescending { it.second }
        val topBoundaries = boundaryContrasts.take(6).map { it.first }.sorted()
        
        Log.d(TAG, "Found ${topBoundaries.size} lane boundaries")
        return topBoundaries
    }
    
    fun clearCache() {
        cachedLanes = null
        Log.d(TAG, "Lane cache cleared")
    }
    
    fun getCachedLanes(): List<LaneRegion>? = cachedLanes
}
