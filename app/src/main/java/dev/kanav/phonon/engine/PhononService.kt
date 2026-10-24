package dev.kanav.phonon.engine

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Phonon as a system text-to-speech engine. Android calls [onSynthesizeText] on one thread, one
 * request at a time, and [onStop] from another to cut the current one short.
 *
 * Each checkpoint voice is an Android voice in the locale its catalog entry gives, or in every
 * language the checkpoint speaks when it has none (see [Model.EngineVoice]).
 */
class PhononService : TextToSpeechService() {
    @Volatile private var stopped = false
    @Volatile private var language: String? = null
    @Volatile private var country: String = ""

    override fun onCreate() {
        super.onCreate()
        Model.load(this)
    }

    // Answered from the installed files, without waiting for the model to load.
    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val code = Model.code(lang) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        if (!Model.installed(this)) return TextToSpeech.LANG_MISSING_DATA
        if (code !in Model.languages(this)) return TextToSpeech.LANG_NOT_SUPPORTED
        if (country.isNullOrEmpty()) return TextToSpeech.LANG_AVAILABLE
        // A country is available when a voice speaks it, or when the checkpoint's voices are not
        // tied to countries; once loaded, that is known exactly.
        val model = (Model.state as? Model.State.Ready)?.model ?: return TextToSpeech.LANG_COUNTRY_AVAILABLE
        val hasCountry = model.voicesFor(code).any { it.locale.country.isEmpty() || iso3Country(it.locale) == country.uppercase(Locale.ROOT) || it.locale.country == country.uppercase(Locale.ROOT) }
        return if (hasCountry) TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_AVAILABLE
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        if (result >= TextToSpeech.LANG_AVAILABLE) {
            language = Model.code(lang)
            this.country = if (result >= TextToSpeech.LANG_COUNTRY_AVAILABLE) country.orEmpty() else ""
        }
        return result
    }

    override fun onGetLanguage(): Array<String> {
        val code = language ?: Model.languages(this).firstOrNull() ?: return arrayOf("", "", "")
        return arrayOf(Locale.forLanguageTag(code).isO3Language, country, "")
    }

    override fun onGetVoices(): List<Voice> {
        val model = Model.await(this) ?: return emptyList()
        return model.voices.map {
            Voice(it.name, it.locale, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_LOW, false, emptySet())
        }
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        val model = Model.await(this) ?: return TextToSpeech.ERROR
        return if (voiceName != null && voiceName in model.byName) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }

    override fun onLoadVoice(voiceName: String?): Int = onIsValidVoiceName(voiceName)

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
        val code = Model.code(lang) ?: return null
        val model = Model.await(this) ?: return null
        return defaultVoice(model, code, country)?.name
    }

    override fun onStop() {
        stopped = true
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopped = false
        val model = Model.await(this)
        if (model == null) {
            fail(callback, TextToSpeech.ERROR_NOT_INSTALLED_YET)
            return
        }
        // A voice the request names wins; otherwise its language, in the default voice for it.
        val voice = request.voiceName?.let { model.byName[it] }
            ?: Model.code(request.language)?.let { defaultVoice(model, it, request.country) }
            ?: run {
                fail(callback, TextToSpeech.ERROR_INVALID_REQUEST)
                return
            }
        val text = request.charSequenceText?.toString().orEmpty()
        if (callback.start(SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return
        if (text.isBlank()) {
            callback.done()
            return
        }
        // Android's 100 is normal for both; TalkBack and the system settings go well past it.
        val stretch = TimeStretch(SAMPLE_RATE, request.speechRate / 100f, request.pitch / 100f)
        val max = callback.maxBufferSize
        // Whether Android still takes audio: it stops taking it once this request is stopped.
        fun send(pcm: FloatArray): Boolean {
            if (pcm.isEmpty()) return true
            val bytes = pcm16(pcm)
            var at = 0
            while (at < bytes.size) {
                val n = minOf(max, bytes.size - at)
                if (callback.audioAvailable(bytes, at, n) != TextToSpeech.SUCCESS) return false
                at += n
            }
            return true
        }
        try {
            var open = true
            synchronized(model) {
                if (model.closing) throw IllegalStateException("the model is being replaced")
                model.tts.setLanguage(voice.lang)
                voice.speaker?.let { model.tts.setVoice(it) }
                model.tts.speak(text) { pcm ->
                    open = !stopped && !model.closing && send(stretch.process(pcm))
                    open
                }
            }
            if (open && !stopped) send(stretch.flush())
            callback.done()
        } catch (e: Exception) {
            Log.e(TAG, "synthesis failed", e)
            fail(callback, TextToSpeech.ERROR_SYNTHESIS)
        }
    }

    /**
     * The voice for [lang] when a request names none: the one picked in the app, else the
     * checkpoint's own, else one from the requested country, else the first.
     */
    private fun defaultVoice(model: Model.Loaded, lang: String, country: String?): Model.EngineVoice? {
        val voices = model.voicesFor(lang).ifEmpty { return null }
        Prefs(this).preferredVoice(lang)?.let { pref -> voices.firstOrNull { it.speaker == pref }?.let { return it } }
        voices.firstOrNull { it.speaker == "default" || it.speaker == null }?.let { return it }
        if (!country.isNullOrEmpty()) {
            val c = country.uppercase(Locale.ROOT)
            voices.firstOrNull { it.locale.country == c || iso3Country(it.locale) == c }?.let { return it }
        }
        return voices.first()
    }

    /** Android only tells the app about an error once the request is done. */
    private fun fail(callback: SynthesisCallback, code: Int) {
        callback.error(code)
        callback.done()
    }

    private companion object {
        const val TAG = "PhononService"

        /** What ptts.h hands out: 24 kHz mono. */
        const val SAMPLE_RATE = 24000

        fun iso3Country(locale: Locale): String = try {
            locale.isO3Country
        } catch (e: Exception) {
            ""
        }

        /** Floats in [-1, 1] to little-endian 16-bit PCM. */
        fun pcm16(pcm: FloatArray): ByteArray {
            val out = ByteArray(pcm.size * 2)
            for (i in pcm.indices) {
                val s = (pcm[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
                out[2 * i] = s.toByte()
                out[2 * i + 1] = (s shr 8).toByte()
            }
            return out
        }
    }
}
