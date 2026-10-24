package dev.kanav.phonon.engine

/** A Phonon checkpoint the app can download from Hugging Face. */
data class ModelSpec(
    val id: String,
    val title: String,
    val repo: String,
    /** A commit, so a download is reproducible, or `main` where none is pinned. */
    val revision: String,
    val languages: List<String>,
    val sizeMb: Int,
    val license: String,
    /** Gated repos need the terms accepted on Hugging Face and a token to download. */
    val gated: Boolean,
    val blurb: String,
) {
    val pageUrl get() = "https://huggingface.co/$repo"
    fun fileUrl(name: String) = "https://huggingface.co/$repo/resolve/$revision/$name"
}

object Catalog {
    /** What a checkpoint folder needs on the CPU: the q8 weights load fastest and are smallest. */
    val MODEL_FILES = listOf("config.json", "tokenizer.json", "model.q8.gguf", "default-voice.safetensors")

    val MODELS = listOf(
        ModelSpec(
            id = "phonon-31m-en",
            title = "Phonon 31m · English",
            repo = "gradium/phonon-31m-en",
            // The commit upstream pins as its default model (ptts::checkpoint::DEFAULT_MODEL).
            revision = "8c64b55ec301728e2d1695bfea87a08a267d0c2d",
            languages = listOf("en"),
            sizeMb = 65,
            license = "Apache 2.0",
            gated = false,
            blurb = "Smallest and fastest. Recommended.",
        ),
        gated40m("en", "English"),
        gated40m("fr", "French"),
        gated40m("de", "German"),
        gated40m("es", "Spanish"),
        gated40m("pt", "Portuguese"),
        ModelSpec(
            id = "phonon-40m-multilingual",
            title = "Phonon 40m · Multilingual",
            repo = "gradium/phonon-40m-multilingual",
            revision = "main",
            languages = listOf("en", "fr", "de", "es", "pt"),
            sizeMb = 75,
            license = "CC BY-NC 4.0",
            gated = true,
            blurb = "All five languages in one model. Non-commercial license.",
        ),
    )

    private fun gated40m(lang: String, name: String) = ModelSpec(
        id = "phonon-40m-$lang",
        title = "Phonon 40m · $name",
        repo = "gradium/phonon-40m-$lang",
        revision = "main",
        languages = listOf(lang),
        sizeMb = 75,
        license = "CC BY-NC 4.0",
        gated = true,
        blurb = "Larger model. Non-commercial license.",
    )

    fun model(id: String?) = MODELS.firstOrNull { it.id == id }

    /** The voice catalog: one `.safetensors` embedding per voice, usable with every checkpoint. */
    const val VOICES_REPO = "gradium/phonon-voices"
    const val VOICES_INDEX = "https://huggingface.co/$VOICES_REPO/resolve/main/voices.jsonl"
    fun voiceUrl(filename: String) = "https://huggingface.co/$VOICES_REPO/resolve/main/$filename"

    val LANGUAGE_NAMES = mapOf(
        "en" to "English",
        "fr" to "French",
        "de" to "German",
        "es" to "Spanish",
        "pt" to "Portuguese",
    )

    /** What the system settings' "Play" button and the app's previews say. */
    val SAMPLE_TEXT = mapOf(
        "en" to "This is an example of speech synthesis in English.",
        "fr" to "Ceci est un exemple de synthèse vocale en français.",
        "de" to "Dies ist ein Beispiel für Sprachsynthese auf Deutsch.",
        "es" to "Este es un ejemplo de síntesis de voz en español.",
        "pt" to "Este é um exemplo de síntese de voz em português.",
    )
}
