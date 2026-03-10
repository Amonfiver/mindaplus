/**
 * ColorDetector.kt - Detección de estado por color HSV
 *
 * Propósito: Detectar estado OBSTACULO (naranja) vs OK (gris) mediante
 *            análisis de color en espacio HSV.
 *
 * Alcance:
 *   - Conversión RGB → HSV
 *   - Máscara de color naranja configurable
 *   - Limpieza morfológica de ruido
 *   - Detección de blobs/regiones válidas
 *   - Filtrado por área mínima
 *   - Retorno de resultado + bitmap de evidencia
 *
 * Modo temporal activo: T100 + OK/OBSTACULO
 *   - FALLO desactivado
 *   - T200/T300 desactivados
 *
 * Estrategia de detección:
 *   - Estado OBSTACULO: presencia significativa de color naranja en ROI
 *   - Estado OK: ausencia de naranja (fondo gris predominante)
 *
 * Cambios recientes (SDD - Color HSV):
 *   - Sistema principal de detección basado en color
 *   - Eliminado matching de templates como flujo principal
 *   - Parámetros HSV configurables
 */
package com.mindaplus.android

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Resultado de detección por color
 */
data class ColorDetectionResult(
    val state: TransferState,
    val confidence: Float,           // 0.0 - 1.0
    val orangePixelRatio: Float,     // Porcentaje de píxeles naranja detectados
    val hasValidBlob: Boolean,       // Si hay blob válido después de filtrado
    val blobArea: Int,               // Área del blob principal (si existe)
    val evidenceBitmap: Bitmap?      // Bitmap de evidencia para debug/alerta
)

/**
 * Configuración de rango HSV para color naranja
 */
data class HsvRange(
    val hueMin: Float, val hueMax: Float,      // Tono (0-360)
    val satMin: Float, val satMax: Float,      // Saturación (0-1)
    val valMin: Float, val valMax: Float       // Valor/Brillo (0-1)
) {
    companion object {
        /**
         * Rango por defecto para naranja industrial típico
         * Ajustable según condiciones de iluminación
         */
        val DEFAULT_ORANGE = HsvRange(
            hueMin = 15f, hueMax = 45f,        // Naranja: ~15-45° en HSV
            satMin = 0.4f, satMax = 1.0f,      // Saturación media-alta
            valMin = 0.3f, valMax = 1.0f       // Brillo medio-alto
        )
        
        /**
         * Rango amplio para capturar más variaciones
         */
        val WIDE_ORANGE = HsvRange(
            hueMin = 10f, hueMax = 50f,
            satMin = 0.3f, satMax = 1.0f,
            valMin = 0.2f, valMax = 1.0f
        )
    }
}

class ColorDetector {
    
    companion object {
        private const val TAG = "ColorDetector"
        
        // Parámetros de filtrado morfológico
        private const val MORPH_KERNEL_SIZE = 3
        
        // Área mínima de blob para considerar válido (porcentaje del ROI)
        private const val MIN_BLOB_AREA_PERCENT = 2.0f
        
        // Umbral de decisión: ratio de píxeles naranja para considerar OBSTACULO
        private const val ORANGE_RATIO_THRESHOLD = 0.05f  // 5% mínimo
        
        // Confianza base cuando se detecta naranja
        private const val BASE_CONFIDENCE_ORANGE = 0.85f
        
        // Confianza base cuando NO se detecta naranja (OK)
        private const val BASE_CONFIDENCE_OK = 0.90f
    }
    
    /**
     * Detecta estado analizando color naranja en el bitmap
     *
     * @param bitmap ROI a analizar
     * @param hsvRange Rango HSV para naranja (null = usar default)
     * @return Resultado de detección con estado y evidencia
     */
    fun detectState(
        bitmap: Bitmap,
        hsvRange: HsvRange? = null
    ): ColorDetectionResult {
        val range = hsvRange ?: HsvRange.DEFAULT_ORANGE
        
        // Crear máscara de naranja
        val (orangeMask, orangeCount) = createOrangeMask(bitmap, range)
        
        // Calcular ratio
        val totalPixels = bitmap.width * bitmap.height
        val orangeRatio = orangeCount.toFloat() / totalPixels
        
        // Aplicar limpieza morfológica
        val cleanedMask = applyMorphologicalCleanup(orangeMask, bitmap.width, bitmap.height)
        
        // Encontrar blobs válidos
        val (hasBlob, blobArea) = findLargestBlob(cleanedMask, bitmap.width, bitmap.height)
        
        // Calcular área mínima requerida
        val minArea = (totalPixels * MIN_BLOB_AREA_PERCENT / 100).toInt()
        
        // Determinar estado
        val isOrangeDetected = orangeRatio > ORANGE_RATIO_THRESHOLD && hasBlob && blobArea >= minArea
        
        val (state, confidence) = if (isOrangeDetected) {
            // OBSTACULO detectado - confianza proporcional al ratio
            val conf = min(BASE_CONFIDENCE_ORANGE + (orangeRatio * 0.15f), 0.99f)
            TransferState.OBSTACULO to conf
        } else {
            // OK - ausencia de naranja significativa
            val conf = BASE_CONFIDENCE_OK - (orangeRatio * 0.5f)  // Penaliza si hay algo de naranja
            TransferState.OK to max(conf, 0.70f)
        }
        
        // Crear bitmap de evidencia (overlay de máscara sobre original)
        val evidenceBitmap = createEvidenceBitmap(bitmap, cleanedMask)
        
        return ColorDetectionResult(
            state = state,
            confidence = confidence,
            orangePixelRatio = orangeRatio,
            hasValidBlob = hasBlob && blobArea >= minArea,
            blobArea = blobArea,
            evidenceBitmap = evidenceBitmap
        )
    }
    
    /**
     * Crea máscara binaria de píxeles naranja
     */
    private fun createOrangeMask(bitmap: Bitmap, range: HsvRange): Pair<BooleanArray, Int> {
        val width = bitmap.width
        val height = bitmap.height
        val mask = BooleanArray(width * height)
        var orangeCount = 0
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val rgb = intArrayOf(
                    Color.red(pixel),
                    Color.green(pixel),
                    Color.blue(pixel)
                )
                
                val hsv = rgbToHsv(rgb[0], rgb[1], rgb[2])
                val isOrange = isInHsvRange(hsv, range)
                
                val idx = y * width + x
                mask[idx] = isOrange
                if (isOrange) orangeCount++
            }
        }
        
        return mask to orangeCount
    }
    
    /**
     * Convierte RGB a HSV
     * Retorna FloatArray de [hue(0-360), saturation(0-1), value(0-1)]
     */
    private fun rgbToHsv(r: Int, g: Int, b: Int): FloatArray {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        
        val max = max(rf, max(gf, bf))
        val min = min(rf, min(gf, bf))
        val diff = max - min
        
        // Hue
        val hue = when {
            diff < 0.0001f -> 0f
            max == rf -> (60 * ((gf - bf) / diff) + 360) % 360
            max == gf -> (60 * ((bf - rf) / diff) + 120)
            else -> (60 * ((rf - gf) / diff) + 240)
        }
        
        // Saturation
        val sat = if (max < 0.0001f) 0f else diff / max
        
        // Value
        val value = max
        
        return floatArrayOf(hue, sat, value)
    }
    
    /**
     * Verifica si un color HSV está dentro del rango
     */
    private fun isInHsvRange(hsv: FloatArray, range: HsvRange): Boolean {
        val (h, s, v) = hsv
        
        // Manejar wrap-around del hue (para rojos que cruzan 0/360)
        val hueInRange = if (range.hueMin > range.hueMax) {
            // Rango cruza 0° (ej: 350-10 para rojo)
            h >= range.hueMin || h <= range.hueMax
        } else {
            h in range.hueMin..range.hueMax
        }
        
        return hueInRange &&
               s in range.satMin..range.satMax &&
               v in range.valMin..range.valMax
    }
    
    /**
     * Aplica limpieza morfológica simple (erosión + dilatación)
     * Implementación básica sin OpenCV
     */
    private fun applyMorphologicalCleanup(
        mask: BooleanArray,
        width: Int,
        height: Int
    ): BooleanArray {
        // Erosión: eliminar píxeles aislados
        val eroded = BooleanArray(mask.size)
        val halfKernel = MORPH_KERNEL_SIZE / 2
        
        for (y in halfKernel until height - halfKernel) {
            for (x in halfKernel until width - halfKernel) {
                var allTrue = true
                for (ky in -halfKernel..halfKernel) {
                    for (kx in -halfKernel..halfKernel) {
                        val idx = (y + ky) * width + (x + kx)
                        if (!mask[idx]) {
                            allTrue = false
                            break
                        }
                    }
                    if (!allTrue) break
                }
                eroded[y * width + x] = allTrue
            }
        }
        
        // Dilatación: cerrar huecos pequeños
        val dilated = BooleanArray(mask.size)
        
        for (y in halfKernel until height - halfKernel) {
            for (x in halfKernel until width - halfKernel) {
                var anyTrue = false
                for (ky in -halfKernel..halfKernel) {
                    for (kx in -halfKernel..halfKernel) {
                        val idx = (y + ky) * width + (x + kx)
                        if (eroded[idx]) {
                            anyTrue = true
                            break
                        }
                    }
                    if (anyTrue) break
                }
                dilated[y * width + x] = anyTrue
            }
        }
        
        return dilated
    }
    
    /**
     * Encuentra el blob conectado más grande usando flood fill simple
     */
    private fun findLargestBlob(
        mask: BooleanArray,
        width: Int,
        height: Int
    ): Pair<Boolean, Int> {
        val visited = BooleanArray(mask.size)
        var maxArea = 0
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (mask[idx] && !visited[idx]) {
                    val area = floodFillArea(mask, visited, x, y, width, height)
                    if (area > maxArea) {
                        maxArea = area
                    }
                }
            }
        }
        
        return (maxArea > 0) to maxArea
    }
    
    /**
     * Flood fill para contar área de blob
     */
    private fun floodFillArea(
        mask: BooleanArray,
        visited: BooleanArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int
    ): Int {
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.add(startX to startY)
        visited[startY * width + startX] = true
        var area = 0
        
        val directions = arrayOf(
            -1 to 0, 1 to 0, 0 to -1, 0 to 1  // 4-conectividad
        )
        
        while (stack.isNotEmpty()) {
            val (x, y) = stack.removeLast()
            area++
            
            for ((dx, dy) in directions) {
                val nx = x + dx
                val ny = y + dy
                val nIdx = ny * width + nx
                
                if (nx in 0 until width && 
                    ny in 0 until height &&
                    !visited[nIdx] && 
                    mask[nIdx]) {
                    visited[nIdx] = true
                    stack.add(nx to ny)
                }
            }
        }
        
        return area
    }
    
    /**
     * Crea bitmap de evidencia con overlay de máscara
     */
    private fun createEvidenceBitmap(
        original: Bitmap,
        mask: BooleanArray
    ): Bitmap {
        val width = original.width
        val height = original.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val originalPixel = original.getPixel(x, y)
                
                if (mask[idx]) {
                    // Píxel naranja detectado: resaltar en verde brillante semitransparente
                    val overlay = Color.argb(128, 0, 255, 0)
                    result.setPixel(x, y, blendColors(originalPixel, overlay))
                } else {
                    // Píxel normal: oscurecer ligeramente
                    val darkened = Color.argb(
                        255,
                        (Color.red(originalPixel) * 0.7f).toInt(),
                        (Color.green(originalPixel) * 0.7f).toInt(),
                        (Color.blue(originalPixel) * 0.7f).toInt()
                    )
                    result.setPixel(x, y, darkened)
                }
            }
        }
        
        return result
    }
    
    /**
     * Mezcla dos colores con alpha
     */
    private fun blendColors(background: Int, overlay: Int): Int {
        val alpha = Color.alpha(overlay) / 255f
        val invAlpha = 1 - alpha
        
        val r = (Color.red(overlay) * alpha + Color.red(background) * invAlpha).toInt()
        val g = (Color.green(overlay) * alpha + Color.green(background) * invAlpha).toInt()
        val b = (Color.blue(overlay) * alpha + Color.blue(background) * invAlpha).toInt()
        
        return Color.rgb(r, g, b)
    }
}