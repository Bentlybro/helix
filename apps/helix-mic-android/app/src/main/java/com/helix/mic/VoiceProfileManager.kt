package com.helix.mic

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Simple voice profile manager using acoustic features (no ML).
 * Extracts zero-crossing rate, energy, energy variance, and spectral centroid
 * to build a voice "fingerprint" for basic speaker identification.
 */
class VoiceProfileManager(context: Context) {

    companion object {
        private const val TAG = "VoiceProfileManager"
        private const val PREFS_NAME = "voice_profile_prefs"
        private const val KEY_ENROLLED = "enrolled_features"
        private const val KEY_ENROLLED_COUNT = "enrolled_sample_count"
        private const val SIMILARITY_THRESHOLD = 0.70f
        private const val SAMPLE_RATE = 16000 // BLE audio is 16kHz
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var enrolledProfile: VoiceFeatures? = null
    private var nextSpeakerId = 2
    // Cache of unknown speaker feature vectors mapped to their assigned labels
    private val knownSpeakers = mutableMapOf<String, VoiceFeatures>()

    init {
        loadProfile()
    }

    data class VoiceFeatures(
        val avgZeroCrossingRate: Float,
        val avgEnergy: Float,
        val energyVariance: Float,
        val spectralCentroid: Float
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("zcr", avgZeroCrossingRate.toDouble())
            put("energy", avgEnergy.toDouble())
            put("energyVar", energyVariance.toDouble())
            put("spectralCentroid", spectralCentroid.toDouble())
        }

        companion object {
            fun fromJson(json: JSONObject): VoiceFeatures = VoiceFeatures(
                avgZeroCrossingRate = json.getDouble("zcr").toFloat(),
                avgEnergy = json.getDouble("energy").toFloat(),
                energyVariance = json.getDouble("energyVar").toFloat(),
                spectralCentroid = json.getDouble("spectralCentroid").toFloat()
            )
        }

        fun toVector(): FloatArray = floatArrayOf(
            avgZeroCrossingRate,
            avgEnergy,
            energyVariance,
            spectralCentroid
        )
    }

    val isEnrolled: Boolean get() = enrolledProfile != null

    /**
     * Enroll the user's voice from raw PCM (16kHz 16-bit mono LE).
     * Extracts features from the provided audio and saves as the reference profile.
     */
    fun enroll(pcmData: ByteArray) {
        val samples = pcmBytesToShortArray(pcmData)
        if (samples.size < SAMPLE_RATE) {
            Log.w(TAG, "Not enough audio for enrollment (${samples.size} samples)")
            return
        }

        // Extract features from multiple windows and average them for robustness
        val windowSize = SAMPLE_RATE * 2 // 2-second windows
        val step = SAMPLE_RATE // 1-second step
        val featuresList = mutableListOf<VoiceFeatures>()

        var offset = 0
        while (offset + windowSize <= samples.size) {
            val window = samples.copyOfRange(offset, offset + windowSize)
            val features = extractFeatures(window, SAMPLE_RATE)
            // Skip silent windows
            if (features.avgEnergy > 100f) {
                featuresList.add(features)
            }
            offset += step
        }

        if (featuresList.isEmpty()) {
            Log.w(TAG, "No voiced segments found in enrollment audio")
            return
        }

        // Average all window features
        val avgFeatures = VoiceFeatures(
            avgZeroCrossingRate = featuresList.map { it.avgZeroCrossingRate }.average().toFloat(),
            avgEnergy = featuresList.map { it.avgEnergy }.average().toFloat(),
            energyVariance = featuresList.map { it.energyVariance }.average().toFloat(),
            spectralCentroid = featuresList.map { it.spectralCentroid }.average().toFloat()
        )

        enrolledProfile = avgFeatures
        saveProfile(avgFeatures, featuresList.size)
        Log.d(TAG, "Voice enrolled from ${featuresList.size} windows: $avgFeatures")
    }

    /**
     * Identify speaker from a PCM audio chunk.
     * Returns "You" if matching enrolled profile, or "Speaker N" otherwise.
     */
    fun identifySpeaker(pcmData: ByteArray): String? {
        if (enrolledProfile == null) return null

        val samples = pcmBytesToShortArray(pcmData)
        if (samples.size < 800) return null // too short to analyze

        val features = extractFeatures(samples, SAMPLE_RATE)
        // Skip silence
        if (features.avgEnergy < 100f) return null

        val similarity = cosineSimilarity(features.toVector(), enrolledProfile!!.toVector())
        Log.d(TAG, "Speaker similarity: $similarity")

        return if (similarity >= SIMILARITY_THRESHOLD) {
            "You"
        } else {
            // Check against known other speakers
            for ((label, knownFeatures) in knownSpeakers) {
                val knownSim = cosineSimilarity(features.toVector(), knownFeatures.toVector())
                if (knownSim >= SIMILARITY_THRESHOLD) {
                    return label
                }
            }
            // New unknown speaker
            val label = "Speaker $nextSpeakerId"
            knownSpeakers[label] = features
            nextSpeakerId++
            label
        }
    }

    fun clearProfile() {
        enrolledProfile = null
        knownSpeakers.clear()
        nextSpeakerId = 2
        prefs.edit()
            .remove(KEY_ENROLLED)
            .remove(KEY_ENROLLED_COUNT)
            .apply()
        Log.d(TAG, "Voice profile cleared")
    }

    fun resetSessionSpeakers() {
        knownSpeakers.clear()
        nextSpeakerId = 2
    }

    private fun extractFeatures(pcm: ShortArray, sampleRate: Int): VoiceFeatures {
        val n = pcm.size.toFloat()

        // Zero crossing rate
        var zeroCrossings = 0
        for (i in 1 until pcm.size) {
            if ((pcm[i] >= 0 && pcm[i - 1] < 0) || (pcm[i] < 0 && pcm[i - 1] >= 0)) {
                zeroCrossings++
            }
        }
        val zcr = zeroCrossings.toFloat() / n

        // Energy (RMS-based to avoid overflow)
        var energySum = 0.0
        for (s in pcm) {
            energySum += s.toDouble() * s.toDouble()
        }
        val avgEnergy = (energySum / n).toFloat()

        // Energy variance
        var varianceSum = 0.0
        for (s in pcm) {
            val e = s.toDouble() * s.toDouble()
            val diff = e - avgEnergy
            varianceSum += diff * diff
        }
        val energyVariance = (varianceSum / n).toFloat()

        // Spectral centroid estimate using magnitude of DFT at key frequency bins
        // Simplified: use autocorrelation to estimate dominant frequency
        val spectralCentroid = estimateSpectralCentroid(pcm, sampleRate)

        return VoiceFeatures(zcr, avgEnergy, energyVariance, spectralCentroid)
    }

    /**
     * Estimate spectral centroid using autocorrelation-based pitch detection.
     * Returns a normalized frequency estimate (0.0 - 1.0).
     */
    private fun estimateSpectralCentroid(pcm: ShortArray, sampleRate: Int): Float {
        // Use a simple weighted frequency approach
        // Break signal into short frames, compute ZCR per frame, weight by energy
        val frameSize = sampleRate / 10  // 100ms frames
        var weightedSum = 0.0
        var totalWeight = 0.0

        var offset = 0
        while (offset + frameSize <= pcm.size) {
            var frameEnergy = 0.0
            var frameCrossings = 0

            for (i in offset until offset + frameSize) {
                frameEnergy += pcm[i].toDouble() * pcm[i].toDouble()
                if (i > offset) {
                    if ((pcm[i] >= 0 && pcm[i - 1] < 0) || (pcm[i] < 0 && pcm[i - 1] >= 0)) {
                        frameCrossings++
                    }
                }
            }
            frameEnergy /= frameSize

            // Estimated frequency from ZCR
            val estFreq = frameCrossings.toFloat() * sampleRate / (2f * frameSize)
            weightedSum += estFreq * frameEnergy
            totalWeight += frameEnergy

            offset += frameSize
        }

        return if (totalWeight > 0) {
            // Normalize to 0-1 range (human speech typically 85-300Hz)
            val centroid = (weightedSum / totalWeight).toFloat()
            (centroid / (sampleRate / 2f)).coerceIn(0f, 1f)
        } else {
            0f
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f

        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0

        for (i in a.indices) {
            dotProduct += a[i].toDouble() * b[i].toDouble()
            normA += a[i].toDouble() * a[i].toDouble()
            normB += b[i].toDouble() * b[i].toDouble()
        }

        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0) (dotProduct / denom).toFloat() else 0f
    }

    private fun pcmBytesToShortArray(pcmData: ByteArray): ShortArray {
        val buf = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        val shorts = ShortArray(pcmData.size / 2)
        buf.asShortBuffer().get(shorts)
        return shorts
    }

    private fun saveProfile(features: VoiceFeatures, sampleCount: Int) {
        prefs.edit()
            .putString(KEY_ENROLLED, features.toJson().toString())
            .putInt(KEY_ENROLLED_COUNT, sampleCount)
            .apply()
    }

    private fun loadProfile() {
        val json = prefs.getString(KEY_ENROLLED, null) ?: return
        try {
            enrolledProfile = VoiceFeatures.fromJson(JSONObject(json))
            Log.d(TAG, "Loaded enrolled voice profile: $enrolledProfile")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load voice profile", e)
        }
    }
}
