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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    
    private var telegramToken by mutableStateOf("")
    private var chatId by mutableStateOf("")
    private var isMonitoring by mutableStateOf(false)
    private var transferStates by mutableStateOf(mapOf(
        100 to TransferState.OK,
        200 to TransferState.OK,
        300 to TransferState.OK
    ))
    private var previewView by mutableStateOf<PreviewView?>(null)
    private var isTrained by mutableStateOf(false)
    private var trainingProgress by mutableStateOf(0 to 9)
    
    // 5-second throttle mechanism
    private var lastAnalysisTime = 0L
    private val analysisInterval = 5000L // 5 seconds as specified in v0.1

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("Mindaplus", "Camera permission granted")
            initializeCamera()
        } else {
            Log.e("Mindaplus", "Camera permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Force landscape orientation
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        
        // Initialize managers
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
        
        // Update training status
        updateTrainingStatus()
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                Log.d("Mindaplus", "Camera permission already granted")
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
                    analyzeCameraFrame(bitmap)
                }
            }
        )
    }

    private fun analyzeCameraFrame(bitmap: Bitmap) {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastAnalysis = currentTime - lastAnalysisTime
        
        // Implement 5-second throttle
        if (timeSinceLastAnalysis < analysisInterval) {
            Log.d("Mindaplus", "MainActivity: Frame skipped due to throttle (${timeSinceLastAnalysis}ms < ${analysisInterval}ms)")
            return
        }
        
        lastAnalysisTime = currentTime
        Log.d("Mindaplus", "MainActivity: Analysis tick - processing frame after ${timeSinceLastAnalysis}ms")
        
        lifecycleScope.launch {
            try {
                val imageWidth = bitmap.width
                val imageHeight = bitmap.height

                Log.d("Mindaplus", "MainActivity: Analyzing frame ${imageWidth}x${imageHeight}")

                // Analyze frame directly as Bitmap (safe after ImageProxy close)
                val newStates = transferMonitor.analyzeFrame(bitmap)
                
                Log.d("Mindaplus", "MainActivity: Analysis results: $newStates")
                
                // Check for state changes and send notifications
                newStates.forEach { (transferId, newState) ->
                    val oldState = transferStates[transferId]
                    if (oldState != newState) {
                        Log.d("Mindaplus", "MainActivity: Transfer $transferId state changed from $oldState to $newState")
                        sendNotification(transferId, oldState!!, newState)
                    }
                }
                
                // Update UI state
                transferStates = newStates
                
            } catch (e: Exception) {
                Log.e("Mindaplus", "MainActivity: Error analyzing camera frame", e)
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
            
            // Right panel - Controls and Status
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "V0.2 TRAINING UI",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.Magenta
                )
                // Telegram Configuration
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Telegram Configuration",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        
                        OutlinedTextField(
                            value = telegramToken,
                            onValueChange = { telegramToken = it },
                            label = { Text("Bot Token") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isMonitoring
                        )
                        
                        OutlinedTextField(
                            value = chatId,
                            onValueChange = { chatId = it },
                            label = { Text("Chat ID") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isMonitoring
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { startMonitoring() },
                                enabled = !isMonitoring && telegramToken.isNotBlank() && chatId.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Start Monitoring")
                            }
                            
                            Button(
                                onClick = { stopMonitoring() },
                                enabled = isMonitoring,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                            ) {
                                Text("Stop Monitoring")
                            }
                        }
                    }
                }
                
                // Transfer Status
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Transfer Status",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        
                        TransferStatusRow(100, transferStates[100]!!)
                        TransferStatusRow(200, transferStates[200]!!)
                        TransferStatusRow(300, transferStates[300]!!)
                    }
                }
                
                // Training Status and Controls
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Training Status",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Entrenado:",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            
                            Text(
                                text = if (isTrained) "Sí" else "No",
                                color = if (isTrained) Color.Green else Color.Red,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Progreso:",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            
                            Text(
                                text = "${trainingProgress.first}/${trainingProgress.second}",
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { openTrainingScreen() },
                                modifier = Modifier.weight(1f),
                                enabled = !isMonitoring
                            ) {
                                Text("Entrenamiento")
                            }
                            
                            Button(
                                onClick = { recalibrateLanes() },
                                modifier = Modifier.weight(1f),
                                enabled = !isMonitoring
                            ) {
                                Text("Recalibrar vías")
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                // Monitoring Status
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isMonitoring) "Monitoring Active" else "Monitoring Inactive",
                            color = if (isMonitoring) Color.Green else Color.Red,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun TransferStatusRow(transferId: Int, state: TransferState) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Transfer $transferId",
                style = MaterialTheme.typography.bodyLarge
            )
            
            Text(
                text = state.displayName,
                color = when (state) {
                    TransferState.OK -> Color.Green
                    TransferState.OBSTACULO -> Color.Red
                    TransferState.FALLO -> Color(0xFFFFA500) // Orange
                    TransferState.UNKNOWN -> Color.Gray
                },
                fontWeight = FontWeight.Bold
            )
        }
    }

    private fun startMonitoring() {
        Log.d("Mindaplus", "MainActivity: Starting monitoring")
        telegramNotifier.updateConfig(telegramToken, chatId)
        isMonitoring = true
        lastAnalysisTime = 0L // Reset throttle timer
        Log.d("Mindaplus", "MainActivity: Monitoring started with 5-second throttle")
    }

    private fun stopMonitoring() {
        Log.d("Mindaplus", "MainActivity: Stopping monitoring")
        isMonitoring = false
        Log.d("Mindaplus", "MainActivity: Monitoring stopped")
    }
    
    private fun openTrainingScreen() {
        Log.d("Mindaplus", "MainActivity: Opening training screen")
        val intent = android.content.Intent(this, TrainingActivity::class.java)
        startActivity(intent)
    }
    
    private fun recalibrateLanes() {
        Log.d("Mindaplus", "MainActivity: Recalibrating lanes")
        transferMonitor.recalibrateLanes()
        Log.d("Mindaplus", "MainActivity: Lane calibration reset")
    }
    
    private fun updateTrainingStatus() {
        isTrained = transferMonitor.isTrained()
        trainingProgress = transferMonitor.getTrainingProgress()
        Log.d("Mindaplus", "MainActivity: Training status updated - trained: $isTrained, progress: ${trainingProgress.first}/${trainingProgress.second}")
    }

    private fun sendNotification(transferId: Int, oldState: TransferState, newState: TransferState) {
        val message = when {
            oldState == TransferState.OK && newState == TransferState.OBSTACULO -> 
                "Transfer $transferId parado, obstáculo en la vía."
            oldState == TransferState.OBSTACULO && newState == TransferState.OK -> 
                "Transfer $transferId rearmado, todo OK."
            newState == TransferState.FALLO -> 
                "Transfer $transferId en fallo."
            else -> return
        }
        
        Log.d("Mindaplus", "MainActivity: Sending Telegram notification: $message")
        
        lifecycleScope.launch {
            try {
                val success = telegramNotifier.sendMessage(message)
                if (success) {
                    Log.d("Mindaplus", "MainActivity: Telegram notification sent successfully")
                } else {
                    Log.e("Mindaplus", "MainActivity: Failed to send Telegram notification")
                }
            } catch (e: Exception) {
                Log.e("Mindaplus", "MainActivity: Exception sending Telegram notification", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.cleanup()
    }
}

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
