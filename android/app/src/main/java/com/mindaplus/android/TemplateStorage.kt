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
        private const val TEMPLATE_VERSION = "0.4"
        private const val SPATIAL_METADATA_VERSION = 1
        private const val TEMPLATE_WIDTH = 128
        private const val TEMPLATE_HEIGHT = 64
        private val TRAINING_TRANSFERS = MonitoringMode.enabledTransfers
        private val TRAINING_STATES = MonitoringMode.enabledTrainingStates
    }

    data class SpatialMetadata(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val frameWidth: Int,
        val frameHeight: Int
    ) {
        val roiHeight: Int
            get() = (bottom - top).coerceAtLeast(1)

        val centerY: Float
            get() = (top + bottom) / 2f

        val centerYNorm: Float
            get() = if (frameHeight > 0) centerY / frameHeight.toFloat() else 0f

        val roiHeightNorm: Float
            get() = if (frameHeight > 0) roiHeight / frameHeight.toFloat() else 0f
    }

    data class TemplateSample(
        val fileName: String,
        val bitmap: Bitmap,
        val spatialMetadata: SpatialMetadata?
    )
    
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
    
    fun saveTemplate(transferId: Int, state: TransferState, bitmap: Bitmap): Boolean {
        return saveTemplate(transferId, state, bitmap, null)
    }

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
            
            // Resize bitmap to fixed template size
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, TEMPLATE_WIDTH, TEMPLATE_HEIGHT, true)
            
            FileOutputStream(file).use { out ->
                resizedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            saveSpatialMetadata(file, transferId, state, spatialMetadata)
            
            Log.d(TAG, "Template saved: $filename")
            updateManifest()
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving template for T$transferId $state", e)
            return false
        }
    }
    
    fun loadTemplate(transferId: Int, state: TransferState): Bitmap? {
        return loadTemplates(transferId, state).firstOrNull()
    }

    fun loadTemplates(transferId: Int, state: TransferState): List<Bitmap> {
        return loadTemplateSamples(transferId, state).map { it.bitmap }
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

            val withSpatial = samples.count { it.spatialMetadata != null }
            Log.d(
                TAG,
                "Loaded ${samples.size} templates for T$transferId ${state.displayName} (spatialMeta=$withSpatial)"
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
                put("left", spatialMetadata.left)
                put("top", spatialMetadata.top)
                put("right", spatialMetadata.right)
                put("bottom", spatialMetadata.bottom)
                put("frameWidth", spatialMetadata.frameWidth)
                put("frameHeight", spatialMetadata.frameHeight)
                put("centerY", spatialMetadata.centerY.toDouble())
                put("centerYNorm", spatialMetadata.centerYNorm.toDouble())
                put("roiHeight", spatialMetadata.roiHeight)
                put("roiHeightNorm", spatialMetadata.roiHeightNorm.toDouble())
            }
            metadataFile.writeText(metadataJson.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed saving spatial metadata for ${templateFile.name}", e)
        }
    }

    private fun loadSpatialMetadata(templateFile: File): SpatialMetadata? {
        return try {
            val metadataFile = getMetadataFile(templateFile)
            if (!metadataFile.exists()) {
                return null
            }

            val json = JSONObject(metadataFile.readText())
            SpatialMetadata(
                left = json.optInt("left", -1),
                top = json.optInt("top", -1),
                right = json.optInt("right", -1),
                bottom = json.optInt("bottom", -1),
                frameWidth = json.optInt("frameWidth", -1),
                frameHeight = json.optInt("frameHeight", -1)
            ).takeIf {
                it.left >= 0 &&
                    it.top >= 0 &&
                    it.right > it.left &&
                    it.bottom > it.top &&
                    it.frameWidth > 0 &&
                    it.frameHeight > 0
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
                    put("spatialMetadataEnabled", true)
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
