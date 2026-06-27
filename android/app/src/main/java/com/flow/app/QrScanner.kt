package com.flow.app

import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * QR pairing scanner (spec §4.1) — CameraX preview + ML Kit barcode analysis.
 * Calls [onQr] once with the first decoded QR_CODE text, then ignores further frames.
 */
@Composable
fun QrScanner(modifier: Modifier = Modifier, onQr: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }
    val handled = remember { booleanArrayOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, qrAnalyzer(scanner, handled, onQr)) }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
}

@SuppressLint("UnsafeOptInUsageError")
private fun qrAnalyzer(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    handled: BooleanArray,
    onQr: (String) -> Unit
) = ImageAnalysis.Analyzer { proxy: ImageProxy ->
    val media = proxy.image
    if (media == null || handled[0]) {
        proxy.close()
        return@Analyzer
    }
    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            barcodes.firstOrNull { it.valueType == Barcode.TYPE_TEXT || it.rawValue != null }
                ?.rawValue
                ?.let {
                    if (!handled[0]) {
                        handled[0] = true
                        onQr(it)
                    }
                }
        }
        .addOnCompleteListener { proxy.close() }
}
