package com.mindaplus.android

import android.graphics.Bitmap
import android.media.Image
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.mindaplus.android.ui.theme.MindaplusTheme
import kotlinx.coroutines.launch

class TrainingActivity : ComponentActivity() {
    private lateinit var cameraManager: CameraManager
    private lateinit var transferMonitor: TransferMonitor
    private lateinit var templateStorage: TemplateStorage
    private lateinit var laneDetector: LaneDetector
    
    private var previewView by mutableStateOf<PreviewView?>(null)
    private var selectedTransfer by mutableStateOf(MonitoringMode.enabledTransfers.firstOrNull() ?: 100)
    private var selectedState by mutableStateOf(MonitoringMode.enabledTrainingStates.firstOrNull() ?: TransferState.OK)
    private var trainingProgress by mutableStateOf(0 to (MonitoringMode.enabledTransfers.size * MonitoringMode.enabledTrainingStates.size).coerceAtLeast(1))
    private var totalSamples by mutableStateOf(0)
    private var isCapturing by mutableStateOf(false)
    private var lastCapturedBitmap by mutableStateOf<Bitmap?>(null)
    private var capturedTemplatePreview by mutableStateOf<Bitmap?>(null)
    private var detectedLaneRegion by mutableStateOf<LaneDetector.LaneRegion?>(null)
    private var lastCapturedTransfer by mutableStateOf<Int?>(null)
    private var lastCapturedState by mutableStateOf<TransferState?>(null)
    private var pendingTemplateBitmap by mutableStateOf<Bitmap?>(null)
    private var pendingFrameWidth by mutableStateOf<Int?>(null)
    private var pendingFrameHeight by mutableStateOf<Int?>(null)
    
    companion object {
        private const val TAG = "TrainingActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Force landscape orientation
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        
        // Initialize components
        cameraManager = CameraManager(this)
        transferMonitor = TransferMonitor(this)
        templateStorage = TemplateStorage(this)
        laneDetector = LaneDetector()
        
        setContent {
            MindaplusTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TrainingScreen()
                }
            }
        }
        
        // Initialize camera
        initializeCamera()
        
        // Update training progress
        updateTrainingProgress()
    }
    
    override fun onResume() {
        super.onResume()
        updateTrainingProgress()
    }
    
    private fun initializeCamera() {
        val previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        this.previewView = previewView
        
        cameraManager.startCamera(
            lifecycleOwner = this,
            previewView = previewView,
            onFrameAnalyzed = { bitmap ->
                // Store the latest bitmap for template capture
                lastCapturedBitmap = bitmap
            }
        )
    }
    
    private fun updateTrainingProgress() {
        val stats = templateStorage.getTrainingStats()
        trainingProgress = stats.baseCovered to stats.baseTotal
        totalSamples = stats.totalSamples
    }
    
    @Composable
    @OptIn(ExperimentalMaterial3Api::class)
    private fun TrainingScreen() {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Left panel - Camera Preview
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    previewView?.let { preview ->
                        AndroidView(
                            factory = { preview },
                            modifier = Modifier.fillMaxSize()
                        )
                    } ?: run {
                        Text("Camera Preview")
                    }
                }
            }
            
            // Right panel - Training Controls
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Training Progress
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Training Progress",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Progress:")
                            Text(
                                text = "${trainingProgress.first}/${trainingProgress.second}",
                                fontWeight = FontWeight.Bold,
                                color = if (trainingProgress.first == trainingProgress.second) Color.Green else Color.Red
                            )
                        }
                        
                        LinearProgressIndicator(
                            progress = trainingProgress.first.toFloat() / trainingProgress.second.coerceAtLeast(1).toFloat(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                
                // Transfer Selection
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Transfer Selection",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )

                        if (MonitoringMode.singleLaneTestMode) {
                            Text(
                                text = "Modo temporal: solo T100 habilitado",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Gray
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (transferId in MonitoringMode.enabledTransfers) {
                                Button(
                                    onClick = { selectedTransfer = transferId },
                                    modifier = Modifier.weight(1f),
                                    colors = if (selectedTransfer == transferId) {
                                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                    } else {
                                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                    }
                                ) {
                                    Text("T$transferId")
                                }
                            }
                        }
                    }
                }
                
                // State Selection
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Estado Manual de la Plantilla",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Selecciona explícitamente el estado que se guardará para esta captura:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                        if (MonitoringMode.singleLaneTestMode) {
                            Text(
                                text = "Modo temporal: FALLO desactivado",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (state in MonitoringMode.enabledTrainingStates) {
                                FilterChip(
                                    selected = selectedState == state,
                                    onClick = { selectedState = state },
                                    label = { Text(state.displayName) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Muestras totales:")
                            Text(
                                text = "$totalSamples",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                
                // Current Selection
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Current Selection",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Text(
                            text = "Transfer: T$selectedTransfer",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        
                        Text(
                            text = "State: ${selectedState.displayName}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = when (selectedState) {
                                TransferState.OK -> Color.Green
                                TransferState.OBSTACULO -> Color.Red
                                TransferState.FALLO -> Color(0xFFFFA500)
                                TransferState.UNKNOWN -> Color.Gray
                            }
                        )
                    }
                }
                
                // Template Preview (shown after capture)
                if (capturedTemplatePreview != null && detectedLaneRegion != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Template Captured",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.Green
                            )
                            
                            Text(
                                text = "Transfer: T${lastCapturedTransfer ?: selectedTransfer}, State: ${lastCapturedState?.displayName ?: selectedState.displayName}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            
                            Text(
                                text = "Detected region: ${detectedLaneRegion?.left ?: 0},${detectedLaneRegion?.top ?: 0} to ${detectedLaneRegion?.right ?: 0},${detectedLaneRegion?.bottom ?: 0}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                            
                            capturedTemplatePreview?.let { bitmap ->
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Template Preview",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(120.dp)
                                )
                            }
                        }
                    }
                }
                
                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { captureTemplate() },
                        modifier = Modifier.weight(1f),
                        enabled = !isCapturing && selectedState != TransferState.UNKNOWN
                    ) {
                        Text("Capturar plantilla")
                    }
                    
                    Button(
                        onClick = { saveTemplate() },
                        modifier = Modifier.weight(1f),
                        enabled = capturedTemplatePreview != null && 
                                 lastCapturedTransfer == selectedTransfer && 
                                 lastCapturedState == selectedState
                    ) {
                        Text("Guardar")
                    }
                }
                
                Button(
                    onClick = { cancelCapture() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancelar")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { recalibrateTrainingSession() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Recalibrar vías")
                    }

                    OutlinedButton(
                        onClick = { finish() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Salir")
                    }
                }
                
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
    
    @Composable
    private fun TransferButton(transferId: Int, isSelected: Boolean, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = if (isSelected) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            } else {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            }
        ) {
            Text("T$transferId")
        }
    }
    
    @Composable
    private fun StateButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = if (isSelected) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            } else {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            }
        ) {
            Text(text)
        }
    }
    
    private fun captureTemplate() {
        if (isCapturing) return
        if (!MonitoringMode.isTransferEnabled(selectedTransfer) || !MonitoringMode.isStateEnabled(selectedState)) {
            showMessage("Modo temporal: selección no habilitada")
            return
        }
        
        isCapturing = true
        Log.d(TAG, "Template capture started for T$selectedTransfer ${selectedState.displayName}")
        
        lifecycleScope.launch {
            try {
                val bitmap = lastCapturedBitmap
                if (bitmap == null) {
                    Log.w(TAG, "No bitmap available for template capture")
                    showMessage("No hay imagen disponible para captura")
                    isCapturing = false
                    return@launch
                }
                
                Log.d(TAG, "Processing bitmap for template capture: ${bitmap.width}x${bitmap.height}")
                
                // Step 1: Detect lanes to find the correct region for this transfer
                val laneDetection = laneDetector.detectLanes(bitmap)
                if (laneDetection.requiresCalibration || laneDetection.lanes == null) {
                    Log.e(TAG, "Lane detection failed - cannot capture template")
                    showMessage("Error: No se pudieron detectar las vías")
                    isCapturing = false
                    return@launch
                }
                
                val lanes = laneDetection.lanes
                Log.d(TAG, "Successfully detected ${lanes.size} lanes")
                
                // Step 2: Select the appropriate lane based on transfer selection
                val targetLaneIndex = when (selectedTransfer) {
                    100 -> 0 // Upper lane (T100)
                    200 -> 1 // Middle lane (T200)
                    300 -> 2 // Lower lane (T300)
                    else -> {
                        Log.e(TAG, "Invalid transfer selection: $selectedTransfer")
                        showMessage("Error: Transfer inválido")
                        isCapturing = false
                        return@launch
                    }
                }
                
                if (targetLaneIndex >= lanes.size) {
                    Log.e(TAG, "Target lane index $targetLaneIndex out of bounds for ${lanes.size} lanes")
                    showMessage("Error: Vía no detectada")
                    isCapturing = false
                    return@launch
                }
                
                val targetLane = lanes[targetLaneIndex]
                Log.d(TAG, "Selected lane $targetLaneIndex for T$selectedTransfer: $targetLane")
                
                // Step 3: Crop bitmap to the detected lane region
                val laneBitmap = ImageUtils.cropBitmap(bitmap, targetLane.left, targetLane.top, targetLane.right, targetLane.bottom)
                if (laneBitmap == null) {
                    Log.e(TAG, "Failed to crop bitmap to lane region")
                    showMessage("Error al recortar la región")
                    isCapturing = false
                    return@launch
                }

                val focusedBitmap = if (MonitoringMode.focusedSubRoiEnabled) {
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
                    "Template capture ROI strategy=${if (MonitoringMode.focusedSubRoiEnabled) "center_crop" else "full_lane"} laneRoi=${laneBitmap.width}x${laneBitmap.height} subRoi=${focusedBitmap.width}x${focusedBitmap.height} ratios=${MonitoringMode.focusedSubRoiWidthRatio}x${MonitoringMode.focusedSubRoiHeightRatio}"
                )
                
                // Step 4: Store the cropped template for preview (NO guardar todavía)
                // Update UI with preview and region info
                capturedTemplatePreview = focusedBitmap
                detectedLaneRegion = targetLane
                lastCapturedTransfer = selectedTransfer
                lastCapturedState = selectedState
                pendingTemplateBitmap = focusedBitmap // Guardar para cuando se pulse "Guardar"
                pendingFrameWidth = bitmap.width
                pendingFrameHeight = bitmap.height
                
                showMessage("Template capturado exitosamente - Región detectada: ${targetLane.left},${targetLane.top} a ${targetLane.right},${targetLane.bottom}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing template", e)
                showMessage("Error al capturar template: ${e.message}")
            } finally {
                isCapturing = false
            }
        }
    }
    
    private fun cropToRegion(bitmap: Bitmap, region: LaneDetector.LaneRegion): Bitmap? {
        return try {
            // Ensure region is within bitmap bounds
            val left = region.left.coerceIn(0, bitmap.width - 1)
            val top = region.top.coerceIn(0, bitmap.height - 1)
            val right = region.right.coerceIn(left + 1, bitmap.width)
            val bottom = region.bottom.coerceIn(top + 1, bitmap.height)
            
            val width = right - left
            val height = bottom - top
            
            if (width <= 0 || height <= 0) {
                Log.e(TAG, "Invalid crop dimensions: ${width}x${height}")
                return null
            }
            
            Bitmap.createBitmap(bitmap, left, top, width, height)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error cropping bitmap to region", e)
            null
        }
    }
    
    private fun cancelCapture() {
        Log.d(TAG, "Canceling current capture and clearing preview")
        clearPendingCaptureData()
        showMessage("Captura cancelada")
    }
    
    private fun saveTemplate() {
        Log.d(TAG, "Saving training template and staying in TrainingActivity")
        
        lifecycleScope.launch {
            try {
                // Verificar que tenemos un template pendiente para guardar
                val templateBitmap = pendingTemplateBitmap
                val transfer = lastCapturedTransfer
                val state = lastCapturedState
                val laneRegion = detectedLaneRegion
                val frameWidth = pendingFrameWidth
                val frameHeight = pendingFrameHeight
                
                if (templateBitmap == null || transfer == null || state == null) {
                    Log.e(TAG, "No pending template to save")
                    showMessage("Error: No hay template pendiente para guardar")
                    return@launch
                }
                
                // Guardar el template en TemplateStorage
                val success = transferMonitor.addLabeledSample(
                    transferId = transfer,
                    state = state,
                    bitmap = templateBitmap,
                    laneRegion = laneRegion,
                    frameWidth = frameWidth ?: templateBitmap.width,
                    frameHeight = frameHeight ?: templateBitmap.height
                )
                if (success) {
                    val centerY = laneRegion?.let { (it.top + it.bottom) / 2f }
                    Log.d(
                        TAG,
                        "Template saved successfully for T$transfer ${state.displayName} lane=${laneRegion ?: "legacy/no-roi"} centerY=${centerY?.let { "%.1f".format(it) } ?: "n/a"} frame=${frameWidth ?: templateBitmap.width}x${frameHeight ?: templateBitmap.height}"
                    )
                    
                    // Actualizar progreso
                    updateTrainingProgress()
                    
                    // Verificar si el entrenamiento está completo
                    val stats = templateStorage.getTrainingStats()
                    if (stats.baseCovered == stats.baseTotal) {
                        Log.d(TAG, "Training base complete: ${stats.baseCovered}/${stats.baseTotal} with ${stats.totalSamples} samples")
                        showMessage("Template guardado. Base completa ${stats.baseCovered}/${stats.baseTotal}. Total muestras: ${stats.totalSamples}")
                    } else {
                        Log.d(TAG, "Training saved: ${stats.baseCovered}/${stats.baseTotal}, samples=${stats.totalSamples}")
                        showMessage("Template guardado: base ${stats.baseCovered}/${stats.baseTotal}, muestras ${stats.totalSamples}")
                    }
                    
                    clearPendingCaptureData()
                } else {
                    Log.e(TAG, "Failed to save template to storage")
                    showMessage("Error al guardar el template")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error saving training data", e)
                showMessage("Error al guardar: ${e.message}")
            }
        }
    }

    private fun recalibrateTrainingSession() {
        Log.d(TAG, "Recalibrating training session: clearing lane cache and capture state")
        laneDetector.clearCache()
        transferMonitor.recalibrateLanes()
        clearPendingCaptureData()
        showMessage("Vías recalibradas. Sesión lista para nueva captura.")
    }

    private fun clearPendingCaptureData() {
        capturedTemplatePreview = null
        detectedLaneRegion = null
        lastCapturedTransfer = null
        lastCapturedState = null
        pendingTemplateBitmap = null
        pendingFrameWidth = null
        pendingFrameHeight = null
    }
    
    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            // Convert YUV_420_888 image to Bitmap
            val width = image.width
            val height = image.height
            
            val yBuffer = image.planes[0].buffer // Y
            val uBuffer = image.planes[1].buffer // U
            val vBuffer = image.planes[2].buffer // V
            
            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            
            val nv21 = ByteArray(ySize + uSize + vSize)
            
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)
            
            // Convert NV21 to RGB bitmap
            val rgba = IntArray(width * height)
            nv21ToRgba(nv21, width, height, rgba)
            
            Bitmap.createBitmap(rgba, width, height, Bitmap.Config.ARGB_8888)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error converting image to bitmap", e)
            null
        }
    }
    
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
    
    private fun showMessage(message: String) {
        lifecycleScope.launch {
            android.widget.Toast.makeText(this@TrainingActivity, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraManager.cleanup()
    }
}
