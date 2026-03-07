package com.mindaplus.android

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
        onFrameAnalyzed: (Bitmap) -> Unit
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
                                // Convertir ImageProxy a Bitmap ANTES de cerrar el ImageProxy
                                val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
                                if (bitmap != null) {
                                    Log.d("Mindaplus", "CameraX: Frame converted to bitmap, size=${bitmap.width}x${bitmap.height}")
                                    onFrameAnalyzed(bitmap)
                                } else {
                                    Log.w("Mindaplus", "CameraX: Failed to convert ImageProxy to bitmap")
                                }
                            } catch (e: Exception) {
                                Log.e("Mindaplus", "CameraX: Error analyzing image", e)
                            } finally {
                                // Cerrar ImageProxy después de la conversión
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