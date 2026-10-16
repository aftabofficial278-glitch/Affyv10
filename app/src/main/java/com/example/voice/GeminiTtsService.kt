package com.example.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import com.example.agent.AffyDiagnostics
import com.example.agent.AffyEmotion
import com.example.agent.AffyEmotionEngine
import com.example.agent.EmotionalVoiceProfile
import com.example.data.remote.GeminiApiService
import com.example.data.remote.GeminiContent
import com.example.data.remote.GeminiGenerateRequest
import com.example.data.remote.GeminiGenerationConfig
import com.example.data.remote.GeminiPart
import com.example.data.remote.GeminiPrebuiltVoiceConfig
import com.example.data.remote.GeminiSpeechConfig
import com.example.data.remote.GeminiVoiceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class GeminiTtsService(
    private val context: Context,
    private val apiService: GeminiApiService = GeminiApiService.create(),
    private val onSpeakingStateChanged: (isSpeaking: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "GeminiTTS"
        private const val PRIMARY_TTS_MODEL = "gemini-2.0-flash"
        private const val SECONDARY_TTS_MODEL = "gemini-2.5-flash"
        private const val TERTIARY_TTS_MODEL = "gemini-2.0-flash-exp"
        const val VOICE_PUCK_YOUNG_MALE = "Puck"
        const val VOICE_LEDA_YOUNG_FEMALE = "Leda"
        private const val SYNTHESIS_NETWORK_TIMEOUT_MS = 4000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mediaPlayer: MediaPlayer? = null
    private val cacheDir = File(context.cacheDir, "affy_tts_cache").apply { mkdirs() }

    @Volatile
    private var isPlaying = false

    suspend fun synthesizeAndPlay(
        text: String,
        apiKey: String,
        voiceName: String = VOICE_PUCK_YOUNG_MALE,
        emotion: AffyEmotion = AffyEmotionEngine.getInstance().currentAffyEmotion.value,
        isActiveSession: () -> Boolean = { true },
        onDone: (() -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        if (cleanText.isBlank() || apiKey.isBlank() || !isActiveSession()) {
            return@withContext false
        }

        val startTime = System.currentTimeMillis()
        val profile = AffyEmotionEngine.getInstance().getEmotionalVoiceProfile(emotion, voiceName)

        // Soften punctuation and pauses for melancholic/sad/caring delivery
        val spokenText = when (emotion) {
            AffyEmotion.SAD_SOFT, AffyEmotion.CARING_CONCERNED -> cleanText.replace("!", ".").replace("  ", " ")
            else -> cleanText
        }

        val cacheKey = hashText("${spokenText}_${voiceName}_${emotion.name}")
        val cachedFile = File(cacheDir, "$cacheKey.wav")

        val voiceLabel = if (voiceName.equals(VOICE_LEDA_YOUNG_FEMALE, ignoreCase = true)) "AFFY Female Voice" else "AFFY Male Voice (17yo)"

        if (cachedFile.exists() && cachedFile.length() > 100) {
            if (!isActiveSession()) return@withContext false
            Log.d(TAG, "Playing from TTS audio cache: ${cachedFile.name} (Emotion: ${emotion.name})")
            AffyDiagnostics.updateVoiceProvider("GEMINI_TTS ($voiceLabel) [Cached ${profile.emotionalTag}]")
            val played = playAudioFile(cachedFile, profile, isActiveSession, onDone)
            return@withContext played
        }

        val request = GeminiGenerateRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(GeminiPart(text = spokenText))
                )
            ),
            systemInstruction = GeminiContent(
                parts = listOf(GeminiPart(text = "You are synthesizing spoken audio. Voice tone: ${profile.toneInstruction}. Deliver the Urdu/Hindi/English speech with natural emotion, appropriate pacing, breath, and human intonation."))
            ),
            generationConfig = GeminiGenerationConfig(
                temperature = 0.5f,
                responseMimeType = null,
                responseModalities = listOf("AUDIO"),
                speechConfig = GeminiSpeechConfig(
                    voiceConfig = GeminiVoiceConfig(
                        prebuiltVoiceConfig = GeminiPrebuiltVoiceConfig(voiceName = voiceName)
                    )
                )
            )
        )

        if (!isActiveSession()) return@withContext false

        val ttsModels = listOf(PRIMARY_TTS_MODEL, SECONDARY_TTS_MODEL, TERTIARY_TTS_MODEL)
        for (modelToUse in ttsModels) {
            if (!isActiveSession()) return@withContext false
            try {
                Log.d(TAG, "Requesting Gemini TTS audio from model: $modelToUse for: '$spokenText' [Emotion: ${emotion.name}]")
                val response = withTimeoutOrNull(SYNTHESIS_NETWORK_TIMEOUT_MS) {
                    apiService.generateContent(
                        model = modelToUse,
                        apiKey = apiKey,
                        request = request
                    )
                }

                if (response == null) {
                    Log.w(TAG, "Gemini TTS timeout (${SYNTHESIS_NETWORK_TIMEOUT_MS}ms) for model $modelToUse")
                    continue
                }

                if (!isActiveSession()) return@withContext false

                val inlineData = response.candidates
                    ?.firstOrNull()
                    ?.content
                    ?.parts
                    ?.firstOrNull { it.inlineData != null }
                    ?.inlineData

                if (inlineData != null && inlineData.data.isNotBlank()) {
                    val rawBytes = Base64.decode(inlineData.data, Base64.DEFAULT)
                    val mimeType = inlineData.mimeType.lowercase()

                    val finalAudioBytes = if (mimeType.contains("pcm") || (!mimeType.contains("mp3") && !mimeType.contains("wav") && !mimeType.contains("ogg"))) {
                        // Raw 24kHz 16-bit mono PCM -> prepend WAV header
                        addWavHeader(rawBytes, sampleRate = 24000, channels = 1, bitsPerSample = 16)
                    } else {
                        rawBytes
                    }

                    FileOutputStream(cachedFile).use { fos ->
                        fos.write(finalAudioBytes)
                    }

                    val latency = System.currentTimeMillis() - startTime
                    Log.i(TAG, "Gemini TTS audio synthesized successfully in ${latency}ms using $modelToUse ($voiceName) [${profile.emotionalTag}]")
                    AffyDiagnostics.updateVoiceProvider("GEMINI_TTS ($voiceLabel) [${profile.emotionalTag}]")

                    if (!isActiveSession()) return@withContext false

                    val played = playAudioFile(cachedFile, profile, isActiveSession, onDone)
                    return@withContext played
                } else {
                    val err = response.error?.message ?: "No audio inlineData in candidate"
                    Log.w(TAG, "Gemini TTS $modelToUse returned no audio: $err")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error in Gemini TTS generation ($modelToUse): ${e.message}")
            }
        }

        return@withContext false
    }

    private suspend fun playAudioFile(
        audioFile: File,
        profile: EmotionalVoiceProfile,
        isActiveSession: () -> Boolean = { true },
        onDone: (() -> Unit)?
    ): Boolean = withContext(Dispatchers.Main) {
        if (!isActiveSession()) return@withContext false
        try {
            stop()
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(audioFile.absolutePath)
                prepare()
            }

            // Apply dynamic emotional acoustic modulation (pitch + speed + volume)
            try {
                val params = player.playbackParams
                params.pitch = profile.pitchMultiplier
                params.speed = profile.speedMultiplier
                player.playbackParams = params
                Log.d(TAG, "Applied emotional playback params [pitch=${profile.pitchMultiplier}, speed=${profile.speedMultiplier}, tag=${profile.emotionalTag}]")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply emotional playbackParams: ${e.message}")
            }
            player.setVolume(profile.volumeMultiplier, profile.volumeMultiplier)

            mediaPlayer = player

            isPlaying = true
            onSpeakingStateChanged(true)

            player.setOnCompletionListener {
                isPlaying = false
                onSpeakingStateChanged(false)
                player.release()
                if (mediaPlayer === player) mediaPlayer = null
                onDone?.invoke()
            }

            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                isPlaying = false
                onSpeakingStateChanged(false)
                player.release()
                if (mediaPlayer === player) mediaPlayer = null
                onDone?.invoke()
                true
            }

            if (!isActiveSession()) {
                player.release()
                mediaPlayer = null
                isPlaying = false
                onSpeakingStateChanged(false)
                return@withContext false
            }

            player.start()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio file: ${e.message}", e)
            isPlaying = false
            onSpeakingStateChanged(false)
            onDone?.invoke()
            false
        }
    }

    fun stop() {
        try {
            if (isPlaying || mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping media player: ${e.message}")
        }
        isPlaying = false
        onSpeakingStateChanged(false)
    }

    private fun hashText(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(20)
    }

    private fun addWavHeader(pcmData: ByteArray, sampleRate: Int = 24000, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val totalAudioLen = pcmData.size
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 // 16 for PCM
        header[20] = 1; header[21] = 0 // format = 1 (PCM)
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = ((channels * bitsPerSample) / 8).toByte(); header[33] = 0 // block align
        header[34] = bitsPerSample.toByte(); header[35] = 0 // bits per sample
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()
        return header + pcmData
    }
}
