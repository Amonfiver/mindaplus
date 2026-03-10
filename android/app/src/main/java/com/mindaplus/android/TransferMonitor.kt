/**
 * TransferMonitor.kt - Monitorización de transfers por detección de color
 *
 * Propósito: Monitorear estado de transfers (OK/OBSTACULO) mediante detección
 *            de color naranja en HSV como estrategia principal.
 *
 * Alcance:
 *   - Detección de lanes/vías
 *   - Análisis de color en ROI de cada vía
 *   - Confirmación temporal de estado (N frames)
 *   - Detección de transiciones de estado
 *   - Cooldown centralizado entre alertas
 *   - Integración con Telegram para notificaciones
 *
 * Modo temporal activo: T100 + OK/OBSTACULO
 *   - FALLO desactivado
 *   - T200/T300 desactivados
 *
 * Estrategia principal: Detección por color HSV
 *   - OBSTACULO: presencia de color naranja en ROI
 *   - OK: ausencia de naranja (gris predominante)
 *   - Transición: Cambio confirmado de OK a OBSTACULO dispara alerta
 *
 * Cambios recientes (SDD - Color HSV):
 *   - Lógica de alerta centralizada (cooldown + transición)
 *   - Retorna eventos de alerta explícitos junto con estados
 *   - Eliminada duplicidad de cooldown con UI
 */
package com.mindaplus.android

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.util.Log

/**
 * Resultado completo del análisis incluyendo estados y alertas
 */
data class AnalysisResult(
    val states: Map<Int, TransferState>,
    val alerts: List<AlertEvent>  // Lista de alertas a enviar
)

/**
 * Evento de alerta para una transición confirmada
 */
data class AlertEvent(
    val transferId: Int,
    val fromState: TransferState,
    val toState: TransferState,
    val timestamp: Long,
    val evidence: Bitmap? = null
)

class TransferMonitor(
    private val context: android.content.Context
) {
    companion object {
        private const val TAG = "TransferMonitor"
        private val ALL_TRANSFERS = listOf(100, 200, 300)
        
        // Frames consecutivos para confirmar cambio de estado
        private const val CONFIRMATION_FRAMES = 2
        
        // Cooldown entre alertas (ms)
        private const val ALERT_COOLDOWN_MS = 60_000L
    }
    
    // Core components
    private val laneDetector = LaneDetector()
    private val colorDetector = ColorDetector()
    private val stateConfirmation = StateConfirmation()
    
    // Estado anterior por transfer para detectar transiciones
    private val previousConfirmedState = mutableMapOf<Int, TransferState>()
    
    // Cooldown centralizado: última vez que se alertó por transfer
    private val lastAlertTimestamp = mutableMapOf<Int, Long>()
    
    // Debug
    private var latestColorResult: ColorDetectionResult? = null
    private var latestLaneBitmap: Bitmap? = null

    /**
     * Analiza frame usando detección por color.
     * Retorna estados actuales y lista de alertas pendientes (transiciones válidas).
     */
    fun analyzeFrame(bitmap: Bitmap): AnalysisResult {
        val analysisTs = System.currentTimeMillis()
        val states = mutableMapOf<Int, TransferState>()
        val alerts = mutableListOf<AlertEvent>()
        
        try {
            Log.d(TAG, "Analyzing frame ${bitmap.width}x${bitmap.height} at ts=$analysisTs")

            // Step 1: Auto-detect lanes
            val laneDetection = laneDetector.detectLanes(bitmap)

            if (laneDetection.requiresCalibration || laneDetection.lanes == null) {
                Log.w(TAG, "Lane detection failed")
                return AnalysisResult(getDefaultStates(), emptyList())
            }

            val lanes = laneDetection.lanes
            if (lanes.size != 3) {
                Log.w(TAG, "Expected 3 lanes, got ${lanes.size}")
                return AnalysisResult(getDefaultStates(), emptyList())
            }

            Log.d(TAG, "Detected ${lanes.size} lanes")

            // Step 2: Analyze each enabled transfer
            val transferIds = MonitoringMode.enabledTransfers

            for (transferId in transferIds) {
                val laneIndex = ALL_TRANSFERS.indexOf(transferId)
                if (laneIndex < 0 || laneIndex >= lanes.size) {
                    states[transferId] = TransferState.UNKNOWN
                    continue
                }

                val lane = lanes[laneIndex]
                
                // Extraer ROI de la lane
                val laneBitmap = extractLaneBitmap(bitmap, lane)
                if (laneBitmap == null) {
                    states[transferId] = TransferState.UNKNOWN
                    continue
                }
                
                latestLaneBitmap = laneBitmap

                // Detección por color (estrategia principal)
                val colorResult = colorDetector.detectState(laneBitmap)
                latestColorResult = colorResult
                
                Log.d(
                    TAG,
                    "T$transferId color: state=${colorResult.state}, " +
                    "conf=${"%.2f".format(colorResult.confidence)}, " +
                    "orange=${"%.1f".format(colorResult.orangePixelRatio * 100)}%"
                )

                // Confirmación temporal
                val confirmation = stateConfirmation.processState(transferId, colorResult.state)
                val confirmedState = confirmation.confirmedState ?: TransferState.UNKNOWN
                
                states[transferId] = confirmedState
                
                // Detectar transición y generar alerta si corresponde
                val previousState = previousConfirmedState[transferId] ?: TransferState.UNKNOWN
                val transitionAlert = checkTransitionAlert(
                    transferId, previousState, confirmedState, analysisTs, colorResult.evidenceBitmap
                )
                if (transitionAlert != null) {
                    alerts.add(transitionAlert)
                }
                
                // Actualizar estado anterior
                previousConfirmedState[transferId] = confirmedState
            }

            // Disabled transfers
            for (transferId in ALL_TRANSFERS) {
                if (!MonitoringMode.isTransferEnabled(transferId)) {
                    states[transferId] = TransferState.UNKNOWN
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing frame", e)
            return AnalysisResult(getDefaultStates(), emptyList())
        }
        
        Log.d(TAG, "Analysis: states=$states, alerts=${alerts.size}")
        return AnalysisResult(states, alerts)
    }
    
    /**
     * Verifica si hay transición válida que deba generar alerta.
     * Solo alerta en transición OK -> OBSTACULO con cooldown respetado.
     */
    private fun checkTransitionAlert(
        transferId: Int,
        fromState: TransferState,
        toState: TransferState,
        timestamp: Long,
        evidence: Bitmap?
    ): AlertEvent? {
        // Solo alertar en transición a OBSTACULO
        if (toState != TransferState.OBSTACULO) return null
        
        // Solo alertar si viene de OK (transición real, no estado inicial)
        if (fromState != TransferState.OK) return null
        
        // Verificar cooldown
        val lastAlert = lastAlertTimestamp[transferId]
        if (lastAlert != null && (timestamp - lastAlert) < ALERT_COOLDOWN_MS) {
            Log.d(TAG, "T$transferId alert blocked by cooldown")
            return null
        }
        
        // Actualizar timestamp de última alerta
        lastAlertTimestamp[transferId] = timestamp
        
        Log.d(TAG, "T$transferId ALERT: $fromState -> $toState")
        
        return AlertEvent(
            transferId = transferId,
            fromState = fromState,
            toState = toState,
            timestamp = timestamp,
            evidence = evidence
        )
    }
    
    /**
     * Extrae bitmap de la lane
     */
    private fun extractLaneBitmap(frame: Bitmap, lane: LaneDetector.LaneRegion): Bitmap? {
        return try {
            val left = lane.left.coerceIn(0, frame.width - 1)
            val top = lane.top.coerceIn(0, frame.height - 1)
            val right = lane.right.coerceIn(left + 1, frame.width)
            val bottom = lane.bottom.coerceIn(top + 1, frame.height)
            
            Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting lane", e)
            null
        }
    }
    
    /**
     * Convierte Image YUV_420_888 a Bitmap
     */
    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap? {
        if (image.format != ImageFormat.YUV_420_888) return null
        
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            
            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer
            
            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val yIdx = y * yRowStride + x * yPixelStride
                    val yVal = yBuffer.get(yIdx).toInt() and 0xFF
                    
                    val uvX = x / 2
                    val uvY = y / 2
                    val uvIdx = uvY * uPlane.rowStride + uvX * uPlane.pixelStride
                    
                    val uVal = (uBuffer.get(uvIdx).toInt() and 0xFF) - 128
                    val vVal = (vBuffer.get(uvIdx).toInt() and 0xFF) - 128
                    
                    val r = (yVal + 1.402f * vVal).toInt().coerceIn(0, 255)
                    val g = (yVal - 0.344f * uVal - 0.714f * vVal).toInt().coerceIn(0, 255)
                    val b = (yVal + 1.772f * uVal).toInt().coerceIn(0, 255)
                    
                    bitmap.setPixel(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
                }
            }
            
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error converting image", e)
            null
        }
    }
    
    /**
     * Obtiene último resultado de color para debug
     */
    fun getLatestColorResult(): ColorDetectionResult? = latestColorResult
    
    /**
     * Obtiene último lane bitmap para debug
     */
    fun getLatestLaneBitmap(): Bitmap? = latestLaneBitmap
    
    // Legacy methods (mantenidos para compatibilidad)
    
    fun isTrained(): Boolean = true  // Ya no requiere entrenamiento
    
    fun getTrainingProgress(): Pair<Int, Int> = 1 to 1  // Siempre "completo"
    
    fun getTrainingStats(): TemplateStorage.TrainingStats {
        return TemplateStorage.TrainingStats(
            baseCovered = 1,
            baseTotal = 1,
            totalSamples = 0,
            sampleCounts = emptyMap()
        )
    }
    
    fun getLatestDebugSnapshot(transferId: Int): Any? = null  // Legacy, no usado
    
    fun clearTraining() {
        laneDetector.clearCache()
        stateConfirmation.reset()
        previousConfirmedState.clear()
        lastAlertTimestamp.clear()
    }
    
    fun recalibrateLanes() {
        laneDetector.clearCache()
        stateConfirmation.reset()
        Log.d(TAG, "Lanes recalibrated")
    }
    
    private fun getDefaultStates(): Map<Int, TransferState> {
        return mapOf(
            100 to TransferState.UNKNOWN,
            200 to TransferState.UNKNOWN,
            300 to TransferState.UNKNOWN
        )
    }
}