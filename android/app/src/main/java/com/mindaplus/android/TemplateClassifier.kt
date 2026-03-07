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
        private const val SIMILARITY_THRESHOLD = 0.7f // Minimum similarity for classification
    }
    
    data class ClassificationResult(
        val state: TransferState,
        val similarity: Float,
        val isConfident: Boolean
    )

    fun classifyROI(bitmap: Bitmap, roi: LaneDetector.LaneRegion, transferId: Int): ClassificationResult {
        try {
            // Load templates for this transfer
            val templates = mutableMapOf<TransferState, Bitmap>()
            var templateCount = 0

            for (state in listOf(TransferState.OK, TransferState.OBSTACULO, TransferState.FALLO)) {
                val template = templateStorage.loadTemplate(transferId, state)
                if (template != null) {
                    templates[state] = template
                    templateCount++
                }
            }

            if (templateCount == 0) {
                Log.w(TAG, "No templates found for transfer $transferId")
                return ClassificationResult(TransferState.UNKNOWN, 0f, false)
            }

            // Extract and normalize ROI from current frame
            val currentROI = extractROI(bitmap, roi)
            if (currentROI == null) {
                Log.w(TAG, "Failed to extract ROI for transfer $transferId")
                return ClassificationResult(TransferState.UNKNOWN, 0f, false)
            }

            // Compare with each template and find best match
            var bestState = TransferState.UNKNOWN
            var bestSimilarity = 0f

            for ((state, template) in templates) {
                val similarity = calculateSimilarity(currentROI, template)
                Log.d(TAG, "Transfer $transferId $state similarity: $similarity")

                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    bestState = state
                }
            }

            val isConfident = bestSimilarity >= SIMILARITY_THRESHOLD
            Log.d(TAG, "Transfer $transferId classified as $bestState (similarity: $bestSimilarity, confident: $isConfident)")

            return ClassificationResult(bestState, bestSimilarity, isConfident)

        } catch (e: Exception) {
            Log.e(TAG, "Error classifying ROI for transfer $transferId", e)
            return ClassificationResult(TransferState.UNKNOWN, 0f, false)
        }
    }
    
    fun classifyROI(image: Image, imageWidth: Int, imageHeight: Int, roi: LaneDetector.LaneRegion, transferId: Int): ClassificationResult {
        try {
            // Load templates for this transfer
            val templates = mutableMapOf<TransferState, Bitmap>()
            var templateCount = 0
            
            for (state in listOf(TransferState.OK, TransferState.OBSTACULO, TransferState.FALLO)) {
                val template = templateStorage.loadTemplate(transferId, state)
                if (template != null) {
                    templates[state] = template
                    templateCount++
                }
            }
            
            if (templateCount == 0) {
                Log.w(TAG, "No templates found for transfer $transferId")
                return ClassificationResult(TransferState.UNKNOWN, 0f, false)
            }
            
            // Extract and normalize ROI from current frame
            val currentROI = extractROI(image, imageWidth, imageHeight, roi)
            if (currentROI == null) {
                Log.w(TAG, "Failed to extract ROI for transfer $transferId")
                return ClassificationResult(TransferState.UNKNOWN, 0f, false)
            }
            
            // Compare with each template and find best match
            var bestState = TransferState.UNKNOWN
            var bestSimilarity = 0f
            
            for ((state, template) in templates) {
                val similarity = calculateSimilarity(currentROI, template)
                Log.d(TAG, "Transfer $transferId $state similarity: $similarity")
                
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    bestState = state
                }
            }
            
            val isConfident = bestSimilarity >= SIMILARITY_THRESHOLD
            Log.d(TAG, "Transfer $transferId classified as $bestState (similarity: $bestSimilarity, confident: $isConfident)")
            
            return ClassificationResult(bestState, bestSimilarity, isConfident)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error classifying ROI for transfer $transferId", e)
            return ClassificationResult(TransferState.UNKNOWN, 0f, false)
        }
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
            
            // Create grayscale bitmap from Y plane
            val bitmap = Bitmap.createBitmap(roiWidth, roiHeight, Bitmap.Config.ARGB_8888)
            
            for (y in top until bottom) {
                val rowStart = y * yRowStride
                for (x in left until right) {
                    val yValue = yBuffer.get(rowStart + x).toInt() and 0xFF
                    val gray = yValue or (yValue shl 8) or (yValue shl 16) or (0xFF shl 24)
                    bitmap.setPixel(x - left, y - top, gray)
                }
            }
            
            // Resize to template size
            return Bitmap.createScaledBitmap(bitmap, TEMPLATE_WIDTH, TEMPLATE_HEIGHT, true)
            
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

            // Create grayscale bitmap for consistent template matching
            val roiBitmap = Bitmap.createBitmap(roiWidth, roiHeight, Bitmap.Config.ARGB_8888)

            for (y in top until bottom) {
                for (x in left until right) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
                    val grayPixel = gray or (gray shl 8) or (gray shl 16) or (0xFF shl 24)
                    roiBitmap.setPixel(x - left, y - top, grayPixel)
                }
            }

            // Resize to template size
            return Bitmap.createScaledBitmap(roiBitmap, TEMPLATE_WIDTH, TEMPLATE_HEIGHT, true)

        } catch (e: Exception) {
            Log.e(TAG, "Error extracting ROI from bitmap", e)
            return null
        }
    }
    
    private fun calculateSimilarity(bitmap1: Bitmap, bitmap2: Bitmap): Float {
        try {
            if (bitmap1.width != bitmap2.width || bitmap1.height != bitmap2.height) {
                Log.w(TAG, "Bitmap dimensions mismatch: ${bitmap1.width}x${bitmap1.height} vs ${bitmap2.width}x${bitmap2.height}")
                return 0f
            }
            
            val width = bitmap1.width
            val height = bitmap1.height
            
            var totalDifference = 0L
            var pixelCount = 0
            
            // Sample every 4th pixel for performance
            for (y in 0 until height step 4) {
                for (x in 0 until width step 4) {
                    val pixel1 = bitmap1.getPixel(x, y)
                    val pixel2 = bitmap2.getPixel(x, y)
                    
                    // Extract grayscale values
                    val gray1 = pixel1 and 0xFF
                    val gray2 = pixel2 and 0xFF
                    
                    totalDifference += abs(gray1 - gray2)
                    pixelCount++
                }
            }
            
            if (pixelCount == 0) {
                return 0f
            }
            
            val avgDifference = totalDifference.toFloat() / pixelCount.toFloat()
            val maxPossibleDifference = 255f
            
            // Convert difference to similarity (0.0 to 1.0)
            val similarity = 1.0f - (avgDifference / maxPossibleDifference)
            
            return kotlin.math.max(0.0f, kotlin.math.min(1.0f, similarity))
            
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating similarity", e)
            return 0f
        }
    }
}
