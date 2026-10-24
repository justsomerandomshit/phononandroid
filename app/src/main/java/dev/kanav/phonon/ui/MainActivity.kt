package dev.kanav.phonon.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kanav.phonon.engine.Catalog
import dev.kanav.phonon.engine.Model
import dev.kanav.phonon.engine.ModelSpec
import dev.kanav.phonon.engine.Prefs
import dev.kanav.phonon.engine.VoiceInfo
import dev.kanav.phonon.engine.Voices
import java.io.File
import java.util.Locale

/**
 * The app's one screen, which the system text-to-speech settings also open as the engine's
 * settings: install a model and voices, pick voices, try them, and make Phonon the default.
 */
class MainActivity : ComponentActivity() {
    /** Bound to this app's own engine, so "Try it" goes through the same path as any app. */
    private var tts: TextToSpeech? = null
    private var ttsReady by mutableStateOf(false)

    /** Whether Phonon is the system's preferred engine, re-read on every resume. */
    var isDefaultEngine by mutableStateOf(false)
        private set

    lateinit var prefs: Prefs
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Model.load(this)
        tts = TextToSpeech(this, { status -> ttsReady = status == TextToSpeech.SUCCESS }, packageName)
        setContent { PhononTheme { App(this) } }
    }

    override fun onResume() {
        super.onResume()
        isDefaultEngine = try {
            @Suppress("DEPRECATION")
            Settings.Secure.getString(contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH) == packageName
        } catch (e: Exception) {
            false
        }
        Jobs.touch()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    fun openTtsSettings() {
        for (action in listOf("com.android.settings.TTS_SETTINGS", Settings.ACTION_ACCESSIBILITY_SETTINGS, Settings.ACTION_SETTINGS)) {
            try {
                startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                // Try the next one.
            }
        }
    }

    fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Jobs.error = "No browser to open $url"
        }
    }

    // Speaking through the engine.

    fun speak(text: String, rate: Float, pitch: Float, voiceName: String? = null, lang: String? = null) {
        val tts = tts ?: return
        if (!ttsReady) {
            Jobs.error = "The engine is still starting"
            return
        }
        val language = lang ?: Model.languages(this).firstOrNull() ?: return
        // Setting the language resets the voice to that language's default.
        tts.setLanguage(Locale.forLanguageTag(language))
        if (voiceName != null) tts.voices?.firstOrNull { it.name == voiceName }?.let { tts.setVoice(it) }
        tts.setSpeechRate(rate)
        tts.setPitch(pitch)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "try")
    }

    fun preview(voice: Model.EngineVoice) =
        speak(Catalog.SAMPLE_TEXT[voice.lang] ?: "Hello.", 1f, 1f, voice.name, voice.lang)

    fun stopSpeaking() {
        tts?.stop()
    }

    // Long tasks.

    fun download(spec: ModelSpec) {
        val token = prefs.hfToken.takeIf { spec.gated }
        if (spec.gated && token.isNullOrBlank()) {
            Jobs.error = "${spec.title} is gated: accept its terms on Hugging Face, then paste an access token below."
            return
        }
        tts?.stop()
        Jobs.run("Downloading ${spec.title}") { report ->
            val app = applicationContext
            Model.install(app, spec.id) { staging ->
                val files = Catalog.MODEL_FILES
                for ((i, name) in files.withIndex()) {
                    dev.kanav.phonon.engine.Http.download(
                        spec.fileUrl(name), File(staging, name), token, { Jobs.cancelled },
                    ) { done, total ->
                        val mb = done / 1_000_000
                        val label = if (total > 0) "Downloading ${spec.title}: $name, $mb of ${total / 1_000_000} MB" else "Downloading ${spec.title}: $name, $mb MB"
                        report(label, if (total > 0) (i + done.toFloat() / total) / files.size else null)
                    }
                }
                report("Loading ${spec.title}…", null)
            }
            Model.await(app)
        }
    }

    fun importFolder(tree: Uri) {
        tts?.stop()
        Jobs.run("Importing…") { report ->
            Model.install(applicationContext, null) { into ->
                FolderImport(contentResolver) { report("Copying $it…", null) }.copy(tree, into)
            }
            Model.await(applicationContext)
        }
    }

    fun uninstall() {
        tts?.stop()
        Jobs.run("Removing the model…") { Model.uninstall(applicationContext) }
    }

    fun downloadVoices(voices: List<VoiceInfo>) {
        Jobs.run("Downloading voices…") { report ->
            Voices.download(applicationContext, voices) { report("Downloading voice $it…", null) }
            report("Loading voices…", null)
            Model.await(applicationContext)
        }
    }

    fun deleteVoice(id: String) {
        tts?.stop()
        Jobs.run("Removing the voice…") {
            Voices.delete(applicationContext, id)
            Model.await(applicationContext)
        }
    }

    /** The native thread pool reads its size once per process, so a change needs a fresh one. */
    fun restartEngine() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}
