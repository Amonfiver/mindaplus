package com.mindaplus.android

import android.graphics.Bitmap
import android.media.Image
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    
    private var previewView by mutableStateOf<PreviewView?>(null)
    private var selectedTransfer by mutableStateOf(100)
    private var selectedState by mutableStateOf(TransferState.OK)
    private var trainingProgress by mutableStateOf(0 to 9)
    private var isCapturing by mutableStateOf(false)
    private var lastCapturedImage by mutableStateOf<Image?>(null)
    
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
            onFrameAnalyzed = { image ->
                // Store the latest image for template capture
                lastCapturedImage = image
            }
        )
    }
    
    private fun updateTrainingProgress() {
        trainingProgress = templateStorage.getTrainingProgress()
    }
    
    @Composable
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
                            progress = trainingProgress.first.toFloat() / trainingProgress.second.toFloat(),
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
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { selectedTransfer = 100 },
                                modifier = Modifier.weight(1f),
                                colors = if (selectedTransfer == 100) {
                                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                } else {
                                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                }
                            ) {
                                Text("T100")
                            }
                            Button(
                                onClick = { selectedTransfer = 200 },
                                modifier = Modifier.weight(1f),
                                colors = if (selectedTransfer == 200) {
                                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                } else {
                                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                }
                            ) {
                                Text("T200")
                            }
                            Button(
                                onClick = { selectedTransfer = 300 },
                                modifier = Modifier.weight(1f),
                                colors = if (selectedTransfer == 300) {
                                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                } else {
                                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                }
                            ) {
                                Text("T300")
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
                            text = "State Selection",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StateButton("OK", selectedState == TransferState.OK) { selectedState = TransferState.OK }
                            StateButton("OBSTÁCULO", selectedState == TransferState.OBSTACULO) { selectedState = TransferState.OBSTACULO }
                            StateButton("FALLO", selectedState == TransferState.FALLO) { selectedState = TransferState.FALLO }
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
                        onClick = { saveAndFinish() },
                        modifier = Modifier.weight(1f),
                        enabled = trainingProgress.first > 0
                    ) {
                        Text("Guardar")
                    }
                }
                
                Button(
                    onClick = { finish() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancelar")
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
        
        isCapturing = true
        
        lifecycleScope.launch {
            try {
                val image = lastCapturedImage
                if (image == null) {
                    Log.w(TAG, "No image available for template capture")
                    isCapturing = false
                    return@launch
                }
                
                Log.d(TAG, "Capturing template for T$selectedTransfer ${selectedState.displayName}")
                
                // Convert image to bitmap
                val bitmap = imageToBitmap(image)
                if (bitmap == null) {
                    Log.e(TAG, "Failed to convert image to bitmap")
                    isCapturing = false
                    return@launch
                }
                
                // Save template
                val success = templateStorage.saveTemplate(selectedTransfer, selectedState, bitmap)
                if (success) {
                    Log.d(TAG, "Template captured successfully for T$selectedTransfer ${selectedState.displayName}")
                    updateTrainingProgress()
                    
                    // Show success message
                    showMessage("Template capturado exitosamente")
                } else {
                    Log.e(TAG, "Failed to save template")
                    showMessage("Error al capturar template")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing template", e)
                showMessage("Error al capturar template: ${e.message}")
            } finally {
                isCapturing = false
            }
        }
    }
    
    private fun saveAndFinish() {
        Log.d(TAG, "Saving training data and finishing")
        
        lifecycleScope.launch {
            try {
                // Training data is already saved with each template capture
                // Just verify we have the expected progress
                val progress = templateStorage.getTrainingProgress()
                if (progress.first == progress.second) {
                    Log.d(TAG, "Training complete: ${progress.first}/${progress.second} templates")
                    showMessage("Entrenamiento completado exitosamente")
                } else {
                    Log.d(TAG, "Training saved: ${progress.first}/${progress.second} templates")
                    showMessage("Progreso guardado: ${progress.first}/${progress.second} templates")
                }
                
                setResult(RESULT_OK)
                finish()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error saving training data", e)
                showMessage("Error al guardar: ${e.message}")
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