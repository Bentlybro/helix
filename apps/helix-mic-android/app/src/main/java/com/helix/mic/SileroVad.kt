package com.helix.mic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD v5 wrapper via ONNX Runtime.
 *
 * Expects a 16kHz mono signal. The model processes frames of 512 samples (32 ms)
 * and returns a speech probability. State is carried across calls.
 */
class SileroVad(private val context: Context) {

    companion object {
        private const val TAG = "SileroVad"
        private const val ASSET_NAME = "silero_vad.onnx"
        const val FRAME_SIZE_16K = 512
        const val CONTEXT_SIZE_16K = 64
        const val SAMPLE_RATE = 16000
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    // Discovered at init from model metadata
    private var inputAudioName = "input"
    private var inputStateName = "state"
    private var inputSrName = "sr"
    private var outputProbName = "output"
    private var outputStateName = "stateN"

    private val stateShape = longArrayOf(2, 1, 128)
    private var stateBuf: FloatArray = FloatArray(2 * 1 * 128)
    private var contextBuf: FloatArray = FloatArray(CONTEXT_SIZE_16K)
    // Scratch buffer reused each call: context (64) + frame (512) = 576
    private val combined: FloatArray = FloatArray(CONTEXT_SIZE_16K + FRAME_SIZE_16K)
    private var callCount = 0

    fun init(): Boolean {
        if (session != null) return true
        return try {
            val modelPath = copyAssetIfNeeded()
            env = OrtEnvironment.getEnvironment()
            val s = env!!.createSession(modelPath, OrtSession.SessionOptions())
            session = s

            val inputNames = s.inputNames.toList()
            val outputNames = s.outputNames.toList()
            Log.d(TAG, "Silero inputs=$inputNames outputs=$outputNames")

            // Bind by substring so the code adapts to v4/v5 naming quirks
            inputAudioName = inputNames.firstOrNull { it.contains("input", ignoreCase = true) } ?: inputNames[0]
            inputStateName = inputNames.firstOrNull { it.contains("state", ignoreCase = true) } ?: "state"
            inputSrName    = inputNames.firstOrNull { it.contains("sr", ignoreCase = true) } ?: "sr"

            outputProbName  = outputNames.firstOrNull { !it.contains("state", ignoreCase = true) } ?: outputNames[0]
            outputStateName = outputNames.firstOrNull { it.contains("state", ignoreCase = true) } ?: outputNames.getOrNull(1) ?: outputNames[0]

            Log.d(TAG, "bound audio='$inputAudioName' state='$inputStateName' sr='$inputSrName' prob='$outputProbName' stateOut='$outputStateName'")

            resetState()
            Log.d(TAG, "Silero VAD loaded from $modelPath")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load Silero VAD", t)
            false
        }
    }

    fun resetState() {
        stateBuf.fill(0f)
        contextBuf.fill(0f)
    }

    /**
     * Returns speech probability [0.0, 1.0] for a 512-sample frame, or -1f on error.
     */
    fun process(frame: FloatArray): Float {
        val s = session ?: return -1f
        val e = env ?: return -1f
        if (frame.size != FRAME_SIZE_16K) return -1f

        // Build the combined buffer: previous context + this frame
        System.arraycopy(contextBuf, 0, combined, 0, CONTEXT_SIZE_16K)
        System.arraycopy(frame, 0, combined, CONTEXT_SIZE_16K, FRAME_SIZE_16K)

        var audioTensor: OnnxTensor? = null
        var stateTensor: OnnxTensor? = null
        var srTensor: OnnxTensor? = null
        try {
            audioTensor = OnnxTensor.createTensor(
                e,
                FloatBuffer.wrap(combined),
                longArrayOf(1, combined.size.toLong())
            )
            stateTensor = OnnxTensor.createTensor(
                e,
                FloatBuffer.wrap(stateBuf),
                stateShape
            )
            // sr is a scalar int64 tensor (shape ())
            srTensor = OnnxTensor.createTensor(
                e,
                LongBuffer.wrap(longArrayOf(SAMPLE_RATE.toLong())),
                longArrayOf()
            )

            val inputs = mapOf(
                inputAudioName to audioTensor,
                inputStateName to stateTensor,
                inputSrName to srTensor
            )

            s.run(inputs).use { result ->
                val probTensor = result.get(outputProbName).orElse(null)
                    ?: return -1f
                @Suppress("UNCHECKED_CAST")
                val probArr = probTensor.value as Array<FloatArray>
                val prob = probArr[0][0]

                val stateOut = result.get(outputStateName).orElse(null)
                if (stateOut != null) {
                    @Suppress("UNCHECKED_CAST")
                    val st = stateOut.value as Array<Array<FloatArray>>
                    var idx = 0
                    for (i in 0 until 2) for (j in 0 until 128) {
                        stateBuf[idx++] = st[i][0][j]
                    }
                }

                // Save last CONTEXT_SIZE samples of the frame as context for next call
                System.arraycopy(frame, FRAME_SIZE_16K - CONTEXT_SIZE_16K, contextBuf, 0, CONTEXT_SIZE_16K)

                if (callCount < 3 || callCount % 50 == 0) {
                    val maxAbs = frame.maxOf { if (it < 0) -it else it }
                    Log.d(TAG, "call#$callCount prob=${"%.4f".format(prob)} frameMaxAbs=${"%.4f".format(maxAbs)} stateSum=${"%.4f".format(stateBuf.sum())}")
                }
                callCount++
                return prob
            }
        } catch (t: Throwable) {
            Log.e(TAG, "VAD inference failed", t)
            return -1f
        } finally {
            try { audioTensor?.close() } catch (_: Throwable) {}
            try { stateTensor?.close() } catch (_: Throwable) {}
            try { srTensor?.close() } catch (_: Throwable) {}
        }
    }

    fun close() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
    }

    private fun copyAssetIfNeeded(): String {
        val out = File(context.filesDir, ASSET_NAME)
        if (out.exists() && out.length() > 0) return out.absolutePath
        context.assets.open(ASSET_NAME).use { input ->
            out.outputStream().use { os -> input.copyTo(os) }
        }
        return out.absolutePath
    }
}
