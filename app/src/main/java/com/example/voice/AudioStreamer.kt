package com.example.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * AudioStreamer: Dedicated ultra-low latency PCM16 16kHz audio capture for Gemini Live API.
 * Captures microphone audio and streams it continuously to Gemini Live WebSocket.
 */
class AudioStreamer(
    private val context: Context,
    private val onAudioChunk: (ByteArray) -> Unit,
    private val onAmplitudeChanged: (Float) -> Unit = {},
    private val onVoiceDetected: () -> Unit = {}
) {
    companion object {
        private const val TAG = "AudioStreamer"
        const val SAMPLE_RATE_HZ = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE_SAMPLES = 512 // 32ms chunk @ 16kHz
        private const val CHUNK_SIZE_BYTES = CHUNK_SIZE_SAMPLES * 2 // 16-bit PCM = 2 bytes per sample
        private const val VOICE_THRESHOLD_RMS = 2600.0 // Calibrated threshold for genuine direct user speech (filters out fan, TV, room noise)
        private const val SUSTAINED_VOICE_FRAMES_REQUIRED = 6 // ~192ms of consecutive speech frames required to trigger barge-in
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordingJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var consecutiveVoiceFrames = 0
    private var lastVoiceTriggerTime = 0L
    private var streamStartTime = 0L

    @Volatile
    private var isStreaming = false

    fun isRecording(): Boolean = isStreaming

    @SuppressLint("MissingPermission")
    fun startStreaming(): Boolean {
        if (isStreaming) {
            Log.d(TAG, "AudioStreamer is already streaming.")
            return true
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        val bufferSize = (minBufferSize * 2).coerceAtLeast(CHUNK_SIZE_BYTES * 4)

        val maxAttempts = 4
        for (attempt in 1..maxAttempts) {
            Log.i(TAG, "[MIC_HANDOFF_ATTEMPT] Attempt $attempt/$maxAttempts to initialize AudioRecord (buffer=$bufferSize)")
            var record: AudioRecord? = null
            try {
                // Try VOICE_COMMUNICATION first for hardware AEC (Acoustic Echo Cancellation)
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE_HZ,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            } catch (e: Exception) {
                Log.w(TAG, "[MIC_HANDOFF_ATTEMPT] VOICE_COMMUNICATION unavailable, fallback to MIC: ${e.message}")
            }

            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                try { record?.release() } catch (_: Exception) {}
                try {
                    record = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE_HZ,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "[MIC_HANDOFF_ATTEMPT] MIC creation exception: ${e.message}")
                }
            }

            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                try {
                    audioRecord = record
                    record.startRecording()
                    isStreaming = true
                    streamStartTime = System.currentTimeMillis()
                    consecutiveVoiceFrames = 0
                    Log.i(TAG, "[MIC_HANDOFF_SUCCESS] AudioRecord initialized on attempt $attempt and capturing PCM16 16kHz mono")
                    startCaptureLoop(record)
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, "[MIC_HANDOFF_ATTEMPT] startRecording() failed on attempt $attempt: ${e.message}")
                    try { record.release() } catch (_: Exception) {}
                    audioRecord = null
                }
            } else {
                try { record?.release() } catch (_: Exception) {}
            }

            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(100L)
                } catch (_: InterruptedException) {}
            }
        }

        Log.e(TAG, "[MIC_HANDOFF_FAILED] Exhausted all $maxAttempts attempts to claim microphone.")
        isStreaming = false
        return false
    }

    private fun startCaptureLoop(record: AudioRecord) {
        recordingJob?.cancel()
        recordingJob = scope.launch(Dispatchers.IO) {
            val audioBuffer = ByteArray(CHUNK_SIZE_BYTES)

            while (isActive && isStreaming) {
                try {
                    val bytesRead = record.read(audioBuffer, 0, CHUNK_SIZE_BYTES)
                    if (bytesRead > 0) {
                        val chunk = audioBuffer.copyOf(bytesRead)

                        // Calculate RMS amplitude for UI Orb and Voice Activity
                        val byteBuf = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
                        var sum = 0.0
                        val samples = bytesRead / 2
                        for (i in 0 until samples) {
                            val sample = byteBuf.short
                            sum += sample * sample
                        }

                        if (samples > 0) {
                            val rms = sqrt(sum / samples)
                            val normalizedAmp = (rms / 16384.0).toFloat().coerceIn(0.05f, 1.0f)

                            scope.launch(Dispatchers.Main) {
                                onAmplitudeChanged(normalizedAmp)
                            }

                            // Robust Barge-In: Require sustained direct human speech above noise threshold (~192ms)
                            // Prevents TV, fan, nearby chatter, or transient clicks from interrupting AFFY
                            val now = System.currentTimeMillis()
                            if (rms >= VOICE_THRESHOLD_RMS) {
                                consecutiveVoiceFrames++
                                if (consecutiveVoiceFrames >= SUSTAINED_VOICE_FRAMES_REQUIRED &&
                                    (now - lastVoiceTriggerTime > 1200L) &&
                                    (now - streamStartTime > 400L)
                                ) {
                                    lastVoiceTriggerTime = now
                                    consecutiveVoiceFrames = 0
                                    onVoiceDetected()
                                }
                            } else {
                                consecutiveVoiceFrames = 0
                            }
                        }

                        // Stream raw PCM16 chunk to Gemini Live WebSocket
                        onAudioChunk(chunk)
                    } else if (bytesRead < 0) {
                        Log.w(TAG, "AudioRecord read error code: $bytesRead")
                    }
                } catch (e: Exception) {
                    if (isActive && isStreaming) {
                        Log.w(TAG, "Error in audio capture loop: ${e.message}")
                    }
                }
            }
        }
    }

    fun stopStreaming() {
        if (!isStreaming) return
        isStreaming = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Log.i(TAG, "AudioStreamer stopped and released")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord: ${e.message}")
        }

        scope.launch(Dispatchers.Main) {
            onAmplitudeChanged(0.05f)
        }
    }

    fun release() {
        stopStreaming()
    }
}
