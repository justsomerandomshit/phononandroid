package dev.kanav.phonon.engine

import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kanav.phonon.ffi.PhononTTS
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * The installed checkpoint and the model loaded from it, shared by the engine service and the
 * settings screen, which run in one process. The model is loaded once, off the main thread, and
 * kept until the checkpoint or its voices change.
 */
object Model {
    private const val TAG = "PhononModel"

    /** The languages the runtime normalizes text for. */
    val SUPPORTED = listOf("en", "fr", "de", "es", "pt")

    /** One voice as Android sees it: a speaker of the checkpoint in one locale. */
    class EngineVoice(
        /** Android's voice name, unique within the engine. */
        val name: String,
        /** One of [SUPPORTED]: how the text is normalized. */
        val lang: String,
        val locale: Locale,
        /** The checkpoint's voice name, or null for a checkpoint without voices. */
        val speaker: String?,
        val label: String,
        val info: VoiceInfo?,
    )

    /** A loaded model. Calls into [tts] must hold the object's lock: the library takes one at a time. */
    class Loaded(val tts: PhononTTS, val speakers: List<String>, val languages: List<String>, meta: Map<String, VoiceInfo>) {
        /** Set when the model is about to be freed, so a request in progress stops early. */
        @Volatile var closing = false

        val voices: List<EngineVoice> = buildVoices(speakers, languages, meta)
        val byName = voices.associateBy { it.name }

        fun voicesFor(lang: String) = voices.filter { it.lang == lang }
    }

    sealed class State {
        object NotInstalled : State()
        object Loading : State()
        class Ready(val model: Loaded) : State()
        class Failed(val message: String) : State()
    }

    /** Snapshot state, so the settings screen recomposes as it changes. */
    var state: State by mutableStateOf(State.NotInstalled)
        private set

    private val loader = Executors.newSingleThreadExecutor()
    private var pending: Future<*>? = null

    fun dir(context: Context) = File(context.filesDir, "model")
    fun voicesDir(context: Context) = File(dir(context), "voices")
    private fun keptVoices(context: Context) = File(context.filesDir, "voices.kept")

    /** Whether a checkpoint folder is in place. It may still fail to load. */
    fun installed(context: Context): Boolean {
        val dir = dir(context)
        return File(dir, "config.json").isFile && File(dir, "tokenizer.json").isFile &&
            (File(dir, "model.q8.gguf").isFile || File(dir, "model.safetensors").isFile)
    }

    /** The catalog model installed, or null for none or an imported folder. */
    fun installedSpec(context: Context): ModelSpec? {
        val file = File(dir(context), INSTALLED)
        if (!installed(context) || !file.isFile) return null
        return try {
            Catalog.model(JSONObject(file.readText()).optString("id"))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The languages the installed checkpoint speaks, read from its config without loading it.
     * A config that lists none is taken to speak them all.
     */
    fun languages(context: Context): List<String> {
        if (!installed(context)) return emptyList()
        val listed = try {
            JSONObject(File(dir(context), "config.json").readText()).optJSONArray("languages")
        } catch (e: Exception) {
            null
        }
        val names = (0 until (listed?.length() ?: 0)).map { listed!!.getString(it) }
        return if (names.isEmpty()) SUPPORTED else SUPPORTED.filter { it in names }
    }

    /** Start loading the installed checkpoint, unless it is loaded or loading already. */
    @Synchronized
    fun load(context: Context): Future<*>? {
        if (state is State.Ready || pending?.isDone == false) return pending
        if (!installed(context)) {
            state = State.NotInstalled
            return null
        }
        val app = context.applicationContext
        // The last load crashed the app: wait for the user to try again rather than crash again.
        Diagnostics.blockedLoad(app)?.let {
            state = State.Failed(it)
            return null
        }
        val dir = dir(app).path
        val languages = languages(app)
        val threads = Prefs(app).threads
        state = State.Loading
        pending = loader.submit {
            val started = SystemClock.elapsedRealtime()
            Diagnostics.loadStarted(app, threads)
            state = try {
                // Before the first load: the library's thread pool reads it once. One thread per
                // core is too many on phones that mix fast and slow cores.
                Os.setenv("RAYON_NUM_THREADS", threads.toString(), true)
                // Text is normalized per request with setLanguage; this is only the first one.
                val tts = PhononTTS(dir, languages.firstOrNull() ?: "none")
                State.Ready(Loaded(tts, tts.voices, languages, Voices.installedMeta(app))).also {
                    Diagnostics.loadFinished(app, null, SystemClock.elapsedRealtime() - started)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "loading $dir failed", t)
                Diagnostics.loadFinished(app, t.toString(), SystemClock.elapsedRealtime() - started)
                State.Failed(t.message ?: t.toString())
            }
        }
        return pending
    }

    /** Load again after a failed or crashed load, on the user's say-so. */
    fun retry(context: Context) {
        Diagnostics.unblock(context)
        load(context)
    }

    /** The loaded model, waiting for a load in progress. Null when none could be loaded. */
    fun await(context: Context): Loaded? {
        try {
            load(context)?.get(LOAD_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "waiting for the model failed", e)
        }
        return (state as? State.Ready)?.model
    }

    /** Free the model and load it again, to pick up added or removed voices. */
    fun reload(context: Context) {
        synchronized(this) { unload() }
        load(context)
    }

    /**
     * Replace the installed checkpoint with what [fill] writes into an empty folder, then load
     * it. [fill] runs on the calling thread, which must not be the main one. Downloaded voices
     * carry over to the new checkpoint.
     */
    fun install(context: Context, specId: String?, fill: (File) -> Unit) {
        // A new checkpoint deserves a try even if the last one crashed.
        Diagnostics.unblock(context)
        val staging = File(context.filesDir, "model.staging")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            fill(staging)
            for (name in listOf("config.json", "tokenizer.json")) {
                require(File(staging, name).isFile) { "the folder has no $name" }
            }
            require(File(staging, "model.q8.gguf").isFile || File(staging, "model.safetensors").isFile) {
                "the folder has no model.q8.gguf or model.safetensors"
            }
            File(staging, INSTALLED).writeText(JSONObject().put("id", specId ?: "").toString())
            // Held across the swap, so a load() from the service cannot start on the old folder
            // between unloading it and replacing it.
            synchronized(this) {
                unload()
                val newVoices = File(staging, "voices")
                if (!newVoices.exists()) {
                    val old = voicesDir(context).takeIf { it.isDirectory } ?: keptVoices(context)
                    if (old.isDirectory) old.renameTo(newVoices)
                }
                keptVoices(context).deleteRecursively()
                dir(context).deleteRecursively()
                check(staging.renameTo(dir(context))) { "could not move the checkpoint into place" }
            }
        } finally {
            staging.deleteRecursively()
        }
        load(context)
    }

    /** Delete the installed checkpoint, keeping its downloaded voices for the next one. */
    fun uninstall(context: Context) {
        synchronized(this) {
            unload()
            val voices = voicesDir(context)
            if (voices.isDirectory) {
                keptVoices(context).deleteRecursively()
                voices.renameTo(keptVoices(context))
            }
            dir(context).deleteRecursively()
        }
    }

    /** Free the loaded model, once whatever is speaking with it has stopped. */
    @Synchronized
    private fun unload() {
        try {
            pending?.get()
        } catch (e: Exception) {
            // A failed load leaves nothing to free.
        }
        (state as? State.Ready)?.model?.let {
            it.closing = true
            synchronized(it) { it.tts.close() }
        }
        state = State.NotInstalled
        pending = null
    }

    /** A language as Android names it, two or three letters, to one of [SUPPORTED], or null. */
    fun code(language: String?): String? {
        if (language.isNullOrEmpty()) return null
        val iso3 = when (language.lowercase(Locale.ROOT)) {
            "fre" -> "fra"
            "ger" -> "deu"
            else -> try {
                Locale.forLanguageTag(language).isO3Language
            } catch (e: Exception) {
                return null
            }
        }
        return SUPPORTED.firstOrNull { Locale.forLanguageTag(it).isO3Language == iso3 }
    }

    /**
     * Android voices for the checkpoint's speakers. A catalog voice is offered in its own locale
     * (such as en-GB) when the checkpoint speaks its language, and not otherwise. A voice without
     * catalog details, such as the checkpoint's `default`, is offered in every language.
     */
    private fun buildVoices(speakers: List<String>, languages: List<String>, meta: Map<String, VoiceInfo>): List<EngineVoice> {
        val out = mutableListOf<EngineVoice>()
        val used = mutableSetOf<String>()
        fun add(lang: String, locale: Locale, speaker: String?, label: String, info: VoiceInfo?) {
            var name = "${locale.toLanguageTag()}-${label.replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').ifEmpty { "voice" }}"
            if (!used.add(name)) name = "$name-${speaker ?: "x"}".also { used += it }
            out += EngineVoice(name, lang, locale, speaker, label, info)
        }
        if (speakers.isEmpty()) {
            for (lang in languages) add(lang, Locale.forLanguageTag(lang), null, "default", null)
            return out
        }
        // The checkpoint's own voice first, then the rest alphabetically.
        val ordered = speakers.sortedWith(compareBy({ it != "default" }, { meta[it]?.name ?: it }))
        for (speaker in ordered) {
            val info = meta[speaker]
            if (info == null) {
                for (lang in languages) add(lang, Locale.forLanguageTag(lang), speaker, speaker, null)
            } else if (info.language in languages) {
                add(info.language, info.locale, speaker, info.name, info)
            }
        }
        return out
    }

    private const val INSTALLED = "installed.json"
    private const val LOAD_TIMEOUT_S = 90L
}
