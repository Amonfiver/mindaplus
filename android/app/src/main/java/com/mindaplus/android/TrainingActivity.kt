/**
 * TrainingActivity.kt - UI de captura de muestras maestras con ROI dual
 *
 * Propósito: Interfaz de usuario para capturar y guardar muestras de referencia
 *            con sistema ROI dual (Search ROI + Master ROI).
 *
 * Alcance: 
 *   - Flujo de captura con dos selecciones manuales:
 *     1. ROI 1 (search): Zona donde buscar el transfer en runtime
 *     2. ROI 2 (master): Muestra maestra exacta del transfer
 *   - Persistencia de ambas ROIs en metadatos
 *   - Preview visual de selecciones con colores distintivos
 *
 * Modo temporal activo: T100 + OK/OBSTACULO
 *   - FALLO desactivado
 *   - T200/T300 desactivados
 *
 * Sistema ROI Dual:
 *   - El usuario captura la lane completa
 *   - Dibuja ROI 1 (zona de búsqueda) - color AZUL
 *   - Dibuja ROI 2 (muestra maestra) - color ROJO, debe estar dentro de ROI 1
 *   - Se guarda la región de ROI 2 como muestra maestra
 *
 * Cambios recientes (SDD - ROI Dual):
 *   - Dos selecciones manuales diferenciadas (search + master)
 *   - Validación: ROI 2 debe estar contenida en ROI 1
 *   - Metadatos espaciales ROI dual persistidos
 *   - Eliminada dependencia de center crop
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
    
    // Estado para selección ROI dual
    private var roi1SelectionStart by mutableStateOf<Offset?>(null)  // Search ROI (azul)
    private var roi1SelectionEnd by mutableStateOf<Offset?>(null)
    private var roi2SelectionStart by mutableStateOf<Offset?>(null)  // Master ROI (rojo)
    private var roi2SelectionEnd by mutableStateOf<Offset?>(null)
    private var selectionCanvasSize by mutableStateOf(IntSize.Zero)
    private var lanePreviewBitmap by mutableStateOf<Bitmap?>(null)
    
    companion object {
        private const val TAG = "TrainingActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        
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
        
        initializeCamera()
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
                            text = "Estado de la muestra maestra",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Selecciona el estado que se guardará para esta captura:",
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
                
                // ROI Dual Selection Preview
                if (lanePreviewBitmap != null && detectedLaneRegion != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Selección ROI Dual",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.Green
                            )
                            
                            Text(
                                text = "1. Dibuja ROI AZUL (zona de búsqueda)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Blue
                            )
                            Text(
                                text = "2. Dibuja ROI ROJO (muestra maestra)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Red
                            )
                            Text(
                                text = "El ROI rojo debe estar dentro del azul",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                            
                            // Preview con selección dual
                            lanePreviewBitmap?.let { bitmap ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp)
                                ) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Lane Preview",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                    Canvas(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .onSizeChanged { selectionCanvasSize = it }
                                            .pointerInput(bitmap) {
                                                detectDragGestures(
                                                    onDragStart = { offset ->
                                                        // Alternar entre ROI 1 y ROI 2 según botón activo
                                                        if (roi1SelectionStart == null || roi1SelectionEnd != null) {
                                                            // Resetear ROI 1
                                                            roi1SelectionStart = offset
                                                            roi1SelectionEnd = offset
                                                            roi2SelectionStart = null
                                                            roi2SelectionEnd = null
                                                        } else {
                                                            // ROI 1 ya existe, dibujar ROI 2
                                                            if (roi2SelectionStart == null) {
                                                                roi2SelectionStart = offset
                                                                roi2SelectionEnd = offset
                                                            }
                                                        }
                                                    },
                                                    onDrag = { change, _ ->
                                                        when {
                                                            roi2SelectionStart != null -> {
                                                                roi2SelectionEnd = change.position
                                                            }
                                                            roi1SelectionStart != null -> {
                                                                roi1SelectionEnd = change.position
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                    ) {
                                        // Dibujar ROI 1 (Search) - AZUL
                                        roi1SelectionStart?.let { start ->
                                            roi1SelectionEnd?.let { end ->
                                                val left = min(start.x, end.x)
                                                val top = min(start.y, end.y)
                                                val right = max(start.x, end.x)
                                                val bottom = max(start.y, end.y)
                                                
                                                drawRect(
                                                    color = Color.Blue,
                                                    topLeft = Offset(left, top),
                                                    size = Size(right - left, bottom - top),
                                                    style = Stroke(width = 4f)
                                                )
                                            }
                                        }
                                        
                                        // Dibujar ROI 2 (Master) - ROJO
                                        roi2SelectionStart?.let { start ->
                                            roi2SelectionEnd?.let { end ->
                                                val left = min(start.x, end.x)
                                                val top = min(start.y, end.y)
                                                val right = max(start.x, end.x)
                                                val bottom = max(start.y, end.y)
                                                
                                                drawRect(
                                                    color = Color.Red,
                                                    topLeft = Offset(left, top),
                                                    size = Size(right - left, bottom - top),
                                                    style = Stroke(width = 4f)
                                                )
                                            }
                                        }
                                    }
                                }
                                
                                // Mostrar info de selecciones
                                SelectionInfo()
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
                        onClick = { capturarParaSeleccionDual() },
                        modifier = Modifier.weight(1f),
                        enabled = !isCapturing && selectedState != TransferState.UNKNOWN && lanePreviewBitmap == null
                    ) {
                        Text("1. Capturar para selección")
                    }
                    
                    Button(
                        onClick = { guardarMuestraDual() },
                        modifier = Modifier.weight(1f),
                        enabled = lanePreviewBitmap != null && 
                                 roi1SelectionStart != null && roi1SelectionEnd != null &&
                                 roi2SelectionStart != null && roi2SelectionEnd != null &&
                                 lastCapturedTransfer == selectedTransfer && 
                                 lastCapturedState == selectedState
                    ) {
                        Text("2. Guardar muestra")
                    }
                }
                
                Button(
                    onClick = { cancelarCapturaDual() },
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
    private fun SelectionInfo() {
        Column {
            // ROI 1 info
            roi1SelectionStart?.let { start1 ->
                roi1SelectionEnd?.let { end1 ->
                    val norm1 = calcularCoordenadasNormalizadas(start1, end1, selectionCanvasSize, lanePreviewBitmap?.width ?: 1, lanePreviewBitmap?.height ?: 1)
                    Text(
                        text = "ROI 1 (búsqueda): ${"%.2f".format(norm1.first)},${"%.2f".format(norm1.second)} - ${"%.2f".format(norm1.third)},${"%.2f".format(norm1.fourth)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Blue
                    )
                }
            }
            
            // ROI 2 info
            roi2SelectionStart?.let { start2 ->
                roi2SelectionEnd?.let { end2 ->
                    val norm2 = calcularCoordenadasNormalizadas(start2, end2, selectionCanvasSize, lanePreviewBitmap?.width ?: 1, lanePreviewBitmap?.height ?: 1)
                    Text(
                        text = "ROI 2 (maestra): ${"%.2f".format(norm2.first)},${"%.2f".format(norm2.second)} - ${"%.2f".format(norm2.third)},${"%.2f".format(norm2.fourth)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Red
                    )
                }
            }
        }
    }
    
    /**
     * Captura frame actual y prepara para selección ROI dual
     */
    private fun capturarParaSeleccionDual() {
        if (isCapturing) return
        if (!MonitoringMode.isTransferEnabled(selectedTransfer) || !MonitoringMode.isStateEnabled(selectedState)) {
            showMessage("Modo temporal: selección no habilitada")
            return
        }
        
        isCapturing = true
        Log.d(TAG, "Captura para selección dual iniciada: T$selectedTransfer ${selectedState.displayName}")
        
        lifecycleScope.launch {
            try {
                val bitmap = lastCapturedBitmap
                if (bitmap == null) {
                    showMessage("No hay imagen disponible")
                    isCapturing = false
                    return@launch
                }
                
                // Detectar lanes
                val laneDetection = laneDetector.detectLanes(bitmap)
                if (laneDetection.requiresCalibration || laneDetection.lanes == null) {
                    showMessage("Error: No se detectaron vías")
                    isCapturing = false
                    return@launch
                }
                
                val lanes = laneDetection.lanes
                val targetLaneIndex = when (selectedTransfer) {
                    100 -> 0
                    200 -> 1
                    300 -> 2
                    else -> 0
                }
                
                if (targetLaneIndex >= lanes.size) {
                    showMessage("Error: Vía no detectada")
                    isCapturing = false
                    return@launch
                }
                
                val targetLane = lanes[targetLaneIndex]
                
                // Recortar lane para preview
                val laneBitmap = ImageUtils.cropBitmap(bitmap, targetLane.left, targetLane.top, targetLane.right, targetLane.bottom)
                if (laneBitmap == null) {
                    showMessage("Error al recortar la vía")
                    isCapturing = false
                    return@launch
                }
                
                // Guardar estado para posterior guardado
                lanePreviewBitmap = laneBitmap
                detectedLaneRegion = targetLane
                lastCapturedTransfer = selectedTransfer
                lastCapturedState = selectedState
                pendingFrameWidth = bitmap.width
                pendingFrameHeight = bitmap.height
                
                // Resetear selecciones
                roi1SelectionStart = null
                roi1SelectionEnd = null
                roi2SelectionStart = null
                roi2SelectionEnd = null
                
                showMessage("Lane capturada. Dibuja ROI azul (búsqueda) y luego ROI rojo (maestra)")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error en captura dual", e)
                showMessage("Error: ${e.message}")
            } finally {
                isCapturing = false
            }
        }
    }
    
    /**
     * Guarda la muestra - DESACTIVADO en modo color HSV
     * El sistema ya no requiere entrenamiento de templates
     */
    private fun guardarMuestraDual() {
        showMessage("Sistema de entrenamiento desactivado. Use detección por color.")
        limpiarEstadoCaptura()
    }
    
    /**
     * Verifica si ROI 1 contiene completamente a ROI 2
     */
    private fun roiContieneOtra(roi1: Quadruple<Float, Float, Float, Float>, roi2: Quadruple<Float, Float, Float, Float>): Boolean {
        return roi2.first >= roi1.first &&    // left2 >= left1
               roi2.second >= roi1.second &&  // top2 >= top1
               roi2.third <= roi1.third &&    // right2 <= right1
               roi2.fourth <= roi1.fourth     // bottom2 <= bottom1
    }
    
    private fun cancelarCapturaDual() {
        limpiarEstadoCaptura()
        showMessage("Captura cancelada")
    }
    
    private fun limpiarEstadoCaptura() {
        lanePreviewBitmap = null
        detectedLaneRegion = null
        lastCapturedTransfer = null
        lastCapturedState = null
        pendingFrameWidth = null
        pendingFrameHeight = null
        roi1SelectionStart = null
        roi1SelectionEnd = null
        roi2SelectionStart = null
        roi2SelectionEnd = null
        selectionCanvasSize = IntSize.Zero
    }
    
    /**
     * Calcula coordenadas normalizadas (0-1) de la selección manual
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
    
    data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
    
    private fun recalibrateTrainingSession() {
        Log.d(TAG, "Recalibrando sesión de entrenamiento")
        laneDetector.clearCache()
        transferMonitor.recalibrateLanes()
        limpiarEstadoCaptura()
        showMessage("Vías recalibradas")
    }
    
    private fun showMessage(message: String) {
        lifecycleScope.launch {
            android.widget.Toast.makeText(this@TrainingActivity, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraManager.cleanup()
    }
}