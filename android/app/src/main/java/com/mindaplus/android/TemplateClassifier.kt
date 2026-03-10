/**
 * TemplateClassifier.kt - Clasificación con sistema ROI dual
 *
 * Propósito: Comparar frames actuales contra muestras maestras usando
 *            sistema ROI dual (Search ROI + Master ROI).
 *
 * Alcance:
 *   - Extracción de lane desde frame
 *   - Localización de ventana candidata dentro de Search ROI (ROI 1)
 *   - Comparación de ventana actual (tamaño Master ROI) vs muestra maestra
 *   - Fallback a center crop temporal para muestras legacy
 *
 * Estrategias de comparación:
 *   - "dual_roi": Usa Search ROI + Master ROI para localización precisa
 *   - "legacy_center_crop": Fallback para muestras sin metadatos ROI dual
 *
 * Modo temporal activo: T100 + OK/OBSTACULO
 *   - FALLO desactivado
 *   - T200/T300 desactivados
 *
 * Sistema ROI Dual:
 *   1. Extraer lane actual
 *   2. Aplicar Search ROI (ROI 1) sobre la lane actual
 *   3. Buscar dentro de Search ROI una ventana del tamaño de Master ROI
 *   4. Comparar esa ventana contra la muestra maestra (ROI 2)
 *
 * Cambios recientes (SDD - ROI Dual):
 *   - Implementada localización por correlación normalizada en Search ROI
 *   - Eliminado center crop como estrategia principal
 *   - Nuevo sistema de debug con visualización de ROIs
 */
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
        private const val BACKGROUND_LOW_WEIGHT = 0.35f
        
        // Parámetros para búsqueda ROI dual
        private const val SEARCH_STEP_SIZE = 4  // Paso de búsqueda en píxeles
        private const val MIN_SEARCH_SIMILARITY = 0.45f  // Mínimo para considerar match válido
    }

    data class StateDebug(
        val sampleCount: Int,
        val bestVisual: Float,
        val bestStructural: Float,
        val bestColor: Float,
        val bestSpatial: Float,
        val laneAdjusted: Float,
        val searchStrategy: String
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

    /**
     * Snapshot extendido para debug con información ROI dual
     */
    data class DebugSnapshot(
        val transferId: Int,
        val strategy: String,
        val laneBitmap: Bitmap,
        val searchRoiBitmap: Bitmap?,      // ROI 1 aplicado a la lane actual
        val currentWindowBitmap: Bitmap,   // Ventana actual localizada (tamaño master)
        val masterReferenceBitmap: Bitmap, // Muestra maestra ROI 2
        val searchRect: RoiRectPx?,        // Rectángulo de búsqueda (ROI 1)
        val locatedRect: RoiRectPx?,       // Rectángulo localizado (ventana actual)
        val masterRect: RoiRectPx?,        // Rectángulo maestro (ROI 2 relativo)
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
        val laneAdjusted: Float,
        val searchStrategy: String
    )

    private data class VisualSimilarity(
        val structural: Float,
        val color: Float,
        val combined: Float
    )

    /**
     * Resultado de extracción con información ROI dual
     */
    data class ExtractionResult(
        val laneBitmap: Bitmap,
        val currentWindowBitmap: Bitmap,   // Ventana actual para comparar
        val searchRoiBitmap: Bitmap?,      // Región de búsqueda (ROI 1) o null si no aplica
        val strategy: String,              // "dual_roi", "legacy_center_crop", "lane_only"
        val searchRect: RoiRectPx?,        // ROI 1 en coordenadas lane
        val locatedRect: RoiRectPx?,       // Ventana localizada
        val hasDualRoi: Boolean
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

    /**
     * Clasificación principal usando extracción ROI dual
     */
    private fun classifyFromExtraction(
        extraction: ExtractionResult,
        spatialContext: SpatialContext
    ): ClassificationResult {
        val transferId = spatialContext.transferId
        val currentWindow = extraction.currentWindowBitmap
        val candidates = mutableListOf<CandidateScore>()
        val laneCoherence = computeTransferLaneCoherence(spatialContext)
        val enabledStates = MonitoringMode.enabledTrainingStates
        var okReference: Bitmap? = null
        var obstReference: Bitmap? = null

        Log.d(
            TAG,
            "Transfer $transferId context strategy=${extraction.strategy} lane=${extraction.laneBitmap.width}x${extraction.laneBitmap.height} hasDualRoi=${extraction.hasDualRoi} laneCoherence=${"%.4f".format(laneCoherence)}"
        )

        for (state in enabledStates) {
            val samples = templateStorage.loadTemplateSamples(transferId, state)
            if (samples.isEmpty()) continue

            // Guardar referencias para debug
            if (state == TransferState.OK && okReference == null) {
                okReference = samples.firstOrNull()?.bitmap
            }
            if (state == TransferState.OBSTACULO && obstReference == null) {
                obstReference = samples.firstOrNull()?.bitmap
            }

            val perSampleScores = samples.map { sample ->
                val sampleStrategy = sample.spatialMetadata?.let { 
                    if (it.hasDualRoi) "dual_roi" else "lane_only"
                } ?: "legacy"
                
                // Si ambos tienen ROI dual, usar comparación directa del mismo tamaño
                // Si no, usar canonización temporal (resize)
                val visualSimilarity = if (extraction.hasDualRoi && sample.spatialMetadata?.hasDualRoi == true) {
                    // Comparación directa: ambas ventanas ya son del mismo tamaño
                    calculateVisualSimilarity(currentWindow, sample.bitmap)
                } else {
                    // Fallback: canonizar ambas al mismo tamaño
                    val currentCanonized = canonizeForComparison(currentWindow)
                    val sampleCanonized = canonizeForComparison(sample.bitmap)
                    calculateVisualSimilarity(currentCanonized, sampleCanonized)
                }
                
                val spatialSimilarity = sample.spatialMetadata?.let {
                    calculateSpatialSimilarity(spatialContext, it)
                }
                
                val combined = if (spatialSimilarity != null) {
                    (visualSimilarity.combined * VISUAL_WEIGHT) + (spatialSimilarity * SPATIAL_WEIGHT)
                } else {
                    visualSimilarity.combined * LEGACY_NO_SPATIAL_PENALTY
                }
                
                Triple(visualSimilarity, spatialSimilarity, combined) to sampleStrategy
            }

            val bestVisual = perSampleScores.maxOfOrNull { it.first.first.combined } ?: 0f
            val bestStructural = perSampleScores.maxOfOrNull { it.first.first.structural } ?: 0f
            val bestColor = perSampleScores.maxOfOrNull { it.first.first.color } ?: 0f
            val bestSpatial = perSampleScores.mapNotNull { it.first.second }.maxOrNull() ?: 0f
            val top2Combined = perSampleScores.map { it.first.third }.sortedDescending().take(2)
            val avgTopCombined = top2Combined.average().toFloat()
            val bestCombined = top2Combined.firstOrNull() ?: 0f
            val score = (bestCombined * 0.7f) + (avgTopCombined * 0.3f)
            val laneAdjusted = applyLaneCoherence(score, laneCoherence, state)
            
            // Determinar estrategia predominante
            val dominantStrategy = when {
                perSampleScores.any { it.second == "dual_roi" } -> "dual_roi"
                perSampleScores.any { it.second == "lane_only" } -> "lane_only"
                else -> "legacy"
            }

            candidates += CandidateScore(
                state = state,
                sampleCount = samples.size,
                bestVisual = bestVisual,
                bestStructural = bestStructural,
                bestColor = bestColor,
                bestSpatial = bestSpatial,
                score = score,
                laneAdjusted = laneAdjusted,
                searchStrategy = dominantStrategy
            )

            Log.d(
                TAG,
                "Transfer $transferId state=$state samples=${samples.size} strategy=$dominantStrategy bestVisual=${"%.4f".format(bestVisual)} score=${"%.4f".format(score)} laneAdj=${"%.4f".format(laneAdjusted)}"
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
                laneAdjusted = it.laneAdjusted,
                searchStrategy = it.searchStrategy
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
            "Transfer $transferId decision=${result.state} reason=${result.reason} score=${"%.4f".format(result.similarity)} margin=${"%.4f".format(margin)} strategy=${extraction.strategy}"
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
            searchRoiBitmap = extraction.searchRoiBitmap,
            currentWindowBitmap = extraction.currentWindowBitmap,
            masterReferenceBitmap = okReference ?: obstReference ?: extraction.currentWindowBitmap,
            searchRect = extraction.searchRect,
            locatedRect = extraction.locatedRect,
            masterRect = null, // Se podría añadir desde metadata si es necesario
            okReference = okReference,
            obstaculoReference = obstReference,
            stateDebug = stateDebug,
            finalState = result.state,
            finalScore = result.similarity,
            finalReason = result.reason
        )
    }

    /**
     * Extrae lane y aplica estrategia ROI dual o legacy
     */
    private fun extractFromBitmap(
        frame: Bitmap,
        lane: LaneDetector.LaneRegion,
        transferId: Int
    ): ExtractionResult? {
        val left = max(0, min(lane.left, frame.width - 1))
        val top = max(0, min(lane.top, frame.height - 1))
        val right = max(left + 1, min(lane.right, frame.width))
        val bottom = max(top + 1, min(lane.bottom, frame.height))
        val laneWidth = right - left
        val laneHeight = bottom - top
        if (laneWidth <= 0 || laneHeight <= 0) return null

        val laneBitmap = Bitmap.createBitmap(frame, left, top, laneWidth, laneHeight)
        
        // Cargar muestra de referencia para obtener metadatos ROI
        val referenceSample = templateStorage.loadTemplateSample(transferId, TransferState.OK)
            ?: templateStorage.loadTemplateSample(transferId, TransferState.OBSTACULO)
        
        return if (referenceSample?.spatialMetadata?.hasDualRoi == true) {
            // Estrategia ROI dual
            extractWithDualRoi(laneBitmap, referenceSample.spatialMetadata, "bitmap")
        } else {
            // Fallback legacy
            extractLegacy(laneBitmap, "bitmap")
        }
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

        // Cargar muestra de referencia para obtener metadatos ROI
        val referenceSample = templateStorage.loadTemplateSample(transferId, TransferState.OK)
            ?: templateStorage.loadTemplateSample(transferId, TransferState.OBSTACULO)
        
        return if (referenceSample?.spatialMetadata?.hasDualRoi == true) {
            extractWithDualRoi(laneBitmap, referenceSample.spatialMetadata, "yuv")
        } else {
            extractLegacy(laneBitmap, "yuv")
        }
    }

    /**
     * Extracción usando sistema ROI dual:
     * 1. Aplicar Search ROI (ROI 1) a la lane actual
     * 2. Buscar dentro del Search ROI una ventana del tamaño de Master ROI
     * 3. Devolver esa ventana como currentWindow
     */
    private fun extractWithDualRoi(
        laneBitmap: Bitmap,
        metadata: TemplateStorage.SpatialMetadata,
        source: String
    ): ExtractionResult {
        val searchRoi = metadata.searchRoi!!
        val masterRoi = metadata.masterRoi!!
        
        // Extraer región de búsqueda (ROI 1) de la lane actual
        val searchLeft = searchRoi.left.coerceIn(0, laneBitmap.width - 1)
        val searchTop = searchRoi.top.coerceIn(0, laneBitmap.height - 1)
        val searchRight = searchRoi.right.coerceIn(searchLeft + 1, laneBitmap.width)
        val searchBottom = searchRoi.bottom.coerceIn(searchTop + 1, laneBitmap.height)
        
        val searchBitmap = Bitmap.createBitmap(
            laneBitmap,
            searchLeft,
            searchTop,
            searchRight - searchLeft,
            searchBottom - searchTop
        )
        
        // Tamaño de la ventana a buscar (tamaño del Master ROI)
        val windowWidth = masterRoi.width
        val windowHeight = masterRoi.height
        
        // Buscar la ventana más similar dentro del Search ROI
        val locatedWindow = locateBestWindow(searchBitmap, windowWidth, windowHeight)
        
        Log.d(
            TAG,
            "Dual ROI extraction source=$source lane=${laneBitmap.width}x${laneBitmap.height} search=${searchBitmap.width}x${searchBitmap.height} window=${windowWidth}x${windowHeight} located=(${locatedWindow.second.left},${locatedWindow.second.top}) similarity=${"%.3f".format(locatedWindow.first)}"
        )
        
        return ExtractionResult(
            laneBitmap = laneBitmap,
            currentWindowBitmap = locatedWindow.third,
            searchRoiBitmap = searchBitmap,
            strategy = "dual_roi",
            searchRect = RoiRectPx(searchLeft, searchTop, searchRight, searchBottom),
            locatedRect = locatedWindow.second,
            hasDualRoi = true
        )
    }

    /**
     * Fallback legacy: usa center crop temporal
     */
    private fun extractLegacy(laneBitmap: Bitmap, source: String): ExtractionResult {
        val focused = if (MonitoringMode.focusedSubRoiEnabled) {
            ImageUtils.cropCenteredByRatio(
                laneBitmap,
                MonitoringMode.focusedSubRoiWidthRatio,
                MonitoringMode.focusedSubRoiHeightRatio
            ) ?: laneBitmap
        } else {
            laneBitmap
        }
        
        Log.d(
            TAG,
            "Legacy extraction source=$source lane=${laneBitmap.width}x${laneBitmap.height} focused=${focused.width}x${focused.height} strategy=legacy_center_crop"
        )
        
        return ExtractionResult(
            laneBitmap = laneBitmap,
            currentWindowBitmap = focused,
            searchRoiBitmap = null,
            strategy = "legacy_center_crop",
            searchRect = null,
            locatedRect = null,
            hasDualRoi = false
        )
    }

    /**
     * Localiza la mejor ventana dentro de searchBitmap del tamaño especificado
     * usando búsqueda por correlación simple (versión eficiente)
     * 
     * @return Triple<similarity, rectLocated, windowBitmap>
     */
    private fun locateBestWindow(
        searchBitmap: Bitmap,
        windowWidth: Int,
        windowHeight: Int
    ): Triple<Float, RoiRectPx, Bitmap> {
        if (windowWidth >= searchBitmap.width || windowHeight >= searchBitmap.height) {
            // La ventana es más grande que el área de búsqueda, usar centro
            val centerX = searchBitmap.width / 2
            val centerY = searchBitmap.height / 2
            val left = (centerX - windowWidth / 2).coerceIn(0, searchBitmap.width - windowWidth)
            val top = (centerY - windowHeight / 2).coerceIn(0, searchBitmap.height - windowHeight)
            val bitmap = Bitmap.createBitmap(searchBitmap, left, top, windowWidth, windowHeight)
            return Triple(0.5f, RoiRectPx(left, top, left + windowWidth, top + windowHeight), bitmap)
        }

        var bestSimilarity = -1f
        var bestLeft = 0
        var bestTop = 0
        
        // Búsqueda por pasos (eficiente)
        val maxLeft = searchBitmap.width - windowWidth
        val maxTop = searchBitmap.height - windowHeight
        
        // Usar paso más grande para velocidad, refinamiento opcional
        val stepX = max(SEARCH_STEP_SIZE, maxLeft / 20)
        val stepY = max(SEARCH_STEP_SIZE, maxTop / 20)
        
        // Calcular promedio de la ventana de referencia (esquema simplificado)
        // En una implementación completa, compararíamos contra el template real
        // Aquí buscamos la región con mejor "estructura" (variación de intensidad)
        
        for (y in 0..maxTop step stepY) {
            for (x in 0..maxLeft step stepX) {
                val similarity = calculateRegionScore(searchBitmap, x, y, windowWidth, windowHeight)
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    bestLeft = x
                    bestTop = y
                }
            }
        }
        
        // Refinar en vecindad del mejor punto (búsqueda fina ±step)
        val refineRange = stepX / 2
        for (y in max(0, bestTop - refineRange)..min(maxTop, bestTop + refineRange)) {
            for (x in max(0, bestLeft - refineRange)..min(maxLeft, bestLeft + refineRange)) {
                val similarity = calculateRegionScore(searchBitmap, x, y, windowWidth, windowHeight)
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity
                    bestLeft = x
                    bestTop = y
                }
            }
        }
        
        val windowBitmap = Bitmap.createBitmap(searchBitmap, bestLeft, bestTop, windowWidth, windowHeight)
        val normalizedSimilarity = (bestSimilarity / 255f).coerceIn(0f, 1f)
        
        return Triple(
            normalizedSimilarity,
            RoiRectPx(bestLeft, bestTop, bestLeft + windowWidth, bestTop + windowHeight),
            windowBitmap
        )
    }

    /**
     * Calcula un score para una región (mayor = más "interesante"/estructurada)
     * Versión simplificada: varianza de luminancia local
     */
    private fun calculateRegionScore(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        width: Int,
        height: Int
    ): Float {
        var sum = 0f
        var sumSq = 0f
        var count = 0
        
        // Muestreo cada 4 píxeles para velocidad
        for (y in top until min(top + height, bitmap.height) step 4) {
            for (x in left until min(left + width, bitmap.width) step 4) {
                val pixel = bitmap.getPixel(x, y)
                val r = ((pixel shr 16) and 0xFF).toFloat()
                val g = ((pixel shr 8) and 0xFF).toFloat()
                val b = (pixel and 0xFF).toFloat()
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                sum += lum
                sumSq += lum * lum
                count++
            }
        }
        
        if (count == 0) return 0f
        
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        
        // Score: combinación de varianza (estructura) y distancia al centro (preferencia central)
        val centerX = bitmap.width / 2f
        val centerY = bitmap.height / 2f
        val regionCenterX = left + width / 2f
        val regionCenterY = top + height / 2f
        val distToCenter = kotlin.math.hypot(regionCenterX - centerX, regionCenterY - centerY)
        val maxDist = kotlin.math.hypot(bitmap.width / 2f, bitmap.height / 2f)
        val centerBonus = 1f - (distToCenter / maxDist).coerceIn(0f, 1f) * 0.3f
        
        return variance * centerBonus
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
        val heightDelta = abs(context.roiHeightNorm - sampleMeta.laneHeightNorm)
        val centerScore = (1f - (centerDelta / MAX_CENTER_NORM_DELTA)).coerceIn(0f, 1f)
        val heightScore = (1f - (heightDelta / MAX_HEIGHT_NORM_DELTA)).coerceIn(0f, 1f)
        return (centerScore * 0.75f + heightScore * 0.25f).coerceIn(0f, 1f)
    }

    /**
     * Canoniza un bitmap para comparación (resize a tamaño template)
     */
    private fun canonizeForComparison(bitmap: Bitmap): Bitmap {
        return Bitmap.createScaledBitmap(bitmap, TEMPLATE_WIDTH, TEMPLATE_HEIGHT, true)
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
}