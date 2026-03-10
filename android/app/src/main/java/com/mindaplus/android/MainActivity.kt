/**
 * MainActivity.kt - Pantalla principal de VIGIA con detección por color
 *
 * Propósito: Interfaz de usuario para monitorización de transfers
 *            mediante detección de color naranja en HSV.
 *
 * Alcance:
 *   - Preview de cámara en tiempo real
 *   - Configuración de Telegram
 *   - Visualización de estado actual (OK/OBSTACULO)
 *   - Panel de debug con evidencia de detección
 *   - Controles de inicio/parada de vigilancia
 *   - Envío de alertas Telegram por transiciones confirmadas
 *
 * Modo temporal activo: T100 + OK/OBSTACULO
 *   - FALLO desactivado
 *   - T200/T300 desactivados
 *
 * Estrategia de vigilancia:
 *   - Detección por color HSV como sistema principal
 *   - Alerta Telegram solo en transición OK -> OBSTACULO confirmada
 *   - Cooldown centralizado en TransferMonitor (no duplicado)
 *   - Silencio absoluto en estado OK
 *
 * Cambios recientes (SDD - Color HSV):
 *   - Migración a detección por color como estrategia principal
 *   - Eliminado sistema de entrenamiento de templates
 *   - Simplificación de UI: solo vigilancia y configuración
 *   - Lógica de alerta centralizada en TransferMonitor
 */
package com.mindaplus.android

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mindaplus.android.ui.theme.MindaplusTheme
import kotlinx.coroutines.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

class MainActivity : ComponentActivity() {
    private lateinit var cameraManager: CameraManager
    private lateinit var telegramNotifier: TelegramNotifier
    private lateinit var transferMonitor: TransferMonitor
    
    // Configuración Telegram
    private var telegramToken by mutableStateOf("")
    private var chatId by mutableStateOf("")
    
    // Estado de vigilancia
    private var isMonitoring by mutableStateOf(false)
    private var transferStates by mutableStateOf(mapOf(100 to TransferState.UNKNOWN))
    
    // Preview
    private var previewView by mutableStateOf<PreviewView?>(null)
    
    // Debug
    private var colorResult by mutableStateOf<ColorDetectionResult?>(null)
    private var laneBitmap by mutableStateOf<Bitmap?>(null)
    
    // Throttle de análisis
    private var lastAnalysisTime = 0L
    private val analysisInterval = 2000L // 2 segundos entre análisis
    private var isAnalysisInProgress = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            initializeCamera()
        } else {
            Log.e("VIGIA", "Camera permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        
        telegramNotifier = TelegramNotifier()
        cameraManager = CameraManager(this)
        transferMonitor = TransferMonitor(this)
        
        setContent {
            MindaplusTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
        
        checkCameraPermission()
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                == PackageManager.PERMISSION_GRANTED -> {
                initializeCamera()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
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
                if (isMonitoring) {
                    analyzeFrame(bitmap)
                }
            }
        )
    }

    /**
     * Analiza frame con throttle y envía alertas de transiciones confirmadas.
     * La lógica de cooldown y detección de transición está centralizada en TransferMonitor.
     */
    private fun analyzeFrame(bitmap: Bitmap) {
        val currentTime = System.currentTimeMillis()
        val timeSinceLast = currentTime - lastAnalysisTime
        
        if (timeSinceLast < analysisInterval || isAnalysisInProgress) {
            return
        }
        
        lastAnalysisTime = currentTime
        isAnalysisInProgress = true
        
        lifecycleScope.launch {
            try {
                // Análisis: retorna estados Y alertas de transición ya filtradas
                val result = transferMonitor.analyzeFrame(bitmap)
                transferStates = result.states
                
                // Guardar debug
                colorResult = transferMonitor.getLatestColorResult()
                laneBitmap = transferMonitor.getLatestLaneBitmap()
                
                // Procesar alertas ya validadas (solo transiciones OK->OBSTACULO con cooldown respetado)
                result.alerts.forEach { alert ->
                    sendTelegramAlert(alert)
                }
                
            } catch (e: Exception) {
                Log.e("VIGIA", "Error analyzing frame", e)
            } finally {
                isAnalysisInProgress = false
            }
        }
    }
    
    /**
     * Envía alerta por Telegram con evidencia.
     * Solo se llama para transiciones OK -> OBSTACULO ya validadas.
     */
    private fun sendTelegramAlert(alert: AlertEvent) {
        val message = "⚠️ ALERTA: Transfer T${alert.transferId} detectó OBSTÁCULO (color naranja)\n" +
                      "Transición: ${alert.fromState.displayName} → ${alert.toState.displayName}"
        
        lifecycleScope.launch {
            try {
                telegramNotifier.updateConfig(telegramToken, chatId)
                val success = telegramNotifier.sendMessage(message)
                
                if (success) {
                    Log.d("VIGIA", "Alert sent for T${alert.transferId}: ${alert.fromState} -> ${alert.toState}")
                    
                    // TODO: Enviar imagen de evidencia si está disponible
                    // alert.evidence?.let { telegramNotifier.sendPhoto(it, chatId) }
                }
            } catch (e: Exception) {
                Log.e("VIGIA", "Error sending alert", e)
            }
        }
    }

    @Composable
    private fun MainScreen() {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Panel izquierdo: Preview de cámara
            Card(
                modifier = Modifier
                    .weight(1.2f)
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
                    } ?: Text("Cámara no disponible")
                }
            }
            
            // Panel derecho: Controles y estado
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Text(
                    text = "VIGIA - Detección por Color",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Text(
                    text = "Modo: T100 | Estados: OK / OBSTÁCULO",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                
                // Configuración Telegram
                TelegramConfigCard()
                
                // Estado actual
                StatusCard()
                
                // Controles
                ControlCard()
                
                // Debug panel
                if (MonitoringMode.debugPanelEnabled) {
                    DebugCard()
                }
                
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
    
    @Composable
    private fun TelegramConfigCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Configuración Telegram",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                OutlinedTextField(
                    value = telegramToken,
                    onValueChange = { telegramToken = it },
                    label = { Text("Bot Token") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isMonitoring,
                    singleLine = true
                )
                
                OutlinedTextField(
                    value = chatId,
                    onValueChange = { chatId = it },
                    label = { Text("Chat ID") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isMonitoring,
                    singleLine = true
                )
            }
        }
    }
    
    @Composable
    private fun StatusCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Estado de Vigilancia",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                // T100
                val state = transferStates[100] ?: TransferState.UNKNOWN
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Transfer 100", style = MaterialTheme.typography.bodyLarge)
                    
                    val (bgColor, textColor) = when (state) {
                        TransferState.OK -> Color(0xFF4CAF50) to Color.White
                        TransferState.OBSTACULO -> Color(0xFFFF5722) to Color.White
                        else -> Color.Gray to Color.White
                    }
                    
                    Surface(
                        color = bgColor,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = when (state) {
                                TransferState.OK -> "OK"
                                TransferState.OBSTACULO -> "OBSTÁCULO"
                                else -> "?"
                            },
                            color = textColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
                
                // Detalles de detección
                colorResult?.let { result ->
                    Divider()
                    
                    Text(
                        "Detección de color:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    
                    Text("• Ratio naranja: ${"%.1f".format(result.orangePixelRatio * 100)}%")
                    Text("• Confianza: ${"%.0f".format(result.confidence * 100)}%")
                    Text("• Blob válido: ${if (result.hasValidBlob) "Sí" else "No"}")
                }
            }
        }
    }
    
    @Composable
    private fun ControlCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Estado de vigilancia
                Surface(
                    color = if (isMonitoring) Color(0xFF4CAF50) else Color.Gray,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isMonitoring) "VIGILANCIA ACTIVA" else "VIGILANCIA INACTIVA",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { startMonitoring() },
                        modifier = Modifier.weight(1f),
                        enabled = !isMonitoring && telegramToken.isNotBlank() && chatId.isNotBlank()
                    ) {
                        Text("Iniciar")
                    }
                    
                    Button(
                        onClick = { stopMonitoring() },
                        modifier = Modifier.weight(1f),
                        enabled = isMonitoring,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5722))
                    ) {
                        Text("Detener")
                    }
                }
                
                OutlinedButton(
                    onClick = { transferMonitor.recalibrateLanes() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isMonitoring
                ) {
                    Text("Recalibrar Vías")
                }
            }
        }
    }
    
    @Composable
    private fun DebugCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Debug - Detección",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                // Imagen de la lane
                laneBitmap?.let { bitmap ->
                    Text("ROI de análisis:", style = MaterialTheme.typography.bodySmall)
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Lane ROI",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                
                // Evidencia con máscara
                colorResult?.evidenceBitmap?.let { evidence ->
                    Text("Máscara de color (verde = naranja):", style = MaterialTheme.typography.bodySmall)
                    Image(
                        bitmap = evidence.asImageBitmap(),
                        contentDescription = "Evidence",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }

    private fun startMonitoring() {
        telegramNotifier.updateConfig(telegramToken, chatId)
        isMonitoring = true
        lastAnalysisTime = 0L
        Log.d("VIGIA", "Monitoring started")
    }

    private fun stopMonitoring() {
        isMonitoring = false
        Log.d("VIGIA", "Monitoring stopped")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraManager.cleanup()
    }
}

// Enum mantenido para compatibilidad
enum class TransferState {
    OK,
    OBSTACULO,
    FALLO,
    UNKNOWN;
    
    val displayName: String
        get() = when (this) {
            OK -> "OK"
            OBSTACULO -> "OBSTÁCULO"
            FALLO -> "FALLO"
            UNKNOWN -> "DESCONOCIDO"
        }
}