package dev.kanav.phonon.engine

import android.content.Context

/** The app's few settings, shared by the engine and the settings screen. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** A Hugging Face access token, for the gated Phonon 40m models. Never leaves the phone except to huggingface.co. */
    var hfToken: String
        get() = sp.getString("hf_token", "").orEmpty()
        set(value) = sp.edit().putString("hf_token", value.trim()).apply()

    /** The speaker used for [lang] when a request names no voice. */
    fun preferredVoice(lang: String): String? = sp.getString("voice_$lang", null)
    fun setPreferredVoice(lang: String, speaker: String?) =
        sp.edit().apply { if (speaker == null) remove("voice_$lang") else putString("voice_$lang", speaker) }.apply()

    /** Inference threads. The native thread pool reads it once, so it applies on the next engine start. */
    var threads: Int
        get() = sp.getInt("threads", DEFAULT_THREADS)
        set(value) = sp.edit().putInt("threads", value.coerceIn(1, MAX_THREADS)).commit().let { }

    companion object {
        const val DEFAULT_THREADS = 2
        const val MAX_THREADS = 6
    }
}
