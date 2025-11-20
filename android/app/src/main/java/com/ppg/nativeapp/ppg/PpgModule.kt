package com.shantanu_thakur.ppgnativeapp.ppg

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import android.hardware.camera2.CaptureRequest
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.*

class PpgModule(private val reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
    private val TAG = "PpgModule"
    private val executor = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private val samples = mutableListOf<Pair<Long, Double>>() // (ms, red)

    override fun getName(): String = "PpgModule"

    @ReactMethod
    fun startScan(durationSec: Int = 30) {
        val ctx = reactApplicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            sendEvent("onError", "CAMERA_PERMISSION_MISSING")
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()

                // Analyzer builder with Camera2 interop to attempt capture request options
                val builder = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(640, 480))

                // attempt to lock AE and enable torch via capture request options
                val extender = Camera2Interop.Extender(builder)
                try {
                    extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    extender.setCaptureRequestOption(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                } catch (e: Exception) {
                    Log.w(TAG, "Camera2 interop options not fully supported: ${e.message}")
                }

                analysis = builder.build()
                analysis?.setAnalyzer(executor) { imageProxy ->
                    try {
                        val red = computeAverageRed(imageProxy)
                        val ts = System.currentTimeMillis()
                        synchronized(samples) {
                            samples.add(Pair(ts, red))
                            if (samples.size % 10 == 0) {
                                val elapsed = (ts - (samples.firstOrNull()?.first ?: ts)).toDouble() / 1000.0
                                val pct = min(100, ((elapsed / durationSec) * 100).toInt())
                                sendEvent("onProgress", pct.toString())
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Analyzer error: ${e.message}")
                    } finally {
                        imageProxy.close()
                    }
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                camera = cameraProvider.bindToLifecycle(
                    reactApplicationContext.currentActivity as androidx.lifecycle.LifecycleOwner,
                    cameraSelector,
                    analysis
                )

                // enable torch (best effort)
                try { camera?.cameraControl?.enableTorch(true) } catch (_: Exception) {}

                // schedule stop
                executor.execute {
                    try { Thread.sleep(durationSec * 1000L) } catch (_: InterruptedException) {}
                    stopScanInternal()
                }

                sendEvent("onStarted", "true")
            } catch (e: Exception) {
                sendEvent("onError", "camera_bind_failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    @ReactMethod
    fun stopScan() {
        stopScanInternal()
    }

    private fun stopScanInternal() {
        try { camera?.cameraControl?.enableTorch(false) } catch (_: Exception) {}
        val snap: List<Pair<Long, Double>>
        synchronized(samples) {
            snap = samples.toList()
            samples.clear()
        }

        if (snap.size < 10) {
            sendEvent("onError", "low_samples")
            return
        }

        val result = analyzeSignalAndComputeBpm(snap)
        if (result.bpm <= 0.0 || result.quality < 0.5) {
            sendEvent("onError", "low_signal")
        } else {
            sendEvent("onResult", String.format("%.1f", result.bpm))
        }

        // unbind
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(reactApplicationContext)
            val provider = cameraProviderFuture.get()
            provider.unbindAll()
        } catch (_: Exception) {}
    }

    private fun sendEvent(name: String, value: String?) {
        reactApplicationContext.getJSModule(RCTDeviceEventEmitter::class.java).emit(name, value)
    }

    private fun computeAverageRed(image: ImageProxy): Double {
        val format = image.format
        if (format != ImageFormat.YUV_420_888) {
            val buf = image.planes[0].buffer
            val arr = ByteArray(buf.remaining()); buf.get(arr)
            var s = 0L; for (b in arr) s += (b.toInt() and 0xFF)
            return s.toDouble() / arr.size.toDouble()
        }

        val yPlane = image.planes[0]; val uPlane = image.planes[1]; val vPlane = image.planes[2]
        val yBuf = yPlane.buffer; val uBuf = uPlane.buffer; val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride; val uvRowStride = uPlane.rowStride; val uvPixelStride = uPlane.pixelStride

        val width = image.width; val height = image.height
        val boxSize = min(width, height) / 3
        val startX = width/2 - boxSize/2
        val startY = height/2 - boxSize/2

        val yBytes = ByteArray(yBuf.remaining()); yBuf.get(yBytes)
        val uBytes = ByteArray(uBuf.remaining()); uBuf.get(uBytes)
        val vBytes = ByteArray(vBuf.remaining()); vBuf.get(vBytes)

        var sumR = 0.0; var count = 0
        val step = max(1, boxSize / 30)
        for (row in startY until (startY + boxSize) step step) {
            val yRowStart = row * yRowStride
            val uvRowStart = (row / 2) * uvRowStride
            for (col in startX until (startX + boxSize) step step) {
                val yIndex = yRowStart + col
                val uvCol = col / 2
                val uIndex = uvRowStart + uvCol * uvPixelStride
                val vIndex = uvRowStart + uvCol * uvPixelStride
                if (yIndex >= 0 && yIndex < yBytes.size && uIndex >= 0 && uIndex < uBytes.size && vIndex >= 0 && vIndex < vBytes.size) {
                    val y = (yBytes[yIndex].toInt() and 0xFF)
                    val v = (vBytes[vIndex].toInt() and 0xFF)
                    val r = 1.164 * (y - 16) + 1.596 * (v - 128)
                    sumR += r
                    count++
                }
            }
        }
        return if (count == 0) 0.0 else sumR / count.toDouble()
    }

    private data class AnalysisResult(val bpm: Double, val quality: Double)

    private fun analyzeSignalAndComputeBpm(raw: List<Pair<Long, Double>>): AnalysisResult {
        if (raw.size < 10) return AnalysisResult(-1.0, 0.0)
        val times = raw.map { it.first.toDouble() / 1000.0 }
        val values = raw.map { it.second }
        val dts = mutableListOf<Double>()
        for (i in 1 until times.size) dts.add(times[i] - times[i-1])
        val medianDt = dts.sorted()[dts.size/2]
        val fs = if (medianDt > 0) 1.0/medianDt else 30.0
        val duration = times.last() - times.first()
        val N = max(32, (duration * fs).toInt())
        val t0 = times.first()
        val resampled = DoubleArray(N)
        for (i in 0 until N) {
            val tt = t0 + i.toDouble()/fs
            var j = 0
            while (j < times.size - 1 && times[j+1] < tt) j++
            val tA = times[j]; val tB = times[min(j+1, times.size-1)]
            val vA = values[j]; val vB = values[min(j+1, values.size-1)]
            val v = if (tB - tA > 0) vA + (vB - vA) * (tt - tA) / (tB - tA) else vA
            resampled[i] = v
        }
        val mean = resampled.average()
        for (i in resampled.indices) resampled[i] -= mean
        val filtered = butterworthBandpass(resampled, fs, 0.7, 4.0)
        val totalEnergy = filtered.map { it*it }.sum()
        val smooth = movingAverage(filtered, 3)
        val bandEnergy = smooth.map { it*it }.sum()
        val quality = if (totalEnergy > 0) min(1.0, bandEnergy / totalEnergy) else 0.0
        val ac = autocorrelate(filtered)
        val peakLag = findBestLag(ac, fs, 0.7, 4.0)
        val bpm = if (peakLag > 0) (60.0 / peakLag) else -1.0
        return AnalysisResult(bpm, quality)
    }

    private fun movingAverage(signal: DoubleArray, window: Int): DoubleArray {
            val out = DoubleArray(signal.size)
            val half = window / 2
            for (i in signal.indices) {
                var sum = 0.0
                var cnt = 0
                val start = max(0, i - half)
                val end = min(signal.size - 1, i + half)
                for (j in start..end) {
                    sum += signal[j]
                    cnt++
                }
                out[i] = sum / max(1, cnt)
            }
            return out
        }


    private fun butterworthBandpass(x: DoubleArray, fs: Double, fLow: Double, fHigh: Double): DoubleArray {
        fun designBiquadBandpass(fc: Double, q: Double, fs: Double): DoubleArray {
            val w0 = 2.0 * Math.PI * fc / fs
            val alpha = sin(w0) / (2.0 * q)
            val cosw0 = cos(w0)
            val b0 = alpha
            val b1 = 0.0
            val b2 = -alpha
            val a0 = 1.0 + alpha
            val a1 = -2.0 * cosw0
            val a2 = 1.0 - alpha
            return doubleArrayOf(b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
        }
        val fc = sqrt(fLow * fHigh)
        val q = fc / (fHigh - fLow)
        val coeff = designBiquadBandpass(fc, q, fs)
        var temp = DoubleArray(x.size)
        applyBiquad(x, coeff, temp)
        val out = DoubleArray(x.size)
        applyBiquad(temp, coeff, out)
        return out
    }

    private fun applyBiquad(input: DoubleArray, coeff: DoubleArray, output: DoubleArray) {
        val b0 = coeff[0]; val b1 = coeff[1]; val b2 = coeff[2]; val a1 = coeff[3]; val a2 = coeff[4]
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in input.indices) {
            val x0 = input[i]
            val y0 = b0*x0 + b1*x1 + b2*x2 - a1*y1 - a2*y2
            output[i] = y0
            x2 = x1; x1 = x0
            y2 = y1; y1 = y0
        }
    }

    private fun autocorrelate(x: DoubleArray): DoubleArray {
        val n = x.size
        val out = DoubleArray(n)
        for (lag in 0 until n) {
            var s = 0.0
            for (i in 0 until n - lag) s += x[i] * x[i + lag]
            out[lag] = s / n
        }
        return out
    }

    private fun findBestLag(ac: DoubleArray, fs: Double, fMin: Double, fMax: Double): Double {
            val n = ac.size
            val lagMin = max(1, floor(fs / fMax).toInt())
            val lagMax = min(n - 1, ceil(fs / fMin).toInt())
            var bestLag = -1
            var bestVal = Double.NEGATIVE_INFINITY
            for (lag in lagMin..lagMax) {
                val valLag = ac[lag]
                if (valLag > bestVal) {
                    bestVal = valLag
                    bestLag = lag
                }
            }
            return if (bestLag > 0) bestLag.toDouble() / fs else -1.0
        }

}
