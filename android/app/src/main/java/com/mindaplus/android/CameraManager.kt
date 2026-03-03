package com.mindaplus.android

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

class CameraManager(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onFrameAnalyzed: (android.media.Image) -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                
                // Preview
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                
                // Image Analysis
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(executor) { imageProxy ->
                            try {
                                val image = imageProxy.image
                                if (image != null) {
                                    Log.d("Mindaplus", "CameraX: Frame received, format=${image.format}, size=${image.width}x${image.height}")
                                    onFrameAnalyzed(image)
                                } else {
                                    Log.w("Mindaplus", "CameraX: ImageProxy.image is null")
                                }
                            } catch (e: Exception) {
                                Log.e("Mindaplus", "CameraX: Error analyzing image", e)
                            } finally {
                                imageProxy.close()
                                Log.d("Mindaplus", "CameraX: ImageProxy closed")
                            }
                        }
                    }
                
                // Select back camera
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                
                try {
                    // Unbind use cases before rebinding
                    cameraProvider?.unbindAll()
                    
                    // Bind use cases to camera
                    cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                    
                    Log.d("Mindaplus", "Camera started successfully")
                } catch (exc: Exception) {
                    Log.e("Mindaplus", "Use case binding failed", exc)
                }
                
            } catch (exc: Exception) {
                Log.e("Mindaplus", "Error starting camera", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }
    
    fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
            Log.d("Mindaplus", "Camera stopped")
        } catch (exc: Exception) {
            Log.e("Mindaplus", "Error stopping camera", exc)
        }
    }
    
    fun cleanup() {
        executor.shutdown()
    }
}