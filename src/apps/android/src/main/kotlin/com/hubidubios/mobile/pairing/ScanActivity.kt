package com.hubidubios.mobile.pairing

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera QR scanner for pairing. CameraX supplies frames; ZXing core decodes the
 * luminance (Y) plane directly — no Play Services, no ML Kit, matching the
 * dependency policy in build.gradle.kts.
 *
 * Returns the decoded text to [PairingActivity] as [EXTRA_PAYLOAD]; that is the
 * same JSON line `hubidubios-sync pair` prints, so both paths converge on
 * [Pairing.parseQr]. Scanning is a convenience over manual entry, never a
 * replacement — pairing still works on a device with no camera.
 */
class ScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAYLOAD = "payload"
    }

    private lateinit var previewView: PreviewView
    private lateinit var analysisExecutor: ExecutorService
    /** One result only: the camera keeps delivering frames after a hit. */
    private val delivered = AtomicBoolean(false)

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            Toast.makeText(
                this,
                "Camera permission denied — paste the payload line instead",
                Toast.LENGTH_LONG,
            ).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        analysisExecutor = Executors.newSingleThreadExecutor()

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val hint = TextView(this).apply {
            text = "Point at the QR from  hubidubios-sync pair"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.BOTTOM }
        }
        setContentView(FrameLayout(this).apply {
            addView(previewView)
            addView(hint)
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrElse {
                Toast.makeText(this, "Camera unavailable: ${it.message}", Toast.LENGTH_LONG).show()
                finish()
                return@addListener
            }
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, QrAnalyzer(::onDecoded)) }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                )
            }.onFailure {
                Toast.makeText(this, "Camera bind failed: ${it.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Called off the main thread by the analyzer. */
    private fun onDecoded(text: String) {
        if (!delivered.compareAndSet(false, true)) return
        runOnUiThread {
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PAYLOAD, text))
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }
}

/**
 * Decodes QR codes from CameraX frames. QR is orientation-independent, so the
 * frame is fed to ZXing as-is without rotating it.
 */
private class QrAnalyzer(private val onHit: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    override fun analyze(image: ImageProxy) {
        try {
            val data = image.luminance() ?: return
            val source = PlanarYUVLuminanceSource(
                data, image.width, image.height,
                0, 0, image.width, image.height,
                false,
            )
            val result = runCatching {
                reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)
            }.getOrNull()
            reader.reset()
            result?.text?.let(onHit)
        } finally {
            // Must always close or CameraX stops delivering frames.
            image.close()
        }
    }
}

/**
 * Copies the Y (luminance) plane into a tightly packed w*h array, honouring
 * rowStride/pixelStride — on many devices the row stride exceeds the width and
 * feeding the raw buffer to ZXing yields a skewed, undecodable image.
 */
private fun ImageProxy.luminance(): ByteArray? {
    val plane = planes.getOrNull(0) ?: return null
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val w = width
    val h = height

    if (rowStride == w && pixelStride == 1) {
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        return data
    }

    val out = ByteArray(w * h)
    val row = ByteArray(rowStride)
    var pos = 0
    for (y in 0 until h) {
        buffer.position(y * rowStride)
        val toRead = minOf(rowStride, buffer.remaining())
        if (toRead <= 0) break
        buffer.get(row, 0, toRead)
        if (pixelStride == 1) {
            System.arraycopy(row, 0, out, pos, minOf(w, toRead))
        } else {
            var x = 0
            while (x < w && x * pixelStride < toRead) {
                out[pos + x] = row[x * pixelStride]
                x++
            }
        }
        pos += w
    }
    return out
}
