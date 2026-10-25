package dev.kanav.phonon.engine

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** A voice of the Hugging Face catalog, as `voices.jsonl` describes it. */
data class VoiceInfo(
    val id: String,
    val name: String,
    /** One of [Model.SUPPORTED]. */
    val language: String,
    /** Such as `en-us`, or `characters` for the English character voices. */
    val localeTag: String,
    val gender: String?,
    val age: String?,
    val description: String,
    val filename: String,
) {
    val locale: Locale
        get() = if (localeTag.contains('-')) Locale.forLanguageTag(localeTag) else Locale.forLanguageTag(language)

    /** Such as "English (United States)", or "English · characters". */
    val group: String
        get() = if (localeTag.contains('-')) locale.getDisplayName(Locale.ENGLISH) else "${Locale.forLanguageTag(language).getDisplayLanguage(Locale.ENGLISH)} · $localeTag"

    companion object {
        fun parse(line: String): VoiceInfo? = try {
            val o = JSONObject(line)
            VoiceInfo(
                id = o.getString("voice_id"),
                name = o.getString("name"),
                language = o.getString("language"),
                localeTag = o.optString("locale", o.getString("language")),
                gender = o.optString("gender").takeIf { it.isNotEmpty() && it != "null" },
                age = o.optString("age").takeIf { it.isNotEmpty() && it != "null" },
                description = o.optString("description"),
                filename = o.getString("filename"),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Catalog voices, downloaded into the checkpoint's `voices/` folder as `<voice_id>.safetensors`,
 * where the library finds them by that name. Their details come from a cached copy of the catalog.
 * Voice files the user imports go in the same folder and have no details.
 */
object Voices {
    private fun cache(context: Context) = File(context.filesDir, "voices.jsonl")

    private fun read(file: File): List<VoiceInfo> =
        if (file.isFile) file.readLines().mapNotNull { if (it.isBlank()) null else VoiceInfo.parse(it) } else emptyList()

    /** The catalog: the cached copy, unless there is none or [refresh] asks for a fresh one. Blocking. */
    fun catalog(context: Context, refresh: Boolean = false): List<VoiceInfo> {
        val file = cache(context)
        if (refresh || !file.isFile) {
            val text = Http.text(Catalog.VOICES_INDEX)
            val parsed = text.lines().mapNotNull { if (it.isBlank()) null else VoiceInfo.parse(it) }
            check(parsed.isNotEmpty()) { "the voice catalog is empty" }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        }
        return read(file)
    }

    /** Catalog details of the voices the checkpoint has, by voice id. */
    fun installedMeta(context: Context): Map<String, VoiceInfo> {
        val ids = installedIds(context)
        return read(cache(context)).filter { it.id in ids }.associateBy { it.id }
    }

    fun installedIds(context: Context): Set<String> =
        Model.voicesDir(context).listFiles { f -> f.name.endsWith(".safetensors") }
            ?.map { it.name.removeSuffix(".safetensors") }?.toSet().orEmpty()

    /** Download [voices] into the checkpoint, then reload it so they show up. Blocking. */
    fun download(context: Context, voices: List<VoiceInfo>, onProgress: (String) -> Unit) {
        val dir = Model.voicesDir(context)
        for (voice in voices) {
            onProgress(voice.name)
            Http.download(Catalog.voiceUrl(voice.filename), File(dir, "${voice.id}.safetensors"))
        }
        Model.reload(context)
    }

    /**
     * Add a voice file of the user's own as [name], then reload and speak a word with it, so a
     * file the model cannot use is refused rather than left to fail later. Returns the voice id
     * it got, which differs from [name] when that is taken. Blocking.
     */
    fun import(context: Context, input: InputStream, name: String): String {
        val dir = Model.voicesDir(context).apply { mkdirs() }
        val base = sanitize(name)
        var id = base
        var n = 2
        while (id == "default" || File(dir, "$id.safetensors").exists()) id = "$base-${n++}"
        // Not named .safetensors until checked, so a reload in the meantime does not pick it up.
        val tmp = File(dir, "$id.part")
        try {
            input.use { from ->
                tmp.outputStream().use { to ->
                    val copied = from.copyTo(to)
                    require(copied <= MAX_IMPORT_BYTES) { "that file is too big to be a voice" }
                }
            }
            checkSafetensors(tmp)
            check(tmp.renameTo(File(dir, "$id.safetensors"))) { "could not save the voice" }
        } finally {
            tmp.delete()
        }
        Model.reload(context)
        val model = Model.await(context)
        val error = when {
            model == null -> null // The model itself failed; the voice may be fine.
            id !in model.speakers -> "the model could not read this voice file"
            else -> try {
                synchronized(model) {
                    model.tts.setVoice(id)
                    model.tts.speak("Hi.") { false }
                }
                null
            } catch (e: Exception) {
                "the model could not speak with this voice file: ${e.message}"
            }
        }
        if (error != null) {
            File(dir, "$id.safetensors").delete()
            Model.reload(context)
            throw IllegalArgumentException(error)
        }
        return id
    }

    /** A voice id from what the user typed: also its file name and the name apps see. */
    fun sanitize(name: String): String =
        name.trim().removeSuffix(".safetensors").replace(Regex("[^A-Za-z0-9 _-]+"), "-")
            .trim(' ', '-').take(40).ifEmpty { "My voice" }

    /** Whether [file] looks like safetensors: a little-endian length, then a JSON header naming tensors. */
    private fun checkSafetensors(file: File) {
        val bad = "that is not a .safetensors voice file"
        file.inputStream().use { input ->
            val len = ByteArray(8)
            require(input.readNBytesCompat(len) == 8) { bad }
            val headerLen = ByteBuffer.wrap(len).order(ByteOrder.LITTLE_ENDIAN).long
            require(headerLen in 2..minOf(file.length() - 8, 16L shl 20)) { bad }
            val header = ByteArray(headerLen.toInt())
            require(input.readNBytesCompat(header) == header.size) { bad }
            val json = try {
                JSONObject(String(header, Charsets.UTF_8))
            } catch (e: Exception) {
                throw IllegalArgumentException(bad)
            }
            require(json.keys().asSequence().any { it != "__metadata__" }) { "the voice file has no tensors" }
        }
    }

    private fun InputStream.readNBytesCompat(into: ByteArray): Int {
        var at = 0
        while (at < into.size) {
            val n = read(into, at, into.size - at)
            if (n < 0) break
            at += n
        }
        return at
    }

    /** Catalog voices are about 250 KB; this leaves room for stored speaker latents. */
    private const val MAX_IMPORT_BYTES = 64L shl 20

    /** Remove a downloaded voice. The checkpoint's own voices stay. Blocking. */
    fun delete(context: Context, id: String) {
        File(Model.voicesDir(context), "$id.safetensors").delete()
        val prefs = Prefs(context)
        for (lang in Model.SUPPORTED) {
            if (prefs.preferredVoice(lang) == id) prefs.setPreferredVoice(lang, null)
        }
        Model.reload(context)
    }
}
