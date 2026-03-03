package com.mindaplus.android

import android.Manifest
import android.content.pm.PackageManager
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
        transferMonitor = TransferMonitor()
        
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
            onFrameAnalyzed = { image ->
                if (isMonitoring) {
                    analyzeCameraFrame(image)
                }
            }
        )
    }

    private fun analyzeCameraFrame(image: android.media.Image) {
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
                val imageWidth = image.width
                val imageHeight = image.height
                
                Log.d("Mindaplus", "MainActivity: Analyzing frame ${imageWidth}x${imageHeight}")
                
                // Analyze frame using TransferMonitor
                val newStates = transferMonitor.analyzeFrame(image, imageWidth, imageHeight)
                
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
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
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

    private fun sendNotification(transferId: Int, oldState: TransferState, newState: TransferState) {
        val message = when {
            oldState == TransferState.OK && newState == TransferState.OBSTACULO -> 
                "Transfer $transferId parado, obstáculo en la vía."
            oldState == TransferState.OBSTACULO && newState == TransferState.OK -> 
                "Transfer $transferId rearmado, todo OK."
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
    OBSTACULO;
    
    val displayName: String
        get() = when (this) {
            OK -> "OK"
            OBSTACULO -> "OBSTÁCULO"
        }
}