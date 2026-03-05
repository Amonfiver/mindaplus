package com.mindaplus.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class TemplateStorage(private val context: Context) {
    companion object {
        private const val TAG = "TemplateStorage"
        private const val TEMPLATES_DIR = "templates"
        private const val MANIFEST_FILE = "manifest.json"
        private const val TEMPLATE_VERSION = "0.2"
        private const val TEMPLATE_WIDTH = 128
        private const val TEMPLATE_HEIGHT = 64
    }
    
    data class TemplateManifest(
        val version: String,
        val timestamp: Long,
        val templates: Map<String, Boolean>
    )
    
    private val templatesDir: File by lazy {
        File(context.filesDir, TEMPLATES_DIR).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }
    
    fun saveTemplate(transferId: Int, state: TransferState, bitmap: Bitmap): Boolean {
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
            
            Log.d(TAG, "Template saved: $filename")
            updateManifest()
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving template for T$transferId $state", e)
            return false
        }
    }
    
    fun loadTemplate(transferId: Int, state: TransferState): Bitmap? {
        try {
            if (state == TransferState.UNKNOWN) {
                return null
            }
            
            val filename = getTemplateFilename(transferId, state)
            val file = File(templatesDir, filename)
            
            if (!file.exists()) {
                return null
            }
            
            return BitmapFactory.decodeFile(file.absolutePath)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading template for T$transferId $state", e)
            return null
        }
    }
    
    fun loadAllTemplates(): Map<String, Bitmap> {
        val templates = mutableMapOf<String, Bitmap>()
        
        try {
            val manifest = loadManifest()
            
            for ((filename, exists) in manifest.templates) {
                if (exists) {
                    val file = File(templatesDir, filename)
                    if (file.exists()) {
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        if (bitmap != null) {
                            templates[filename] = bitmap
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading all templates", e)
        }
        
        return templates
    }
    
    fun isTrained(): Boolean {
        try {
            val manifest = loadManifest()
            val requiredTemplates = 9 // 3 transfers × 3 states
            val trainedCount = manifest.templates.values.count { it }
            
            return trainedCount == requiredTemplates
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking training status", e)
            return false
        }
    }
    
    fun getTrainingProgress(): Pair<Int, Int> {
        try {
            val manifest = loadManifest()
            val trainedCount = manifest.templates.values.count { it }
            val totalCount = 9 // 3 transfers × 3 states
            
            return trainedCount to totalCount
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting training progress", e)
            return 0 to 9
        }
    }
    
    fun clearAllTemplates() {
        try {
            templatesDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".png")) {
                    file.delete()
                }
            }
            
            updateManifest()
            Log.d(TAG, "All templates cleared")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing templates", e)
        }
    }
    
    private fun getTemplateFilename(transferId: Int, state: TransferState): String {
        return when (state) {
            TransferState.OK -> "tpl_t${transferId}_ok.png"
            TransferState.OBSTACULO -> "tpl_t${transferId}_obst.png"
            TransferState.FALLO -> "tpl_t${transferId}_fallo.png"
            TransferState.UNKNOWN -> throw IllegalArgumentException("Cannot create template for UNKNOWN state")
        }
    }
    
    private fun updateManifest() {
        try {
            val templates = mutableMapOf<String, Boolean>()
            
            // Check all required template files
            for (transferId in listOf(100, 200, 300)) {
                for (state in listOf(TransferState.OK, TransferState.OBSTACULO, TransferState.FALLO)) {
                    val filename = getTemplateFilename(transferId, state)
                    val file = File(templatesDir, filename)
                    templates[filename] = file.exists()
                }
            }
            
            val manifest = TemplateManifest(
                version = TEMPLATE_VERSION,
                timestamp = System.currentTimeMillis(),
                templates = templates
            )
            
            val manifestFile = File(templatesDir, MANIFEST_FILE)
            manifestFile.writeText(
                JSONObject().apply {
                    put("version", manifest.version)
                    put("timestamp", manifest.timestamp)
                    put("templates", JSONObject(manifest.templates))
                }.toString()
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating manifest", e)
        }
    }
    
    private fun loadManifest(): TemplateManifest {
        try {
            val manifestFile = File(templatesDir, MANIFEST_FILE)
            
            if (!manifestFile.exists()) {
                // Return empty manifest if file doesn't exist
                return TemplateManifest(
                    version = TEMPLATE_VERSION,
                    timestamp = System.currentTimeMillis(),
                    templates = emptyMap()
                )
            }
            
            val jsonString = manifestFile.readText()
            val json = JSONObject(jsonString)
            
            val templates = mutableMapOf<String, Boolean>()
            val templatesJson = json.getJSONObject("templates")
            for (key in templatesJson.keys()) {
                templates[key] = templatesJson.getBoolean(key)
            }
            
            return TemplateManifest(
                version = json.getString("version"),
                timestamp = json.getLong("timestamp"),
                templates = templates
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading manifest", e)
            return TemplateManifest(
                version = TEMPLATE_VERSION,
                timestamp = System.currentTimeMillis(),
                templates = emptyMap()
            )
        }
    }
}