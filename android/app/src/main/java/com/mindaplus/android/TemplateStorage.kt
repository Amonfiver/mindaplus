/**
 * TemplateStorage.kt - Persistencia de muestras maestras y metadatos espaciales ROI dual
 *
 * Propósito: Guardar y cargar templates de referencia para clasificación de estados
 *            de transfers (OK, OBSTACULO) con sistema ROI dual.
 *
 * Alcance: 
 *   - Almacenamiento de imágenes PNG en filesDir/templates/
 *   - Metadatos espaciales ROI dual: Search ROI (ROI 1) + Master ROI (ROI 2)
 *   - Compatibilidad backward con muestras legacy (center crop)
 *
 * Modo temporal activo: T100 + OK/OBSTACULO
 *   - FALLO desactivado
 *   - T200/T300 desactivados
 *
 * Sistema ROI Dual:
 *   - ROI 1 (searchRoi): Zona de búsqueda dentro de la lane donde buscar el transfer
 *   - ROI 2 (masterRoi): Muestra maestra exacta del transfer (tamaño/forma objetivo)
 *   - En runtime: se busca dentro de ROI 1 una ventana del tamaño de ROI 2
 *
 * Cambios recientes (SDD - ROI Dual):
 *   - SpatialMetadata extendido con searchRoi y masterRoi
 *   - Eliminada dependencia de center crop como método principal
 *   - Fallback legacy para muestras sin ROI dual
 */
package com.mindaplus.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.random.Random

class TemplateStorage(private val context: Context) {
    companion object {
        private const val TAG = "TemplateStorage"
        private const val TEMPLATES_DIR = "templates"
        private const val MANIFEST_FILE = "manifest.json"
        private const val ROI_CALIBRATION_FILE = "roi_calibration.json"
        private const val TEMPLATE_VERSION = "0.5" // Actualizado para ROI dual
        private const val SPATIAL_METADATA_VERSION = 2 // v2 = ROI dual
        private const val TEMPLATE_WIDTH = 128
        private const val TEMPLATE_HEIGHT = 64
        private val TRAINING_TRANSFERS = MonitoringMode.enabledTransfers
        private val TRAINING_STATES = MonitoringMode.enabledTrainingStates
    }

    /**
     * ROI específico con coordenadas absolutas
     */
    data class RoiRect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val isValid: Boolean get() = left >= 0 && top >= 0 && right > left && bottom > top
        
        companion object {
            fun fromNormalized(
                leftNorm: Float, topNorm: Float, rightNorm: Float, bottomNorm: Float,
                parentWidth: Int, parentHeight: Int
            ): RoiRect {
                return RoiRect(
                    left = (leftNorm * parentWidth).toInt().coerceIn(0, parentWidth - 1),
                    top = (topNorm * parentHeight).toInt().coerceIn(0, parentHeight - 1),
                    right = (rightNorm * parentWidth).toInt().coerceIn(1, parentWidth),
                    bottom = (bottomNorm * parentHeight).toInt().coerceIn(1, parentHeight)
                )
            }
        }
    }

    /**
     * Metadatos espaciales ROI dual
     * 
     * @param laneRoi Región de la lane detectada en coordenadas del frame original (ROI 1 contenedor)
     * @param searchRoi ROI 1: Zona de búsqueda dentro de la lane (relativo a laneRoi)
     * @param masterRoi ROI 2: Muestra maestra exacta (relativo a laneRoi)
     */
    data class SpatialMetadata(
        val laneRoi: RoiRect,           // Lane completa en coordenadas frame
        val searchRoi: RoiRect?,        // ROI 1: zona de búsqueda (relativo a lane)
        val masterRoi: RoiRect?,        // ROI 2: muestra maestra (relativo a lane)
        val frameWidth: Int,
        val frameHeight: Int
    ) {
        /**
         * Verifica si tiene ROI dual completo (search + master)
         */
        val hasDualRoi: Boolean
            get() = searchRoi?.isValid == true && masterRoi?.isValid == true
        
        /**
         * Fallback legacy: solo lane ROI sin ROIs específicas
         */
        val isLegacy: Boolean
            get() = !hasDualRoi
        
        /**
         * Centro Y normalizado de la lane (para compatibilidad spatial antiguo)
         */
        val centerYNorm: Float
            get() = if (frameHeight > 0) (laneRoi.top + laneRoi.bottom) / 2f / frameHeight else 0f
        
        /**
         * Altura de lane normalizada
         */
        val laneHeightNorm: Float
            get() = if (frameHeight > 0) laneRoi.height.toFloat() / frameHeight else 0f
    }

    data class TemplateSample(
        val fileName: String,
        val bitmap: Bitmap,
        val spatialMetadata: SpatialMetadata?
    ) {
        /**
         * Estrategia de comparación recomendada para esta muestra
         */
        val comparisonStrategy: String
            get() = when {
                spatialMetadata?.hasDualRoi == true -> "dual_roi"
                spatialMetadata != null -> "lane_only"
                else -> "legacy"
            }
    }

    data class ManualRoi(
        val leftNorm: Float,
        val topNorm: Float,
        val rightNorm: Float,
        val bottomNorm: Float,
        val updatedAt: Long
    ) {
        fun isValid(): Boolean {
            return leftNorm in 0f..1f &&
                topNorm in 0f..1f &&
                rightNorm in 0f..1f &&
                bottomNorm in 0f..1f &&
                rightNorm > leftNorm &&
                bottomNorm > topNorm
        }
    }
    
    data class TemplateManifest(
        val version: String,
        val timestamp: Long,
        val baseCoverage: Map<String, Boolean>,
        val sampleCounts: Map<String, Int>,
        val totalSamples: Int
    )

    data class TrainingStats(
        val baseCovered: Int,
        val baseTotal: Int,
        val totalSamples: Int,
        val sampleCounts: Map<String, Int>
    )
    
    private val templatesDir: File by lazy {
        File(context.filesDir, TEMPLATES_DIR).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }
    
    /**
     * Guarda template con metadatos ROI dual (nuevo flujo)
     */
    fun saveTemplate(
        transferId: Int,
        state: TransferState,
        bitmap: Bitmap,
        spatialMetadata: SpatialMetadata?
    ): Boolean {
        try {
            if (state == TransferState.UNKNOWN) {
                Log.w(TAG, "Cannot save template for UNKNOWN state")
                return false
            }
            
            val filename = getTemplateFilename(transferId, state)
            val file = File(templatesDir, filename)
            
            // Guardar bitmap tal cual (sin resize forzado, el resize se hace según estrategia)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            saveSpatialMetadata(file, transferId, state, spatialMetadata)
            
            val roiInfo = spatialMetadata?.let { 
                "dualRoi=${it.hasDualRoi} lane=${it.laneRoi.width}x${it.laneRoi.height}" 
            } ?: "no_metadata"
            Log.d(TAG, "Template saved: $filename ($roiInfo)")
            updateManifest()
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving template for T$transferId $state", e)
            return false
        }
    }
    
    /**
     * Carga una muestra específica con metadatos completos
     */
    fun loadTemplateSample(transferId: Int, state: TransferState): TemplateSample? {
        return loadTemplateSamples(transferId, state).firstOrNull()
    }

    fun loadTemplateSamples(transferId: Int, state: TransferState): List<TemplateSample> {
        try {
            if (state == TransferState.UNKNOWN) {
                return emptyList()
            }

            val files = listTemplateFilesForClass(transferId, state)
            val samples = files.mapNotNull { file ->
                val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@mapNotNull null
                val metadata = loadSpatialMetadata(file)
                TemplateSample(file.name, bitmap, metadata)
            }

            val withDualRoi = samples.count { it.spatialMetadata?.hasDualRoi == true }
            val withLaneOnly = samples.count { it.spatialMetadata?.isLegacy == false && it.spatialMetadata?.hasDualRoi == false }
            val legacy = samples.count { it.spatialMetadata == null || it.spatialMetadata?.isLegacy == true }
            
            Log.d(
                TAG,
                "Loaded ${samples.size} templates for T$transferId ${state.displayName} (dualRoi=$withDualRoi, laneOnly=$withLaneOnly, legacy=$legacy)"
            )
            return samples
        } catch (e: Exception) {
            Log.e(TAG, "Error loading template for T$transferId $state", e)
            return emptyList()
        }
    }
    
    fun loadAllTemplates(): Map<String, Bitmap> {
        val templates = mutableMapOf<String, Bitmap>()
        
        try {
            templatesDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".png", ignoreCase = true) }
                ?.forEach { file ->
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        templates[file.name] = bitmap
                    }
                }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading all templates", e)
        }
        
        return templates
    }
    
    fun isTrained(): Boolean {
        val stats = getTrainingStats()
        return stats.baseCovered == stats.baseTotal
    }
    
    fun getTrainingProgress(): Pair<Int, Int> {
        val stats = getTrainingStats()
        return stats.baseCovered to stats.baseTotal
    }

    fun getTrainingStats(): TrainingStats {
        return try {
            val sampleCounts = mutableMapOf<String, Int>()
            var covered = 0
            var totalSamples = 0

            for (transferId in TRAINING_TRANSFERS) {
                for (state in TRAINING_STATES) {
                    val key = getBaseKey(transferId, state)
                    val count = listTemplateFilesForClass(transferId, state).size
                    sampleCounts[key] = count
                    totalSamples += count
                    if (count > 0) covered++
                }
            }

            TrainingStats(
                baseCovered = covered,
                baseTotal = TRAINING_TRANSFERS.size * TRAINING_STATES.size,
                totalSamples = totalSamples,
                sampleCounts = sampleCounts
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting training stats", e)
            TrainingStats(
                baseCovered = 0,
                baseTotal = TRAINING_TRANSFERS.size * TRAINING_STATES.size,
                totalSamples = 0,
                sampleCounts = emptyMap()
            )
        }
    }
    
    fun clearAllTemplates() {
        try {
            templatesDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".png")) {
                    file.delete()
                    getMetadataFile(file).delete()
                }
            }
            
            updateManifest()
            Log.d(TAG, "All templates cleared")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing templates", e)
        }
    }
    
    private fun getTemplateFilename(transferId: Int, state: TransferState): String {
        val suffix = getStateSuffix(state)
        val timestamp = System.currentTimeMillis()
        val randomId = Random.nextInt(1000, 9999)
        return "tpl_t${transferId}_${suffix}_${timestamp}_$randomId.png"
    }

    private fun listTemplateFilesForClass(transferId: Int, state: TransferState): List<File> {
        if (state == TransferState.UNKNOWN) return emptyList()
        val suffix = getStateSuffix(state)
        val prefix = "tpl_t${transferId}_${suffix}_"
        val legacyName = "tpl_t${transferId}_${suffix}.png"

        return templatesDir.listFiles()
            ?.filter {
                it.isFile && (
                    (it.name.startsWith(prefix) && it.name.endsWith(".png", ignoreCase = true)) ||
                        it.name.equals(legacyName, ignoreCase = true)
                    )
            }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    private fun getBaseKey(transferId: Int, state: TransferState): String {
        return "t${transferId}_${getStateSuffix(state)}"
    }

    private fun getMetadataFile(templateFile: File): File {
        return File(templatesDir, "${templateFile.name}.meta.json")
    }

    /**
     * Guarda metadatos espaciales ROI dual en formato JSON
     */
    private fun saveSpatialMetadata(
        templateFile: File,
        transferId: Int,
        state: TransferState,
        spatialMetadata: SpatialMetadata?
    ) {
        try {
            val metadataFile = getMetadataFile(templateFile)
            if (spatialMetadata == null) {
                if (metadataFile.exists()) {
                    metadataFile.delete()
                }
                return
            }

            val metadataJson = JSONObject().apply {
                put("version", SPATIAL_METADATA_VERSION)
                put("templateFile", templateFile.name)
                put("transferId", transferId)
                put("state", state.name)
                
                // Lane ROI (absoluto en frame)
                put("laneLeft", spatialMetadata.laneRoi.left)
                put("laneTop", spatialMetadata.laneRoi.top)
                put("laneRight", spatialMetadata.laneRoi.right)
                put("laneBottom", spatialMetadata.laneRoi.bottom)
                
                // Search ROI (ROI 1) - relativo a lane, -1 si no existe
                put("searchLeft", spatialMetadata.searchRoi?.left ?: -1)
                put("searchTop", spatialMetadata.searchRoi?.top ?: -1)
                put("searchRight", spatialMetadata.searchRoi?.right ?: -1)
                put("searchBottom", spatialMetadata.searchRoi?.bottom ?: -1)
                
                // Master ROI (ROI 2) - relativo a lane, -1 si no existe
                put("masterLeft", spatialMetadata.masterRoi?.left ?: -1)
                put("masterTop", spatialMetadata.masterRoi?.top ?: -1)
                put("masterRight", spatialMetadata.masterRoi?.right ?: -1)
                put("masterBottom", spatialMetadata.masterRoi?.bottom ?: -1)
                
                // Frame dimensions
                put("frameWidth", spatialMetadata.frameWidth)
                put("frameHeight", spatialMetadata.frameHeight)
            }
            metadataFile.writeText(metadataJson.toString())
            Log.d(TAG, "Spatial metadata saved for ${templateFile.name}: dualRoi=${spatialMetadata.hasDualRoi}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed saving spatial metadata for ${templateFile.name}", e)
        }
    }

    fun saveManualRoi(transferId: Int, roi: ManualRoi): Boolean {
        if (!roi.isValid()) {
            Log.w(TAG, "Rejected invalid manual ROI for T$transferId: $roi")
            return false
        }
        return try {
            val all = loadAllManualRois().toMutableMap()
            all[transferId] = roi.copy(updatedAt = System.currentTimeMillis())
            writeManualRoiFile(all)
            Log.d(TAG, "Saved manual ROI for T$transferId: $roi")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving manual ROI for T$transferId", e)
            false
        }
    }

    fun loadManualRoi(transferId: Int): ManualRoi? {
        return loadAllManualRois()[transferId]
    }

    fun loadAllManualRois(): Map<Int, ManualRoi> {
        return try {
            val file = File(templatesDir, ROI_CALIBRATION_FILE)
            if (!file.exists()) return emptyMap()

            val json = JSONObject(file.readText())
            val data = json.optJSONObject("transfers") ?: return emptyMap()
            val out = mutableMapOf<Int, ManualRoi>()
            data.keys().forEach { key ->
                val transferId = key.removePrefix("t").toIntOrNull() ?: return@forEach
                val roiJson = data.optJSONObject(key) ?: return@forEach
                val roi = ManualRoi(
                    leftNorm = roiJson.optDouble("leftNorm", -1.0).toFloat(),
                    topNorm = roiJson.optDouble("topNorm", -1.0).toFloat(),
                    rightNorm = roiJson.optDouble("rightNorm", -1.0).toFloat(),
                    bottomNorm = roiJson.optDouble("bottomNorm", -1.0).toFloat(),
                    updatedAt = roiJson.optLong("updatedAt", 0L)
                )
                if (roi.isValid()) {
                    out[transferId] = roi
                }
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "Error loading manual ROIs", e)
            emptyMap()
        }
    }

    /**
     * Carga metadatos espaciales desde JSON con soporte para ROI dual
     */
    private fun loadSpatialMetadata(templateFile: File): SpatialMetadata? {
        return try {
            val metadataFile = getMetadataFile(templateFile)
            if (!metadataFile.exists()) {
                return null
            }

            val json = JSONObject(metadataFile.readText())
            val version = json.optInt("version", 1)
            
            // Lane ROI siempre debe existir
            val laneLeft = json.optInt("laneLeft", -1)
            val laneTop = json.optInt("laneTop", -1)
            val laneRight = json.optInt("laneRight", -1)
            val laneBottom = json.optInt("laneBottom", -1)
            
            // Fallback v1: usar campos legacy
            val effectiveLaneLeft = if (laneLeft < 0) json.optInt("left", -1) else laneLeft
            val effectiveLaneTop = if (laneTop < 0) json.optInt("top", -1) else laneTop
            val effectiveLaneRight = if (laneRight < 0) json.optInt("right", -1) else laneRight
            val effectiveLaneBottom = if (laneBottom < 0) json.optInt("bottom", -1) else laneBottom
            
            if (effectiveLaneLeft < 0 || effectiveLaneTop < 0 || 
                effectiveLaneRight <= effectiveLaneLeft || effectiveLaneBottom <= effectiveLaneTop) {
                return null
            }
            
            val laneRoi = RoiRect(effectiveLaneLeft, effectiveLaneTop, effectiveLaneRight, effectiveLaneBottom)
            
            // Search ROI (ROI 1)
            val searchLeft = json.optInt("searchLeft", -1)
            val searchRoi = if (searchLeft >= 0) {
                RoiRect(
                    searchLeft,
                    json.optInt("searchTop", -1),
                    json.optInt("searchRight", -1),
                    json.optInt("searchBottom", -1)
                )
            } else null
            
            // Master ROI (ROI 2)
            val masterLeft = json.optInt("masterLeft", -1)
            val masterRoi = if (masterLeft >= 0) {
                RoiRect(
                    masterLeft,
                    json.optInt("masterTop", -1),
                    json.optInt("masterRight", -1),
                    json.optInt("masterBottom", -1)
                )
            } else null
            
            val frameWidth = json.optInt("frameWidth", -1)
            val frameHeight = json.optInt("frameHeight", -1)
            
            if (frameWidth <= 0 || frameHeight <= 0) return null
            
            SpatialMetadata(
                laneRoi = laneRoi,
                searchRoi = searchRoi?.takeIf { it.isValid },
                masterRoi = masterRoi?.takeIf { it.isValid },
                frameWidth = frameWidth,
                frameHeight = frameHeight
            ).also {
                Log.d(TAG, "Loaded metadata for ${templateFile.name}: version=$version, dualRoi=${it.hasDualRoi}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed loading spatial metadata for ${templateFile.name}", e)
            null
        }
    }

    private fun getStateSuffix(state: TransferState): String {
        return when (state) {
            TransferState.OK -> "ok"
            TransferState.OBSTACULO -> "obst"
            TransferState.FALLO -> "fallo"
            TransferState.UNKNOWN -> throw IllegalArgumentException("UNKNOWN has no template suffix")
        }
    }

    private fun writeManualRoiFile(rois: Map<Int, ManualRoi>) {
        val file = File(templatesDir, ROI_CALIBRATION_FILE)
        val payload = JSONObject().apply {
            put("version", 1)
            put("updatedAt", System.currentTimeMillis())
            put(
                "transfers",
                JSONObject().apply {
                    rois.forEach { (transferId, roi) ->
                        put(
                            "t$transferId",
                            JSONObject().apply {
                                put("leftNorm", roi.leftNorm.toDouble())
                                put("topNorm", roi.topNorm.toDouble())
                                put("rightNorm", roi.rightNorm.toDouble())
                                put("bottomNorm", roi.bottomNorm.toDouble())
                                put("updatedAt", roi.updatedAt)
                            }
                        )
                    }
                }
            )
        }
        file.writeText(payload.toString())
    }

    private fun updateManifest() {
        try {
            val stats = getTrainingStats()
            val baseCoverage = mutableMapOf<String, Boolean>()
            for (transferId in TRAINING_TRANSFERS) {
                for (state in TRAINING_STATES) {
                    val key = getBaseKey(transferId, state)
                    val count = stats.sampleCounts[key] ?: 0
                    baseCoverage[key] = count > 0
                }
            }

            val manifest = TemplateManifest(
                version = TEMPLATE_VERSION,
                timestamp = System.currentTimeMillis(),
                baseCoverage = baseCoverage,
                sampleCounts = stats.sampleCounts,
                totalSamples = stats.totalSamples
            )
            
            val manifestFile = File(templatesDir, MANIFEST_FILE)
            manifestFile.writeText(
                JSONObject().apply {
                    put("version", manifest.version)
                    put("timestamp", manifest.timestamp)
                    put("spatialMetadataVersion", SPATIAL_METADATA_VERSION)
                    put("dualRoiEnabled", true)
                    put("baseCoverage", JSONObject(manifest.baseCoverage))
                    put("sampleCounts", JSONObject(manifest.sampleCounts))
                    put("totalSamples", manifest.totalSamples)
                }.toString()
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating manifest", e)
        }
    }
}