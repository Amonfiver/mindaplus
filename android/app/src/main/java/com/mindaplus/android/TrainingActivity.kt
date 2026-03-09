/**
 * TrainingActivity.kt - UI de captura de muestras maestras para entrenamiento
 *
 * Propósito: Interfaz de usuario para capturar y guardar muestras de referencia
 *            de los diferentes estados de los transfers (OK, OBSTACULO).
 *
 * Alcance: Lógica de captura con selección manual rectangular sobre preview.
 *          El usuario dibuja la ROI deseada y se recorta al guardar.
 *
 * Modo temporal activo: T100 únicamente, estados OK y OBSTACULO habilitados.
 *                       FALLO desactivado. ROI manual persistente desactivado.
 *
 * Cambios recientes (SDD):
 *   - Eliminado bloque visible "Calibración ROI Manual (Gestor)"
 *   - Renombrados textos: plantilla -> muestra maestra, Template Captured, etc.
 *   - captureTemplate() ya no usa ROI manual persistente, solo lane completa
 *   - Selección manual rectangular sobre preview, aplicada al guardar
 *   - testRecorteAutomatico() limpia estado de guardado para evitar confusión
 */

package com.mindaplus.android

import android.graphics.Bitmap
import android.media.Image
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.mindaplus.android.ui.theme.MindaplusTheme
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

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
    private var calibrationLaneBitmap by mutableStateOf<Bitmap?>(null)
    private var manualRoi by mutableStateOf<TemplateStorage.ManualRoi?>(null)
    private var calibrationStart by mutableStateOf<Offset?>(null)
    private var calibrationEnd by mutableStateOf<Offset?>(null)
    private var calibrationCanvasSize by mutableStateOf(IntSize.Zero)
    
    // Estado para selección manual rectangular en flujo de muestra maestra (NO persistente)
    private var muestraMaestraSelectionStart by mutableStateOf<Offset?>(null)
    private var muestraMaestraSelectionEnd by mutableStateOf<Offset?>(null)
    private var muestraMaestraCanvasSize by mutableStateOf(IntSize.Zero)
    private var muestraMaestraBitmap by mutableStateOf<Bitmap?>(null)
    
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
                    LaunchedEffect(selectedTransfer) {
                        manualRoi = transferMonitor.getManualRoi(selectedTransfer)
                    }
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
        manualRoi = transferMonitor.getManualRoi(selectedTransfer)
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
        manualRoi = transferMonitor.getManualRoi(selectedTransfer)
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
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Estado manual de la muestra maestra",
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
                
                // Template Preview (shown after capture) con selección manual rectangular
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
                                text = "Muestra maestra capturada",
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
                            
                            // Preview con selección manual rectangular interactiva
                            capturedTemplatePreview?.let { bitmap ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp)
                                ) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Template Preview",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                    Canvas(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .onSizeChanged { muestraMaestraCanvasSize = it }
                                            .pointerInput(bitmap) {
                                                detectDragGestures(
                                                    onDragStart = { offset ->
                                                        muestraMaestraSelectionStart = offset
                                                        muestraMaestraSelectionEnd = offset
                                                        muestraMaestraBitmap = bitmap
                                                    },
                                                    onDrag = { change, _ ->
                                                        muestraMaestraSelectionEnd = change.position
                                                    }
                                                )
                                            }
                                    ) {
                                        // Dibujar rectángulo de selección si existe
                                        val start = muestraMaestraSelectionStart
                                        val end = muestraMaestraSelectionEnd
                                        if (start != null && end != null) {
                                            val left = min(start.x, end.x)
                                            val top = min(start.y, end.y)
                                            val right = max(start.x, end.x)
                                            val bottom = max(start.y, end.y)
                                            
                                            drawRect(
                                                color = Color.Red,
                                                topLeft = Offset(left, top),
                                                size = Size(right - left, bottom - top),
                                                style = Stroke(width = 3f)
                                            )
                                        }
                                    }
                                }
                                
                                // Mostrar coordenadas de selección si existen
                                muestraMaestraSelectionStart?.let { start ->
                                    muestraMaestraSelectionEnd?.let { end ->
                                        val normCoords = calcularCoordenadasNormalizadas(
                                            start, 
                                            end, 
                                            muestraMaestraCanvasSize,
                                            bitmap.width,
                                            bitmap.height
                                        )
                                        Text(
                                            text = "Selección: (${"%.3f".format(normCoords.first)}, ${"%.3f".format(normCoords.second)}) - (${"%.3f".format(normCoords.third)}, ${"%.3f".format(normCoords.fourth)})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Blue
                                        )
                                    }
                                }
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
                        onClick = { capturarMuestraMaestra() },
                        modifier = Modifier.weight(1f),
                        enabled = !isCapturing && selectedState != TransferState.UNKNOWN
                    ) {
                        Text("Capturar muestra maestra")
                    }
                    
                    Button(
                        onClick = { guardarMuestraMaestra() },
                        modifier = Modifier.weight(1f),
                        enabled = capturedTemplatePreview != null && 
                                 lastCapturedTransfer == selectedTransfer && 
                                 lastCapturedState == selectedState
                    ) {
                        Text("Guardar muestra")
                    }
                }
                
                Button(
                        onClick = { cancelarCaptura() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancelar")
                }

                // Botón de test para recorte automático (NO guarda)
                OutlinedButton(
                    onClick = { testRecorteAutomatico() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isCapturing
                ) {
                    Text("Test recorte automático")
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
    
    private fun capturarMuestraMaestra() {
        if (isCapturing) return
        if (!MonitoringMode.isTransferEnabled(selectedTransfer) || !MonitoringMode.isStateEnabled(selectedState)) {
            showMessage("Modo temporal: selección no habilitada")
            return
        }
        
        isCapturing = true
                Log.d(TAG, "Captura muestra maestra iniciada para T$selectedTransfer ${selectedState.displayName}")
        
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
                
                // Step 3: Crop bitmap to the detected lane region (solo lane completa, sin ROI manual)
                val laneBitmap = ImageUtils.cropBitmap(bitmap, targetLane.left, targetLane.top, targetLane.right, targetLane.bottom)
                if (laneBitmap == null) {
                    Log.e(TAG, "Failed to crop bitmap to lane region")
                    showMessage("Error al recortar la región")
                    isCapturing = false
                    return@launch
                }

                // Modo simplificado: usar lane completa como muestra, sin ROI manual persistente
                val focusedBitmap = laneBitmap
                
                Log.d(
                    TAG,
                    "Muestra capturada (modo simplificado): laneCompleta=${laneBitmap.width}x${laneBitmap.height}"
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
    
    private fun cancelarCaptura() {
        Log.d(TAG, "Canceling current capture and clearing preview")
        clearPendingCaptureData()
        showMessage("Captura cancelada")
    }
    
    private fun guardarMuestraMaestra() {
        Log.d(TAG, "Guardando muestra maestra")
        
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
                    Log.e(TAG, "No hay muestra maestra pendiente para guardar")
                    showMessage("Error: No hay muestra maestra pendiente para guardar")
                    return@launch
                }
                
                // Verificar si existe selección manual válida
                val selectionStart = muestraMaestraSelectionStart
                val selectionEnd = muestraMaestraSelectionEnd
                val canvasSize = muestraMaestraCanvasSize
                
                val bitmapParaGuardar = if (selectionStart != null && 
                                            selectionEnd != null && 
                                            canvasSize.width > 0 && 
                                            canvasSize.height > 0 &&
                                            muestraMaestraBitmap != null) {
                    // Hay selección manual válida, aplicar recorte
                    val coords = calcularCoordenadasNormalizadas(
                        selectionStart,
                        selectionEnd,
                        canvasSize,
                        templateBitmap.width,
                        templateBitmap.height
                    )
                    
                    // Verificar que la selección sea válida (mínimo 3% en cada dimensión)
                    if (coords.third - coords.first < 0.03f || coords.fourth - coords.second < 0.03f) {
                        Log.w(TAG, "Selección manual demasiado pequeña")
                        showMessage("Error: La selección es demasiado pequeña. Dibuja un área mayor.")
                        isCapturing = false
                        return@launch
                    }
                    
                    // Recortar según selección manual
                    val left = (coords.first * templateBitmap.width).toInt().coerceIn(0, templateBitmap.width - 1)
                    val top = (coords.second * templateBitmap.height).toInt().coerceIn(0, templateBitmap.height - 1)
                    val right = (coords.third * templateBitmap.width).toInt().coerceIn(left + 1, templateBitmap.width)
                    val bottom = (coords.fourth * templateBitmap.height).toInt().coerceIn(top + 1, templateBitmap.height)
                    
                    val recortado = ImageUtils.cropBitmap(templateBitmap, left, top, right, bottom)
                    
                    if (recortado != null) {
                        Log.d(TAG, "Muestra recortada con selección manual: ${recortado.width}x${recortado.height}")
                        recortado
                    } else {
                        Log.w(TAG, "Fallo al recortar con selección manual, usando bitmap completo")
                        templateBitmap
                    }
                } else {
                    // No hay selección manual válida, mostrar mensaje de error
                    Log.w(TAG, "No hay selección manual válida para guardar")
                    showMessage("Error: Debes dibujar una selección rectangular sobre la preview antes de guardar")
                    isCapturing = false
                    return@launch
                }
                
                // Guardar el template en TemplateStorage
                val success = transferMonitor.addLabeledSample(
                    transferId = transfer,
                    state = state,
                    bitmap = bitmapParaGuardar,
                    laneRegion = laneRegion,
                    frameWidth = frameWidth ?: bitmapParaGuardar.width,
                    frameHeight = frameHeight ?: bitmapParaGuardar.height
                )
                if (success) {
                    val centerY = laneRegion?.let { (it.top + it.bottom) / 2f }
                    Log.d(
                        TAG,
                        "Muestra guardada exitosamente para T$transfer ${state.displayName} lane=${laneRegion ?: "legacy/no-roi"} centerY=${centerY?.let { "%.1f".format(it) } ?: "n/a"} frame=${frameWidth ?: bitmapParaGuardar.width}x${frameHeight ?: bitmapParaGuardar.height}"
                    )
                    
                    // Actualizar progreso
                    updateTrainingProgress()
                    
                    // Verificar si el entrenamiento está completo
                    val stats = templateStorage.getTrainingStats()
                    if (stats.baseCovered == stats.baseTotal) {
                        Log.d(TAG, "Base de entrenamiento completa: ${stats.baseCovered}/${stats.baseTotal} con ${stats.totalSamples} muestras")
                        showMessage("Muestra guardada. Base completa ${stats.baseCovered}/${stats.baseTotal}. Total: ${stats.totalSamples}")
                    } else {
                        Log.d(TAG, "Muestra guardada: ${stats.baseCovered}/${stats.baseTotal}, muestras=${stats.totalSamples}")
                        showMessage("Muestra guardada: base ${stats.baseCovered}/${stats.baseTotal}, total ${stats.totalSamples}")
                    }
                    
                    clearPendingCaptureData()
                } else {
                    Log.e(TAG, "Fallo al guardar muestra maestra")
                    showMessage("Error al guardar la muestra maestra")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error guardando muestra maestra", e)
                showMessage("Error al guardar: ${e.message}")
            } finally {
                isCapturing = false
            }
        }
    }

    private fun captureLaneForCalibration() {
        val bitmap = lastCapturedBitmap
        if (bitmap == null) {
            showMessage("No hay imagen disponible para calibración")
            return
        }
        val laneDetection = laneDetector.detectLanes(bitmap)
        val lanes = laneDetection.lanes
        if (laneDetection.requiresCalibration || lanes == null || lanes.isEmpty()) {
            showMessage("No se pudo detectar lane para calibración")
            return
        }
        val laneIndex = when (selectedTransfer) {
            100 -> 0
            200 -> 1
            300 -> 2
            else -> 0
        }
        if (laneIndex >= lanes.size) {
            showMessage("Lane no disponible para calibración")
            return
        }
        val lane = lanes[laneIndex]
        val laneBitmap = ImageUtils.cropBitmap(bitmap, lane.left, lane.top, lane.right, lane.bottom)
        if (laneBitmap == null) {
            showMessage("Error al recortar lane de calibración")
            return
        }
        Log.d(
            TAG,
            "Calibration lane capture frame=${bitmap.width}x${bitmap.height} laneRegion=(${lane.left},${lane.top})-(${lane.right},${lane.bottom}) laneBitmap=${laneBitmap.width}x${laneBitmap.height}"
        )
        calibrationLaneBitmap = laneBitmap
        calibrationStart = null
        calibrationEnd = null
        showMessage("Lane capturada. Dibuja ROI manual y pulsa Guardar ROI.")
    }

    private fun saveManualRoiFromSelection() {
        val laneBitmap = calibrationLaneBitmap ?: return
        val start = calibrationStart ?: return
        val end = calibrationEnd ?: return
        if (laneBitmap.width <= 0 || laneBitmap.height <= 0) return

        val mapping = ImageUtils.computeFitDisplayMapping(
            viewWidth = calibrationCanvasSize.width,
            viewHeight = calibrationCanvasSize.height,
            bitmapWidth = laneBitmap.width,
            bitmapHeight = laneBitmap.height
        )

        val viewLeft = min(start.x, end.x)
        val viewTop = min(start.y, end.y)
        val viewRight = max(start.x, end.x)
        val viewBottom = max(start.y, end.y)

        val bmpLeft = ((viewLeft - mapping.offsetX) / mapping.scale).coerceIn(0f, laneBitmap.width.toFloat())
        val bmpTop = ((viewTop - mapping.offsetY) / mapping.scale).coerceIn(0f, laneBitmap.height.toFloat())
        val bmpRight = ((viewRight - mapping.offsetX) / mapping.scale).coerceIn(0f, laneBitmap.width.toFloat())
        val bmpBottom = ((viewBottom - mapping.offsetY) / mapping.scale).coerceIn(0f, laneBitmap.height.toFloat())

        val leftNorm = (bmpLeft / laneBitmap.width.toFloat()).coerceIn(0f, 1f)
        val topNorm = (bmpTop / laneBitmap.height.toFloat()).coerceIn(0f, 1f)
        val rightNorm = (bmpRight / laneBitmap.width.toFloat()).coerceIn(0f, 1f)
        val bottomNorm = (bmpBottom / laneBitmap.height.toFloat()).coerceIn(0f, 1f)

        if (rightNorm - leftNorm < 0.03f || bottomNorm - topNorm < 0.03f) {
            showMessage("ROI demasiado pequeña")
            return
        }

        Log.d(
            TAG,
            "ROI calibration geometry laneBmp=${laneBitmap.width}x${laneBitmap.height} view=${calibrationCanvasSize.width}x${calibrationCanvasSize.height} scaleMode=Fit scale=${"%.4f".format(mapping.scale)} offset=(${String.format("%.2f", mapping.offsetX)},${String.format("%.2f", mapping.offsetY)}) viewRoi=(${String.format("%.1f", viewLeft)},${String.format("%.1f", viewTop)})-(${String.format("%.1f", viewRight)},${String.format("%.1f", viewBottom)}) bmpRoi=(${String.format("%.1f", bmpLeft)},${String.format("%.1f", bmpTop)})-(${String.format("%.1f", bmpRight)},${String.format("%.1f", bmpBottom)}) normRoi=(${String.format("%.4f", leftNorm)},${String.format("%.4f", topNorm)})-(${String.format("%.4f", rightNorm)},${String.format("%.4f", bottomNorm)})"
        )

        val roi = TemplateStorage.ManualRoi(
            leftNorm = leftNorm,
            topNorm = topNorm,
            rightNorm = rightNorm,
            bottomNorm = bottomNorm,
            updatedAt = System.currentTimeMillis()
        )
        val saved = transferMonitor.saveManualRoi(selectedTransfer, roi)
        if (saved) {
            manualRoi = transferMonitor.getManualRoi(selectedTransfer)
            showMessage("ROI manual guardada para T$selectedTransfer")
            Log.d(TAG, "Manual ROI saved for T$selectedTransfer: $manualRoi")
        } else {
            showMessage("Error guardando ROI manual")
        }
    }

    private fun cropManualRoiFromLane(laneBitmap: Bitmap, roi: TemplateStorage.ManualRoi): Bitmap? {
        if (!roi.isValid()) return null
        val left = (roi.leftNorm * laneBitmap.width).toInt().coerceIn(0, laneBitmap.width - 1)
        val top = (roi.topNorm * laneBitmap.height).toInt().coerceIn(0, laneBitmap.height - 1)
        val right = (roi.rightNorm * laneBitmap.width).toInt().coerceIn(left + 1, laneBitmap.width)
        val bottom = (roi.bottomNorm * laneBitmap.height).toInt().coerceIn(top + 1, laneBitmap.height)
        Log.d(
            TAG,
            "Applying manual ROI lane=${laneBitmap.width}x${laneBitmap.height} norm=(${String.format("%.4f", roi.leftNorm)},${String.format("%.4f", roi.topNorm)})-(${String.format("%.4f", roi.rightNorm)},${String.format("%.4f", roi.bottomNorm)}) px=($left,$top)-($right,$bottom)"
        )
        return ImageUtils.cropBitmap(laneBitmap, left, top, right, bottom)
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
        // Limpiar también la selección manual de muestra maestra
        muestraMaestraSelectionStart = null
        muestraMaestraSelectionEnd = null
        muestraMaestraCanvasSize = IntSize.Zero
        muestraMaestraBitmap = null
    }

    /**
     * Calcula coordenadas normalizadas (0-1) de la selección manual sobre la preview.
     * NO guarda en persistencia, solo devuelve valores para uso inmediato.
     */
    private fun calcularCoordenadasNormalizadas(
        start: Offset,
        end: Offset,
        canvasSize: IntSize,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): Quadruple<Float, Float, Float, Float> {
        if (canvasSize.width <= 0 || canvasSize.height <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) {
            return Quadruple(0f, 0f, 1f, 1f)
        }

        val mapping = ImageUtils.computeFitDisplayMapping(
            viewWidth = canvasSize.width,
            viewHeight = canvasSize.height,
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight
        )

        val viewLeft = min(start.x, end.x)
        val viewTop = min(start.y, end.y)
        val viewRight = max(start.x, end.x)
        val viewBottom = max(start.y, end.y)

        val bmpLeft = ((viewLeft - mapping.offsetX) / mapping.scale).coerceIn(0f, bitmapWidth.toFloat())
        val bmpTop = ((viewTop - mapping.offsetY) / mapping.scale).coerceIn(0f, bitmapHeight.toFloat())
        val bmpRight = ((viewRight - mapping.offsetX) / mapping.scale).coerceIn(0f, bitmapWidth.toFloat())
        val bmpBottom = ((viewBottom - mapping.offsetY) / mapping.scale).coerceIn(0f, bitmapHeight.toFloat())

        val leftNorm = (bmpLeft / bitmapWidth.toFloat()).coerceIn(0f, 1f)
        val topNorm = (bmpTop / bitmapHeight.toFloat()).coerceIn(0f, 1f)
        val rightNorm = (bmpRight / bitmapWidth.toFloat()).coerceIn(0f, 1f)
        val bottomNorm = (bmpBottom / bitmapHeight.toFloat()).coerceIn(0f, 1f)

        return Quadruple(leftNorm, topNorm, rightNorm, bottomNorm)
    }

    /**
     * Data class simple para retornar 4 valores (tupla)
     */
    data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    /**
     * Test del recorte automático sin guardar.
     * Toma frame actual, detecta lane, aplica center crop temporal y muestra preview.
     * NO guarda entrenamiento, NO modifica muestras existentes.
     */
    private fun testRecorteAutomatico() {
        if (isCapturing) return
        
        isCapturing = true
        Log.d(TAG, "Test recorte automático iniciado para T$selectedTransfer")
        
        lifecycleScope.launch {
            try {
                val bitmap = lastCapturedBitmap
                if (bitmap == null) {
                    Log.w(TAG, "No bitmap disponible para test")
                    showMessage("No hay imagen disponible")
                    isCapturing = false
                    return@launch
                }
                
                // Paso 1: Detectar lanes
                val laneDetection = laneDetector.detectLanes(bitmap)
                if (laneDetection.requiresCalibration || laneDetection.lanes == null) {
                    Log.e(TAG, "Lane detection falló en test")
                    showMessage("Error: No se detectaron vías")
                    isCapturing = false
                    return@launch
                }
                
                val lanes = laneDetection.lanes
                
                // Paso 2: Seleccionar lane según transfer
                val targetLaneIndex = when (selectedTransfer) {
                    100 -> 0
                    200 -> 1
                    300 -> 2
                    else -> 0
                }
                
                if (targetLaneIndex >= lanes.size) {
                    Log.e(TAG, "Lane no disponible para test")
                    showMessage("Error: Vía no detectada")
                    isCapturing = false
                    return@launch
                }
                
                val targetLane = lanes[targetLaneIndex]
                
                // Paso 3: Recortar lane completa
                val laneBitmap = ImageUtils.cropBitmap(
                    bitmap, 
                    targetLane.left, 
                    targetLane.top, 
                    targetLane.right, 
                    targetLane.bottom
                )
                
                if (laneBitmap == null) {
                    Log.e(TAG, "Fallo al recortar lane en test")
                    showMessage("Error al recortar región")
                    isCapturing = false
                    return@launch
                }
                
                // Paso 4: Aplicar recorte automático (center crop temporal)
                val recorteAutomatico = ImageUtils.cropCenteredByRatio(
                    laneBitmap,
                    MonitoringMode.focusedSubRoiWidthRatio,
                    MonitoringMode.focusedSubRoiHeightRatio
                ) ?: laneBitmap
                
                Log.d(
                    TAG,
                    "Test recorte automático: lane=${laneBitmap.width}x${laneBitmap.height} recorte=${recorteAutomatico.width}x${recorteAutomatico.height}"
                )
                
                // Paso 5: Mostrar en preview (SOLO preview, sin guardar estado de entrenamiento)
                // IMPORTANTE: Limpiar cualquier estado pendiente de guardado para evitar confusión
                clearPendingCaptureData()
                
                // Ahora asignamos solo para preview visual
                capturedTemplatePreview = recorteAutomatico
                detectedLaneRegion = targetLane
                // NO asignamos: pendingTemplateBitmap, lastCapturedTransfer, lastCapturedState
                // para que el botón "Guardar muestra" permanezca deshabilitado
                
                showMessage("Test completado: recorte ${recorteAutomatico.width}x${recorteAutomatico.height}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error en test recorte automático", e)
                showMessage("Error en test: ${e.message}")
            } finally {
                isCapturing = false
            }
        }
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
