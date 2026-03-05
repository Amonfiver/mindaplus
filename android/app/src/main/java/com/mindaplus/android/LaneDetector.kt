package com.mindaplus.android

import android.graphics.ImageFormat
import android.media.Image
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class LaneDetector {
    companion object {
        private const val TAG = "LaneDetector"
        private const val DOWNSAMPLE_FACTOR = 4
        private const val MIN_LANE_WIDTH_PX = 50
        private const val MIN_CONTRAST_THRESHOLD = 30
        private const val LANE_DETECTION_ROWS = 20 // Number of rows to analyze
    }
    
    data class LaneRegion(val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class DetectionResult(val lanes: List<LaneRegion>?, val requiresCalibration: Boolean)
    
    private var cachedLanes: List<LaneRegion>? = null
    
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
            
            val yPlane = image.planes[0]
            val yBuffer = yPlane.buffer
            val yRowStride = yPlane.rowStride
            
            // Downsample for performance
            val downsampledWidth = imageWidth / DOWNSAMPLE_FACTOR
            val downsampledHeight = imageHeight / DOWNSAMPLE_FACTOR
            
            // Analyze contrast profile across multiple rows
            val contrastProfiles = mutableListOf<IntArray>()
            val rowStep = max(1, imageHeight / LANE_DETECTION_ROWS)
            
            for (y in 0 until imageHeight step rowStep) {
                val profile = analyzeRowContrast(y, yBuffer, yRowStride, imageWidth, downsampledWidth)
                contrastProfiles.add(profile)
            }
            
            // Find lane boundaries by detecting high contrast regions
            val laneBoundaries = findLaneBoundaries(contrastProfiles, downsampledWidth)
            
            if (laneBoundaries.size < 6) { // Need at least 3 lanes (6 boundaries)
                Log.w(TAG, "Insufficient lane boundaries detected: ${laneBoundaries.size}")
                return DetectionResult(null, true)
            }
            
            // Convert boundaries to lane regions
            val lanes = mutableListOf<LaneRegion>()
            for (i in 0 until laneBoundaries.size - 1 step 2) {
                if (i + 1 < laneBoundaries.size) {
                    val left = laneBoundaries[i] * DOWNSAMPLE_FACTOR
                    val right = laneBoundaries[i + 1] * DOWNSAMPLE_FACTOR
                    
                    if (right - left >= MIN_LANE_WIDTH_PX) {
                        val top = imageHeight * 0.2f
                        val bottom = imageHeight * 0.4f
                        
                        lanes.add(LaneRegion(left, top.toInt(), right, bottom.toInt()))
                        
                        if (lanes.size == 3) break // We only need 3 lanes
                    }
                }
            }
            
            if (lanes.size != 3) {
                Log.w(TAG, "Failed to detect exactly 3 lanes: ${lanes.size}")
                return DetectionResult(null, true)
            }
            
            // Cache the detected lanes
            cachedLanes = lanes
            Log.d(TAG, "Successfully detected ${lanes.size} lanes")
            
            return DetectionResult(lanes, false)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting lanes", e)
            return DetectionResult(null, true)
        }
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