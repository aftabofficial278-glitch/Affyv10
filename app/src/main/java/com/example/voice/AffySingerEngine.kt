package com.example.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import com.example.agent.AffyDiagnostics
import com.example.data.datastore.AffyPreferences
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

data class SingerMoodProfile(
    val name: String,
    val tempoFeel: String,
    val dynamicArc: String,
    val vocalTexture: String,
    val melodicContour: String,
    val rhythmicStyle: String
)

data class SingerSongMetadata(
    val title: String,
    val lyrics: String,
    val mood: SingerMoodProfile,
    val language: String,
    val voiceName: String
)

sealed class SingerResult {
    data class Success(
        val audioFile: File,
        val metadata: SingerSongMetadata,
        val durationMs: Long
    ) : SingerResult()

    data class Error(val errorMessage: String) : SingerResult()
}

/**
 * AffySingerEngine V1: Autonomous Vocal Performance & A Cappella Singing Engine for AFFY.
 * Uses the existing Gemini audio generation pipeline with native Puck (Male) and Leda (Female) voices.
 * Produces genuine melodic singing, pitch variation, musical cadence, dynamic arcs, and soulful vocal delivery.
 */
class AffySingerEngine(
    private val context: Context,
    private val preferences: AffyPreferences = AffyPreferences(context),
    private val apiService: GeminiApiService = GeminiApiService.create()
) {
    companion object {
        private const val TAG = "AffySingerEngine"
        private const val SINGER_PRIMARY_MODEL = "gemini-2.0-flash"
        private const val SINGER_SECONDARY_MODEL = "gemini-2.5-flash"
        private const val SINGER_TERTIARY_MODEL = "gemini-2.0-flash-exp"
        private const val SYNTHESIS_TIMEOUT_MS = 15000L
        private const val MAX_CACHE_SIZE_BYTES = 25 * 1024 * 1024L // 25 MB max cache

        @Volatile
        private var instance: AffySingerEngine? = null

        fun getInstance(context: Context): AffySingerEngine {
            return instance ?: synchronized(this) {
                instance ?: AffySingerEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheDir = File(context.cacheDir, "affy_singer_cache").apply { mkdirs() }
    private var mediaPlayer: MediaPlayer? = null
    private var visualizerJob: Job? = null

    @Volatile
    private var isCurrentlySinging = false

    @Volatile
    private var activeSessionId = 0L

    /**
     * Synthesizes and plays a genuine singing performance based on the user's prompt or lyric request.
     */
    suspend fun sing(
        userPrompt: String,
        explicitLyrics: String? = null,
        onStatusUpdate: ((status: String, lyrics: String) -> Unit)? = null,
        onAmplitudeChanged: ((Float) -> Unit)? = null,
        onDone: ((isSuccess: Boolean, message: String) -> Unit)? = null
    ): SingerResult = withContext(Dispatchers.IO) {
        val sessionId = ++activeSessionId
        stop()

        val cleanPrompt = userPrompt.trim()
        val effectiveApiKey = preferences.getEffectiveApiKey().trim()

        if (effectiveApiKey.isBlank() || effectiveApiKey.contains("your_gemini_api_key", ignoreCase = true)) {
            val errorMsg = "Gemini API key is missing or invalid. Please check Settings."
            Log.e(TAG, errorMsg)
            withContext(Dispatchers.Main) { onDone?.invoke(false, errorMsg) }
            return@withContext SingerResult.Error(errorMsg)
        }

        // 1. Read persistent voice of truth ("Puck" -> Male, "Leda" -> Female)
        val selectedVoice = preferences.liveVoiceFlow.first().trim()
        val voiceName = if (selectedVoice.equals("Leda", ignoreCase = true) || selectedVoice.equals("female", ignoreCase = true)) {
            "Leda"
        } else {
            "Puck"
        }

        // 2. Detect language, mood, and lyrics with structured musical performance layers
        val language = detectLanguage(cleanPrompt, explicitLyrics)
        val mood = detectMood(cleanPrompt)
        val lyrics = explicitLyrics?.trim()?.takeIf { it.isNotBlank() } ?: composeLyrics(cleanPrompt, language, mood, voiceName)
        val songTitle = extractSongTitle(cleanPrompt, lyrics, language)

        val metadata = SingerSongMetadata(
            title = songTitle,
            lyrics = lyrics,
            mood = mood,
            language = language,
            voiceName = voiceName
        )

        Log.i(TAG, "Starting Singer Mode synthesis: Voice=$voiceName, Lang=$language, Mood=${mood.name}, Title='$songTitle'")
        AffyDiagnostics.logVoiceStage(
            stage = "SINGER_START",
            engine = "GEMINI_SINGER ($voiceName)",
            details = "Composing a cappella song: '$songTitle' [Mood: ${mood.name}, Lang: $language, Tempo: ${mood.tempoFeel}]"
        )

        withContext(Dispatchers.Main) {
            onStatusUpdate?.invoke("Composing ${mood.name} song...", lyrics)
        }

        // 3. Synthesize audio via Gemini Audio Modality with structured musical system instruction
        val cacheKey = hashText("$lyrics-$voiceName-${mood.name}-$language")
        val cachedFile = File(cacheDir, "singer_$cacheKey.wav")

        var audioFileToPlay: File? = null

        if (cachedFile.exists() && cachedFile.length() > 2048) {
            Log.i(TAG, "Reusing cached singing audio: ${cachedFile.name} (${cachedFile.length()} bytes)")
            audioFileToPlay = cachedFile
        } else {
            val synthesizedFile = synthesizeSingingAudio(metadata, effectiveApiKey, cachedFile) {
                sessionId == activeSessionId
            }
            if (synthesizedFile != null && synthesizedFile.exists() && synthesizedFile.length() > 2048) {
                audioFileToPlay = synthesizedFile
            }
        }

        if (sessionId != activeSessionId) {
            return@withContext SingerResult.Error("Singing request cancelled")
        }

        if (audioFileToPlay == null) {
            val failureMsg = "AFFY Singer Engine could not generate melodic vocals at this time."
            Log.e(TAG, failureMsg)
            AffyDiagnostics.logVoiceStage(
                stage = "SINGER_FAILED",
                engine = "GEMINI_SINGER ($voiceName)",
                isSuccess = false,
                failureReason = failureMsg
            )
            withContext(Dispatchers.Main) {
                onDone?.invoke(false, failureMsg)
            }
            return@withContext SingerResult.Error(failureMsg)
        }

        // Clean cache size if needed
        cleanCacheIfNeeded()

        // 4. Play audio performance
        withContext(Dispatchers.Main) {
            onStatusUpdate?.invoke("AFFY is singing: $songTitle", lyrics)
        }

        val played = playSingingAudio(
            audioFile = audioFileToPlay,
            metadata = metadata,
            isActiveSession = { sessionId == activeSessionId },
            onAmplitudeChanged = onAmplitudeChanged,
            onDone = {
                if (sessionId == activeSessionId) {
                    isCurrentlySinging = false
                    AffyDiagnostics.logVoiceStage(
                        stage = "SINGER_COMPLETED",
                        engine = "GEMINI_SINGER ($voiceName)",
                        details = "Singing finished: '$songTitle'"
                    )
                    onDone?.invoke(true, "Song performance completed successfully.")
                }
            }
        )

        if (played) {
            SingerResult.Success(
                audioFile = audioFileToPlay,
                metadata = metadata,
                durationMs = 0L
            )
        } else {
            val playErrMsg = "Failed to initiate audio playback for song."
            withContext(Dispatchers.Main) { onDone?.invoke(false, playErrMsg) }
            SingerResult.Error(playErrMsg)
        }
    }

    private suspend fun synthesizeSingingAudio(
        metadata: SingerSongMetadata,
        apiKey: String,
        outputFile: File,
        isActiveSession: () -> Boolean
    ): File? = withContext(Dispatchers.IO) {
        val mood = metadata.mood

        val voiceSpecificGuide = if (metadata.voiceName.equals("Puck", ignoreCase = true)) {
            """
            VOCALIST REGISTER & STYLE (Puck - Male Vocalist):
            - Register: Natural, comfortable young male baritone/tenor register.
            - Texture: Warm, soulful, conversational in the verse; full-bodied, resonant chest-voice projection during the emotional chorus peak.
            - Dynamics: Soft, intimate verse phrasing -> building chest resonance -> high melodic swell in chorus -> gentle breathy outro resolution.
            """.trimIndent()
        } else {
            """
            VOCALIST REGISTER & STYLE (Leda - Female Vocalist):
            - Register: Natural, comfortable young female mezzo-soprano register.
            - Texture: Crystal clear, delicate, breathy in the verse; luminous, soaring, emotive head-voice and chest-mix projection during the chorus peak.
            - Dynamics: Tender, intimate verse phrasing -> warm rising swell -> clear high melodic crest in chorus -> sweet, gentle outro fade.
            """.trimIndent()
        }

        val systemPrompt = """
            You are AFFY's elite Vocalist Engine performing a complete, deeply expressive a cappella musical piece.
            Your mission is to SING the song with genuine musical melody, clear pitch movement across notes, rhythmic timing, dynamic intensity curves, and profound emotional delivery.

            $voiceSpecificGuide

            PERFORMANCE BLUEPRINT:
            1. MELODY & PITCH: Sing with sustained melodic notes, pitch transitions, and vocal curves. Do NOT speak, do NOT recite poetry, do NOT do monotonic TTS reading.
            2. DYNAMICS ARC: Follow the natural emotional curve: ${mood.dynamicArc}.
            3. TEMPO & RHYTHM: Feel the groove: ${mood.tempoFeel}. Maintain ${mood.rhythmicStyle}.
            4. PHRASING & BREATHS: Respect the breath marks `[breath]` and pause markers `...`. Sustain key vowel endings marked with `~` with a natural, steady vibrato without distortion.
            5. VOCAL TEXTURE: Deliver ${mood.vocalTexture} with authentic ${metadata.language} emotion.
            6. PURE PERFORMANCE: Output pure singing voice only. No talking, no spoken introduction, no commentary, no instrumental beatboxing.
        """.trimIndent()

        val userPrompt = """
            [MUSICAL SCORE & VOCAL PERFORMANCE REQUEST]
            Song Title: ${metadata.title}
            Vocalist: ${metadata.voiceName}
            Mood: ${mood.name}
            Language: ${metadata.language}
            Tempo Feel: ${mood.tempoFeel}
            Dynamic Curve: ${mood.dynamicArc}
            Melodic Contour: ${mood.melodicContour}

            LYRICS & PERFORMANCE MARKUP:
            ${metadata.lyrics}

            Now SING this piece with genuine melodic pitch, musical rhythm, and heartfelt vocal passion!
        """.trimIndent()

        val request = GeminiGenerateRequest(
            contents = listOf(
                GeminiContent(
                    role = "user",
                    parts = listOf(GeminiPart(text = userPrompt))
                )
            ),
            systemInstruction = GeminiContent(
                role = "system",
                parts = listOf(GeminiPart(text = systemPrompt))
            ),
            generationConfig = GeminiGenerationConfig(
                temperature = 0.68f, // Elevated temperature for expressive melodic prosody and vibrato
                topP = 0.95f,
                responseModalities = listOf("AUDIO"),
                speechConfig = GeminiSpeechConfig(
                    voiceConfig = GeminiVoiceConfig(
                        prebuiltVoiceConfig = GeminiPrebuiltVoiceConfig(voiceName = metadata.voiceName)
                    )
                )
            )
        )

        val modelsToTry = listOf(SINGER_PRIMARY_MODEL, SINGER_SECONDARY_MODEL, SINGER_TERTIARY_MODEL)
        for (model in modelsToTry) {
            if (!isActiveSession()) return@withContext null
            try {
                Log.d(TAG, "Requesting singing audio from model: $model for voice: ${metadata.voiceName}")
                val response = withTimeoutOrNull(SYNTHESIS_TIMEOUT_MS) {
                    apiService.generateContent(
                        model = model,
                        apiKey = apiKey,
                        request = request
                    )
                }

                if (response == null) {
                    Log.w(TAG, "Singer synthesis timed out for model $model")
                    continue
                }

                if (!isActiveSession()) return@withContext null

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
                        // Raw 24kHz 16-bit mono PCM -> prepend standard 44-byte WAV header
                        addWavHeader(rawBytes, sampleRate = 24000, channels = 1, bitsPerSample = 16)
                    } else {
                        rawBytes
                    }

                    FileOutputStream(outputFile).use { fos ->
                        fos.write(finalAudioBytes)
                    }

                    Log.i(TAG, "Singer audio successfully generated (${finalAudioBytes.size} bytes) via $model")
                    return@withContext outputFile
                } else {
                    val err = response.error?.message ?: response.getBlockOrFinishReason() ?: "No audio candidate"
                    Log.w(TAG, "Model $model returned no singing audio: $err")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error synthesizing singing audio with $model: ${e.message}")
            }
        }

        null
    }

    private suspend fun playSingingAudio(
        audioFile: File,
        metadata: SingerSongMetadata,
        isActiveSession: () -> Boolean,
        onAmplitudeChanged: ((Float) -> Unit)?,
        onDone: () -> Unit
    ): Boolean = withContext(Dispatchers.Main) {
        if (!isActiveSession()) return@withContext false
        try {
            stop()
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(audioFile.absolutePath)
                prepare()
            }
            mediaPlayer = player
            isCurrentlySinging = true

            player.setOnCompletionListener {
                stopVisualizer()
                isCurrentlySinging = false
                player.release()
                if (mediaPlayer === player) mediaPlayer = null
                onDone()
            }

            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error while playing song: what=$what, extra=$extra")
                stopVisualizer()
                isCurrentlySinging = false
                player.release()
                if (mediaPlayer === player) mediaPlayer = null
                onDone()
                true
            }

            if (!isActiveSession()) {
                player.release()
                mediaPlayer = null
                isCurrentlySinging = false
                return@withContext false
            }

            player.start()
            startVisualizer(onAmplitudeChanged)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play singing audio: ${e.message}", e)
            stopVisualizer()
            isCurrentlySinging = false
            onDone()
            false
        }
    }

    private fun startVisualizer(onAmplitudeChanged: ((Float) -> Unit)?) {
        stopVisualizer()
        visualizerJob = scope.launch(Dispatchers.Main) {
            var phase = 0f
            while (isActive && isCurrentlySinging && mediaPlayer?.isPlaying == true) {
                // Rhythmic amplitude modulation for the floating visualizer orb
                phase += 0.35f
                val simulatedAmp = 0.25f + (0.55f * kotlin.math.abs(kotlin.math.sin(phase.toDouble())).toFloat())
                onAmplitudeChanged?.invoke(simulatedAmp)
                delay(60)
            }
            onAmplitudeChanged?.invoke(0.05f)
        }
    }

    private fun stopVisualizer() {
        visualizerJob?.cancel()
        visualizerJob = null
    }

    fun stop() {
        try {
            stopVisualizer()
            if (isCurrentlySinging || mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping singing player: ${e.message}")
        }
        isCurrentlySinging = false
    }

    fun isSinging(): Boolean = isCurrentlySinging

    private fun detectLanguage(prompt: String, lyrics: String?): String {
        val text = "$prompt ${lyrics ?: ""}".lowercase(Locale.ROOT)
        return when {
            text.contains("urdu") || text.contains("shayeri") || text.contains("ghazal") || text.contains("geet") -> "Urdu"
            text.contains("hindi") -> "Hindi"
            text.contains("english") || text.contains("angrezi") -> "English"
            text.matches(Regex(".*[a-zA-Z].*")) && (text.contains("mein") || text.contains("karo") || text.contains("gao") || text.contains("sunao") || text.contains("hai") || text.contains("dil")) -> "Roman Urdu"
            else -> "Roman Urdu"
        }
    }

    private fun detectMood(prompt: String): SingerMoodProfile {
        val lower = prompt.lowercase(Locale.ROOT)
        return when {
            lower.contains("romantic") || lower.contains("pyar") || lower.contains("mohabbat") || lower.contains("ishq") -> {
                SingerMoodProfile(
                    name = "Romantic & Soulful",
                    tempoFeel = "Slow, intimate ballad (around 68-72 BPM)",
                    dynamicArc = "Soft, breathy opening -> gentle warm build -> rich emotional swell in Chorus -> tender, fading whisper resolution",
                    vocalTexture = "Warm, gentle, intimate, rich with subtle vibrato",
                    melodicContour = "Smooth undulating waves rising gently to a tender high peak on the chorus, resolving softly",
                    rhythmicStyle = "Fluid, legato, expressive rubato phrasing"
                )
            }
            lower.contains("sad") || lower.contains("gham") || lower.contains("dard") || lower.contains("udaas") || lower.contains("emotional") -> {
                SingerMoodProfile(
                    name = "Sad & Emotional",
                    tempoFeel = "Slow, heartfelt adagio (around 60-65 BPM)",
                    dynamicArc = "Subdued, quiet opening -> aching crescendo in chorus -> lingering, poignant fade",
                    vocalTexture = "Vulnerable, breathy, expressive, deep resonance with emotional breaks",
                    melodicContour = "Descending melancholic lines with a yearning upward reach at the emotional peak",
                    rhythmicStyle = "Slow, thoughtful pacing, deliberate pauses between phrases"
                )
            }
            lower.contains("happy") || lower.contains("khushi") || lower.contains("masti") || lower.contains("dance") || lower.contains("cheerful") -> {
                SingerMoodProfile(
                    name = "Happy & Cheerful",
                    tempoFeel = "Upbeat, lively and joyful (around 108-116 BPM)",
                    dynamicArc = "Bright and sunny throughout -> energetic punchy chorus -> joyful cheerful finish",
                    vocalTexture = "Bright, crisp, smiling vocal tone, clear and forward",
                    melodicContour = "Playful bouncing leaps, rising uplifting melodies, bouncy cadence",
                    rhythmicStyle = "Brisk, rhythmic, syncopated and bouncy"
                )
            }
            lower.contains("energetic") || lower.contains("rock") || lower.contains("fast") || lower.contains("josh") -> {
                SingerMoodProfile(
                    name = "Energetic & Upbeat",
                    tempoFeel = "Fast and driving (around 120-128 BPM)",
                    dynamicArc = "High energy start -> driving momentum -> powerful anthem-like chorus -> punchy finale",
                    vocalTexture = "Strong, confident projection, punchy consonants",
                    melodicContour = "Sharp ascending hooks, vibrant rhythmic repetitions",
                    rhythmicStyle = "Tight, driving, syncopated tempo"
                )
            }
            lower.contains("birthday") || lower.contains("salgira") -> {
                SingerMoodProfile(
                    name = "Birthday Celebration",
                    tempoFeel = "Joyful and festive (around 105-112 BPM)",
                    dynamicArc = "Warm friendly intro -> celebratory swelling chorus with sustained high notes -> happy cheering resolution",
                    vocalTexture = "Bright, warm, celebratory, beaming with festive energy",
                    melodicContour = "Triumphant ascending arches, bright melodic calls",
                    rhythmicStyle = "Celebratory, swinging, joyful bounce"
                )
            }
            lower.contains("friendship") || lower.contains("dosti") || lower.contains("dost") -> {
                SingerMoodProfile(
                    name = "Heartwarming Friendship",
                    tempoFeel = "Gentle, uplifting acoustic feel (around 78-84 BPM)",
                    dynamicArc = "Sweet acoustic opening -> uplifting supportive chorus -> heartwarming warm finish",
                    vocalTexture = "Comforting, loyal, warm-hearted, clear tone",
                    melodicContour = "Reassuring gentle slopes, harmonic rising warmth",
                    rhythmicStyle = "Flowing, natural conversational melody"
                )
            }
            lower.contains("cinematic") || lower.contains("filmi") || lower.contains("sufi") -> {
                SingerMoodProfile(
                    name = "Cinematic & Soulful",
                    tempoFeel = "Moderate grand ballad (around 80-85 BPM)",
                    dynamicArc = "Quiet atmospheric intro -> steady rising power -> grand emotional climax in chorus -> resonant controlled resolution",
                    vocalTexture = "Deep chest resonance transitioning to powerful passionate head voice",
                    melodicContour = "Wide dynamic range, sweeping ascending runs to a climactic sustained high note",
                    rhythmicStyle = "Stately, expansive, majestic phrasing"
                )
            }
            else -> {
                SingerMoodProfile(
                    name = "Soulful & Melodic",
                    tempoFeel = "Medium acoustic tempo (around 75-80 BPM)",
                    dynamicArc = "Gentle intro -> warm expressive verse -> swelling melodic chorus -> peaceful resolution",
                    vocalTexture = "Soulful, warm, melodious, natural resonance",
                    melodicContour = "Graceful rising and falling melodies with gentle ornaments",
                    rhythmicStyle = "Smooth, lyrical, expressive delivery"
                )
            }
        }
    }

    private fun composeLyrics(prompt: String, language: String, mood: SingerMoodProfile, voiceName: String): String {
        return when (language) {
            "English" -> {
                when {
                    mood.name.contains("Happy", ignoreCase = true) || mood.name.contains("Energetic", ignoreCase = true) ->
                        """
                        [INTRO]
                        (Brisk joyful hum) Mmm~ yeah~ ♪

                        [VERSE]
                        (Bright & light)
                        Morning sun is shining bright today... [breath]
                        Chasing every single cloud away...

                        [CHORUS]
                        (Uplifting & spirited swell)
                        AFFY sings a joyful song for you~ ♪
                        Lighting up the path in all you do~~!

                        [OUTRO]
                        (Soft smiling resolution)
                        Yeah... smiling all the way~ ♪
                        """.trimIndent()

                    mood.name.contains("Romantic", ignoreCase = true) || mood.name.contains("Soulful", ignoreCase = true) ->
                        """
                        [INTRO/AALAP]
                        (Gentle soft humming) Oooh~ ooh~ ♪

                        [VERSE]
                        (Soft & intimate)
                        Underneath the quiet midnight sky... [breath]
                        Stars are glowing softly way up high...

                        [CHORUS]
                        (Warm emotional swell, sustained melodic peak)
                        In this precious moment calm and sweet~ ♪
                        Two hearts dance to love's eternal beat~~~!

                        [OUTRO]
                        (Fading soft whisper)
                        Forever... in the quiet night... ♪
                        """.trimIndent()

                    mood.name.contains("Sad", ignoreCase = true) ->
                        """
                        [INTRO]
                        (Aching slow hum) Mmm... ooh... ♪

                        [VERSE]
                        (Vulnerable, quiet breath)
                        Shadows fall upon a silent room... [breath]
                        Hope is waiting gently through the gloom...

                        [CHORUS]
                        (Yearning emotional climax)
                        After every sorrow comes the light~~~ ♪
                        Morning breaks beyond the darkest night...

                        [OUTRO]
                        (Lingering tender fade)
                        Hold on... morning is in sight... ♪
                        """.trimIndent()

                    else ->
                        """
                        [INTRO]
                        (Warm melodic hum) Aaah~ mmm~ ♪

                        [VERSE]
                        (Gentle & clear)
                        Every single moment feels brand new... [breath]
                        Brightest dreams are shining just for you...

                        [CHORUS]
                        (Full warm vocal swell)
                        AFFY's voice is drifting through the air~ ♪
                        Bringing peace and loving care~~~!

                        [OUTRO]
                        (Soft peaceful finish)
                        Always right here with you... ♪
                        """.trimIndent()
                }
            }
            "Hindi", "Urdu", "Roman Urdu" -> {
                when {
                    mood.name.contains("Romantic", ignoreCase = true) ->
                        """
                        [INTRO/AALAP]
                        (Dheemi aawaz mein aalap) Aaa~ aaa~ ooo~ ♪

                        [VERSE]
                        (Pyaar bhari, dheemi aur meethi aawaz)
                        Raat ki khamoshi mein dil geet sunaye... [breath]
                        Chaand ki roshni mein ek sapna jagaye...

                        [CHORUS]
                        (Jazbaati urooj, unchi aur suhani sur)
                        Aapki dosti ka pyara sa ehsaas~ ♪
                        Har lamha ban gaya hai kitna khaas~~~!

                        [OUTRO]
                        (Halka sa dheema ikhtetaam)
                        Kitna... pyara sa ehsaas... ♪
                        """.trimIndent()

                    mood.name.contains("Happy", ignoreCase = true) || mood.name.contains("Energetic", ignoreCase = true) ->
                        """
                        [INTRO]
                        (Joshili khushi bhari dhun) Hey~ haa~ mmm~ ♪

                        [VERSE]
                        (Khilkhilati, chamakdar aawaz)
                        Zindagi hai pyari, muskurate jao... [breath]
                        Har subah naya ek geet gungunao...

                        [CHORUS]
                        (Buland suron mein khushi ka naghma)
                        AFFY hai aapke sath har ek mod par~ ♪
                        Khushiyon ke rang bikhrenge har taraf~~~!

                        [OUTRO]
                        (Muskurata hua ikhtetaam)
                        Muskurate jao... hamesha... ♪
                        """.trimIndent()

                    mood.name.contains("Sad", ignoreCase = true) ->
                        """
                        [INTRO/AALAP]
                        (Dard bhari dheemi sada) Haaye... ooo~ aaa... ♪

                        [VERSE]
                        (Bheegi aawaz, dheema lehja)
                        Waqt ke saaye dhalte hain dheere dheere... [breath]
                        Umeed ke diye jalte hain dheere dheere...

                        [CHORUS]
                        (Jazbaat ki gehrayi aur uncha sur)
                        Dukh ki har raat ke baad aati hai subah~~~ ♪
                        Muskurao ke sath hai har lamha naya~~~!

                        [OUTRO]
                        (Aahista khamosh hota hua khatam)
                        Har lamha... naya... ♪
                        """.trimIndent()

                    mood.name.contains("Birthday", ignoreCase = true) ->
                        """
                        [INTRO]
                        (Jashan aur khushi bhari aawaz) Haa~ la la la~ ♪

                        [VERSE]
                        (Meethi mubarakbaad)
                        Khushiyon bhara ye din aapko mubarak ho... [breath]
                        Har pal mein hasi aur roshni ka rang ho...

                        [CHORUS]
                        (Buland khushi aur jashan ka geet)
                        Salgira ka ye din laye sau sau baharein~ ♪
                        AFFY ki dua hai chamko jaise sitare~~~!

                        [OUTRO]
                        (Dua bhari meethi aawaz)
                        Mubarak ho... salgira... ♪
                        """.trimIndent()

                    mood.name.contains("Friendship", ignoreCase = true) ->
                        """
                        [INTRO/AALAP]
                        (Pyar bhara aalap) Mmm~ aaa~ ♪

                        [VERSE]
                        (Apniyat bhari aawaz)
                        Safar mein jab bhi koi mushkil aaye... [breath]
                        Sachhe dost ka saya sath nibhaye...

                        [CHORUS]
                        (Dilkash aur purkhaloos sur)
                        Ye dosti ka rishta hai sabse haseen~ ♪
                        AFFY hai sath aapke, rakho yaqeen~~~!

                        [OUTRO]
                        (Garmjosh ikhtetaam)
                        Sath hamesha... dosti ka... ♪
                        """.trimIndent()

                    else ->
                        """
                        [INTRO/AALAP]
                        (Sufiyana dheema aalap) Aa~aa~aa~ ooo~ ♪

                        [VERSE]
                        (Ruhani aur dilkash lehja)
                        Dil ki baatein hain geeton mein dhali hui... [breath]
                        Subah ki hawa hai phoolon mein pali hui...

                        [CHORUS]
                        (Surila aur pur-asar urooj)
                        AFFY gaata hai pyara sa taraana~ ♪
                        Khushiyon se bhar jaye saara zamaana~~~!

                        [OUTRO]
                        (Shant aur sukoon bhara khatam)
                        Saara... zamaana... ♪
                        """.trimIndent()
                }
            }
            else ->
                """
                [INTRO/AALAP]
                (Dheema aalap) Aa~aa~aa~ ♪

                [VERSE]
                (Meetha lehja)
                Raat ki khamoshi mein dil geet sunaye... [breath]
                Chaand ke saaye mein AFFY muskuraaye...

                [CHORUS]
                (Dilkash buland sur)
                Zindagi ke har safar mein roshni ho sath~ ♪
                Dil se nikalti hai ye pyari si baat~~~!

                [OUTRO]
                (Dheemi meethi aawaz)
                Pyari si baat... ♪
                """.trimIndent()
        }
    }

    private fun extractSongTitle(prompt: String, lyrics: String, language: String): String {
        // Extract title from lyrics or first singing line
        val titleLine = lyrics.lines().firstOrNull {
            it.isNotBlank() && !it.startsWith("[") && !it.startsWith("(")
        }?.trim()?.replace(Regex("[♪~.]"), "")?.trim() ?: "AFFY Melody"

        return if (titleLine.length > 30) titleLine.take(28) + "..." else titleLine
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
        header[32] = ((channels * bitsPerSample) / 8).toByte(); header[33] = 0
        header[34] = bitsPerSample.toByte(); header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        val wav = ByteArray(44 + pcmData.size)
        System.arraycopy(header, 0, wav, 0, 44)
        System.arraycopy(pcmData, 0, wav, 44, pcmData.size)
        return wav
    }

    private fun cleanCacheIfNeeded() {
        try {
            val files = cacheDir.listFiles() ?: return
            var totalSize = files.sumOf { it.length() }
            if (totalSize > MAX_CACHE_SIZE_BYTES) {
                // Delete oldest files first
                files.sortBy { it.lastModified() }
                for (f in files) {
                    if (totalSize <= MAX_CACHE_SIZE_BYTES / 2) break
                    val len = f.length()
                    if (f.delete()) {
                        totalSize -= len
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cache cleanup notice: ${e.message}")
        }
    }
}
