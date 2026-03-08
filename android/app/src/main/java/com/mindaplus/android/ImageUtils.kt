package com.mindaplus.android

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.camera.core.ExperimentalGetImage
import java.nio.ByteBuffer
import kotlin.math.min

object ImageUtils {
    private const val TAG = "ImageUtils"

    data class FitDisplayMapping(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
        val drawnWidth: Float,
        val drawnHeight: Float
    )
    
    /**
     * Convierte un ImageProxy a Bitmap de forma segura antes de que se cierre.
     * Esta función debe llamarse ANTES de cerrar el ImageProxy.
     */
    @OptIn(markerClass = [ExperimentalGetImage::class])
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val image = imageProxy.image
            if (image == null) {
                Log.w(TAG, "ImageProxy.image is null")
                return null
            }
            
            // CameraX ImageAnalysis normalmente usa YUV_420_888
            if (imageProxy.format == ImageFormat.YUV_420_888) {
                yuv420ToBitmap(image)
            } else {
                Log.w(TAG, "Unsupported image format: ${imageProxy.format}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting ImageProxy to Bitmap", e)
            null
        }
    }
    
    /**
     * Convierte una Image YUV_420_888 a Bitmap
     */
    private fun yuv420ToBitmap(image: Image): Bitmap? {
        return try {
            val width = image.width
            val height = image.height
            
            val yBuffer = image.planes[0].buffer // Y
            val uBuffer = image.planes[1].buffer // U
            val vBuffer = image.planes[2].buffer // V
            
            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            
            val nv21 = ByteArray(ySize + uSize + vSize)
            
            // Copiar datos YUV a un array temporal
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)
            
            // Convertir NV21 a RGB
            val rgba = IntArray(width * height)
            nv21ToRgba(nv21, width, height, rgba)
            
            Bitmap.createBitmap(rgba, width, height, Bitmap.Config.ARGB_8888)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error converting YUV_420_888 to Bitmap", e)
            null
        }
    }
    
    /**
     * Convierte una Image RGBA a Bitmap
     */
    private fun rgbaToBitmap(image: Image): Bitmap? {
        return try {
            val width = image.width
            val height = image.height
            
            val buffer = image.planes[0].buffer
            val pixelStride = image.planes[0].pixelStride
            val rowStride = image.planes[0].rowStride
            
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            
            // Copiar directamente si el formato es compatible
            if (pixelStride == 4 && rowStride == width * 4) {
                buffer.rewind()
                bitmap.copyPixelsFromBuffer(buffer)
            } else {
                // Copiar pixel por pixel si hay stride
                val pixels = IntArray(width * height)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val bufferIndex = y * rowStride + x * pixelStride
                        val pixel = buffer.getInt(bufferIndex)
                        pixels[y * width + x] = pixel
                    }
                }
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            }
            
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error converting RGBA to Bitmap", e)
            null
        }
    }
    
    /**
     * Convierte formato NV21 a RGBA
     */
    private fun nv21ToRgba(nv21: ByteArray, width: Int, height: Int, rgba: IntArray) {
        val frameSize = width * height
        
        for (j in 0 until height) {
            for (i in 0 until width) {
                val y = (nv21[j * width + i].toInt() and 0xff)
                val v = (nv21[frameSize + (j / 2) * width + (i / 2) * 2].toInt() and 0xff)
                val u = (nv21[frameSize + (j / 2) * width + (i / 2) * 2 + 1].toInt() and 0xff)
                
                val yValue = if (y < 16) 16 else y
                val uValue = u - 128
                val vValue = v - 128
                
                val r = (1.164 * (yValue - 16) + 1.596 * vValue).toInt().coerceIn(0, 255)
                val g = (1.164 * (yValue - 16) - 0.813 * vValue - 0.391 * uValue).toInt().coerceIn(0, 255)
                val b = (1.164 * (yValue - 16) + 2.018 * uValue).toInt().coerceIn(0, 255)
                
                rgba[j * width + i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
    
    /**
     * Recorta un Bitmap a una región específica
     */
    fun cropBitmap(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Bitmap? {
        return try {
            // Asegurar que los límites estén dentro del bitmap
            val validLeft = left.coerceIn(0, bitmap.width - 1)
            val validTop = top.coerceIn(0, bitmap.height - 1)
            val validRight = right.coerceIn(validLeft + 1, bitmap.width)
            val validBottom = bottom.coerceIn(validTop + 1, bitmap.height)
            
            val width = validRight - validLeft
            val height = validBottom - validTop
            
            if (width <= 0 || height <= 0) {
                Log.w(TAG, "Invalid crop dimensions: ${width}x${height}")
                return null
            }
            
            Bitmap.createBitmap(bitmap, validLeft, validTop, width, height)
        } catch (e: Exception) {
            Log.e(TAG, "Error cropping bitmap", e)
            null
        }
    }

    /**
     * Recorta una sub-ROI centrada por ratios (0..1). Si el recorte no es válido, devuelve null.
     */
    fun cropCenteredByRatio(bitmap: Bitmap, widthRatio: Float, heightRatio: Float): Bitmap? {
        return try {
            if (bitmap.width <= 1 || bitmap.height <= 1) return null

            val safeWidthRatio = widthRatio.coerceIn(0.1f, 1f)
            val safeHeightRatio = heightRatio.coerceIn(0.1f, 1f)

            val targetWidth = (bitmap.width * safeWidthRatio).toInt().coerceIn(1, bitmap.width)
            val targetHeight = (bitmap.height * safeHeightRatio).toInt().coerceIn(1, bitmap.height)

            val left = ((bitmap.width - targetWidth) / 2).coerceIn(0, bitmap.width - 1)
            val top = ((bitmap.height - targetHeight) / 2).coerceIn(0, bitmap.height - 1)
            val right = (left + targetWidth).coerceIn(left + 1, bitmap.width)
            val bottom = (top + targetHeight).coerceIn(top + 1, bitmap.height)

            Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating centered crop", e)
            null
        }
    }

    /**
     * Mapeo para mostrar bitmap en una vista usando comportamiento equivalente a ContentScale.Fit.
     */
    fun computeFitDisplayMapping(
        viewWidth: Int,
        viewHeight: Int,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): FitDisplayMapping {
        val safeViewW = viewWidth.coerceAtLeast(1)
        val safeViewH = viewHeight.coerceAtLeast(1)
        val safeBmpW = bitmapWidth.coerceAtLeast(1)
        val safeBmpH = bitmapHeight.coerceAtLeast(1)

        val scaleX = safeViewW.toFloat() / safeBmpW.toFloat()
        val scaleY = safeViewH.toFloat() / safeBmpH.toFloat()
        val scale = min(scaleX, scaleY)
        val drawnW = safeBmpW * scale
        val drawnH = safeBmpH * scale
        val offsetX = (safeViewW - drawnW) / 2f
        val offsetY = (safeViewH - drawnH) / 2f
        return FitDisplayMapping(
            scale = scale,
            offsetX = offsetX,
            offsetY = offsetY,
            drawnWidth = drawnW,
            drawnHeight = drawnH
        )
    }
}
