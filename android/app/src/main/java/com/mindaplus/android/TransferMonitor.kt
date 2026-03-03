package com.mindaplus.android

import android.graphics.ImageFormat
import android.media.Image
import android.util.Log
import kotlin.math.max
import kotlin.math.min

class TransferMonitor {
    companion object {
        // Fixed ROI coordinates (relative 0..1) as specified in v0.1
        private val ROI_T100 = RectF(0.1f, 0.2f, 0.3f, 0.4f)
        private val ROI_T200 = RectF(0.4f, 0.2f, 0.6f, 0.4f)
        private val ROI_T300 = RectF(0.7f, 0.2f, 0.9f, 0.4f)
        
        // Orange detection thresholds (HSV ranges)
        private const val HUE_MIN = 10f  // Orange hue range
        private const val HUE_MAX = 25f
        private const val SAT_MIN = 0.5f
        private const val SAT_MAX = 1.0f
        private const val VAL_MIN = 0.4f
        private const val VAL_MAX = 1.0f
        
        // Proportion threshold for orange detection (configurable)
        private const val ORANGE_THRESHOLD = 0.15f // 15% of ROI must be orange
    }
    
    data class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float)
    
    fun analyzeFrame(image: Image, imageWidth: Int, imageHeight: Int): Map<Int, TransferState> {
        try {
            if (image.format != ImageFormat.YUV_420_888) {
                Log.w("Mindaplus", "Unsupported image format: ${image.format}")
                return mapOf(100 to TransferState.OK, 200 to TransferState.OK, 300 to TransferState.OK)
            }
            
            val results = mutableMapOf<Int, TransferState>()
            
            // Analyze each ROI
            results[100] = analyzeROI(image, imageWidth, imageHeight, ROI_T100)
            results[200] = analyzeROI(image, imageWidth, imageHeight, ROI_T200)
            results[300] = analyzeROI(image, imageWidth, imageHeight, ROI_T300)
            
            return results
            
        } catch (e: Exception) {
            Log.e("Mindaplus", "Error analyzing frame", e)
            return mapOf(100 to TransferState.OK, 200 to TransferState.OK, 300 to TransferState.OK)
        }
    }
    
    private fun analyzeROI(image: Image, imageWidth: Int, imageHeight: Int, roi: RectF): TransferState {
        try {
            val planes = image.planes
            if (planes == null || planes.size < 3) {
                Log.w("Mindaplus", "TransferMonitor: Invalid planes array, size=${planes?.size ?: 0}")
                return TransferState.OK
            }
            
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]
            
            if (yPlane == null || uPlane == null || vPlane == null) {
                Log.w("Mindaplus", "TransferMonitor: One or more planes are null")
                return TransferState.OK
            }
            
            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer
            
            if (yBuffer == null || uBuffer == null || vBuffer == null) {
                Log.w("Mindaplus", "TransferMonitor: One or more buffers are null")
                return TransferState.OK
            }
            
            val yRowStride = yPlane.rowStride
            val uvRowStride = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride
            
            if (yRowStride <= 0 || uvRowStride <= 0 || uvPixelStride <= 0) {
                Log.w("Mindaplus", "TransferMonitor: Invalid stride values")
                return TransferState.OK
            }
            
            // Convert relative ROI to absolute pixels
            val left = (roi.left * imageWidth).toInt()
            val top = (roi.top * imageHeight).toInt()
            val right = (roi.right * imageWidth).toInt()
            val bottom = (roi.bottom * imageHeight).toInt()
            
            val roiWidth = right - left
            val roiHeight = bottom - top
            
            if (roiWidth <= 0 || roiHeight <= 0) {
                Log.w("Mindaplus", "TransferMonitor: Invalid ROI dimensions")
                return TransferState.OK
            }
            
            if (left < 0 || top < 0 || right > imageWidth || bottom > imageHeight) {
                Log.w("Mindaplus", "TransferMonitor: ROI out of bounds")
                return TransferState.OK
            }
            
            var orangePixelCount = 0
            var totalPixelCount = 0
            
            // Sample pixels (analyze every 4th pixel for performance)
            for (y in top until bottom step 4) {
                for (x in left until right step 4) {
                    if (x >= imageWidth || y >= imageHeight) continue
                    
                    val yIndex = y * yRowStride + x
                    val uvIndex = (y / 2) * uvRowStride + (x / 2) * uvPixelStride
                    
                    // Check buffer bounds before accessing
                    if (yIndex < 0 || yIndex >= yBuffer.limit() ||
                        uvIndex < 0 || uvIndex >= uBuffer.limit() ||
                        uvIndex < 0 || uvIndex >= vBuffer.limit()) {
                        Log.w("Mindaplus", "TransferMonitor: Buffer index out of bounds")
                        continue
                    }
                    
                    val yValue = yBuffer.get(yIndex).toInt() and 0xFF
                    val uValue = uBuffer.get(uvIndex).toInt() and 0xFF
                    val vValue = vBuffer.get(uvIndex).toInt() and 0xFF
                    
                    // Convert YUV to RGB
                    val rgb = yuvToRgb(yValue, uValue, vValue)
                    
                    // Convert RGB to HSV
                    val hsv = rgbToHsv(rgb.first, rgb.second, rgb.third)
                    
                    // Check if pixel is orange
                    if (isOrange(hsv.first, hsv.second, hsv.third)) {
                        orangePixelCount++
                    }
                    
                    totalPixelCount++
                }
            }
            
            if (totalPixelCount == 0) {
                return TransferState.OK
            }
            
            val orangeRatio = orangePixelCount.toFloat() / totalPixelCount.toFloat()
            
            Log.d("Mindaplus", "TransferMonitor: ROI analysis - Orange ratio: $orangeRatio, threshold: $ORANGE_THRESHOLD")
            
            return if (orangeRatio >= ORANGE_THRESHOLD) {
                TransferState.OBSTACULO
            } else {
                TransferState.OK
            }
            
        } catch (e: Exception) {
            Log.e("Mindaplus", "TransferMonitor: Error analyzing ROI", e)
            return TransferState.OK
        }
    }
    
    private fun yuvToRgb(y: Int, u: Int, v: Int): Triple<Int, Int, Int> {
        // Standard YUV to RGB conversion
        val c = y - 16
        val d = u - 128
        val e = v - 128
        
        val r = (298 * c + 409 * e + 128) shr 8
        val g = (298 * c - 100 * d - 208 * e + 128) shr 8
        val b = (298 * c + 516 * d + 128) shr 8
        
        return Triple(
            max(0, min(255, r)),
            max(0, min(255, g)),
            max(0, min(255, b))
        )
    }
    
    private fun rgbToHsv(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val delta = max - min
        
        val h = when {
            delta == 0f -> 0f
            max == rf -> ((gf - bf) / delta) % 6
            max == gf -> ((bf - rf) / delta) + 2
            else -> ((rf - gf) / delta) + 4
        } * 60f
        
        val s = if (max == 0f) 0f else delta / max
        val v = max
        
        return Triple(if (h < 0) h + 360 else h, s, v)
    }
    
    private fun isOrange(h: Float, s: Float, v: Float): Boolean {
        return h in HUE_MIN..HUE_MAX &&
               s in SAT_MIN..SAT_MAX &&
               v in VAL_MIN..VAL_MAX
    }
}