package dev.kanav.phonon.engine

import android.content.Context
import org.json.JSONObject
import java.io.File
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
