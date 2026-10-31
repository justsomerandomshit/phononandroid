package dev.kanav.phonon.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.kanav.phonon.engine.Catalog
import dev.kanav.phonon.engine.Model
import dev.kanav.phonon.engine.Prefs
import dev.kanav.phonon.engine.Voices
import java.util.Locale

@Composable
fun App(activity: MainActivity) {
    // "home", "catalog" or "debug".
    var screen by rememberSaveable { mutableStateOf("home") }
    BackHandler(screen != "home") { screen = "home" }
    when (screen) {
        "catalog" -> CatalogScreen(activity, onBack = { screen = "home" })
        "debug" -> DebugScreen(activity, onBack = { screen = "home" })
        else -> HomeScreen(activity, onBrowseVoices = { screen = "catalog" }, onDebug = { screen = "debug" })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(activity: MainActivity, onBrowseVoices: () -> Unit, onDebug: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Phonon TTS") }) }) { padding ->
        // Read so the screen follows tasks ending and the model loading.
        val revision = Jobs.revision
        val state = Model.state
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusCard(activity, state, onDebug) }
            item { JobCard() }
            item { DefaultEngineCard(activity) }
            item(key = "model-$revision") { ModelCard(activity) }
            if (state is Model.State.Ready) {
                item(key = "voices-$revision") { VoicesCard(activity, state.model, onBrowseVoices) }
                item { TryCard(activity) }
            }
            item { PerformanceCard(activity) }
            item { AboutCard(activity, onDebug) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun StatusCard(activity: MainActivity, state: Model.State, onDebug: () -> Unit) {
    val colors = when (state) {
        is Model.State.Ready -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        is Model.State.Failed -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        else -> CardDefaults.cardColors()
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = colors) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                is Model.State.Loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                is Model.State.Ready -> Icon(Icons.Filled.CheckCircle, null)
                else -> Icon(Icons.Filled.Warning, null)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    when (state) {
                        is Model.State.NotInstalled -> "No voice model installed"
                        is Model.State.Loading -> "Loading the model…"
                        is Model.State.Ready -> "Ready"
                        is Model.State.Failed -> "The model failed to load"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    when (state) {
                        is Model.State.NotInstalled -> "Download a model below. Speech is generated on this phone, with no network."
                        is Model.State.Loading -> "This takes a few seconds."
                        is Model.State.Ready -> "${state.model.languages.joinToString { Catalog.LANGUAGE_NAMES[it] ?: it }} · ${state.model.speakers.size.coerceAtLeast(1)} voice(s)"
                        is Model.State.Failed -> state.message
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state is Model.State.Failed) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { Model.retry(activity) }, enabled = !Jobs.busy) { Text("Try again") }
                        OutlinedButton(onClick = onDebug) { Text("Debug info") }
                    }
                }
            }
        }
    }
}

@Composable
private fun JobCard() {
    val task = Jobs.task
    val error = Jobs.error
    if (task != null) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(task.label, style = MaterialTheme.typography.bodyMedium)
                val progress = task.progress
                if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                TextButton(onClick = Jobs::cancel, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
            }
        }
    } else if (error != null) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { Jobs.error = null }) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun DefaultEngineCard(activity: MainActivity) {
    Section("System voice") {
        if (activity.isDefaultEngine) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Phonon is the device's default text-to-speech engine.")
            }
            Text(
                "Every app that speaks through Android (TalkBack, Select to Speak, e-book readers, maps…) uses it.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = activity::openTtsSettings) { Text("Text-to-speech settings") }
        } else {
            Text("To make Phonon the voice every app uses, open the text-to-speech settings, tap Preferred engine, and choose Phonon TTS.")
            Button(onClick = activity::openTtsSettings) { Text("Open text-to-speech settings") }
            Text("Or from a computer:", style = MaterialTheme.typography.bodySmall)
            SelectionContainer {
                Text(
                    "adb shell settings put secure tts_default_synth ${activity.packageName}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

@Composable
private fun ModelCard(activity: MainActivity) {
    val installedSpec = remember { Model.installedSpec(activity) }
    val installed = remember { Model.installed(activity) }
    var showAll by rememberSaveable { mutableStateOf(installedSpec?.gated == true) }
    var token by rememberSaveable { mutableStateOf(activity.prefs.hfToken) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) activity.importFolder(uri)
    }
    Section("Model") {
        if (installed && installedSpec == null) {
            ModelRow(title = "Imported checkpoint", detail = Model.languages(activity).joinToString { Catalog.LANGUAGE_NAMES[it] ?: it }, installed = true, busy = Jobs.busy, onDownload = {}, onRemove = activity::uninstall)
            HorizontalDivider()
        }
        val shown = Catalog.MODELS.filter { showAll || !it.gated || it.id == installedSpec?.id }
        for (spec in shown) {
            ModelRow(
                title = spec.title,
                detail = "${spec.blurb}\n${spec.sizeMb} MB · ${spec.license}${if (spec.gated) " · needs a Hugging Face token" else ""}",
                installed = spec.id == installedSpec?.id,
                busy = Jobs.busy,
                onDownload = { activity.download(spec) },
                onRemove = activity::uninstall,
                onTerms = if (spec.gated) ({ activity.openUrl(spec.pageUrl) }) else null,
            )
        }
        if (!showAll) {
            TextButton(onClick = { showAll = true }) { Text("More models: Phonon 40m (French, German, Spanish, Portuguese…)") }
        } else {
            Text(
                "The 40m models are gated: open a model's page, accept its terms (approval is automatic), then create a read token at huggingface.co/settings/tokens and paste it here.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it; activity.prefs.hfToken = it },
                label = { Text("Hugging Face token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedButton(onClick = { pick.launch(null) }, enabled = !Jobs.busy) { Text("Import a checkpoint folder…") }
    }
}

@Composable
private fun ModelRow(
    title: String,
    detail: String,
    installed: Boolean,
    busy: Boolean,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onTerms: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall)
            if (onTerms != null && !installed) {
                TextButton(onClick = onTerms, contentPadding = PaddingValues(0.dp)) { Text("Accept terms on Hugging Face") }
            }
        }
        Spacer(Modifier.width(8.dp))
        if (installed) {
            Column(horizontalAlignment = Alignment.End) {
                Text("Installed", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = onRemove, enabled = !busy) { Text("Remove") }
            }
        } else {
            Button(onClick = onDownload, enabled = !busy) { Text("Get") }
        }
    }
}

@Composable
private fun VoicesCard(activity: MainActivity, model: Model.Loaded, onBrowseVoices: () -> Unit) {
    // Bumped to recompose the radio buttons after a pick; the choice itself lives in Prefs.
    var picks by remember { mutableIntStateOf(0) }
    // Voice files in the checkpoint's voices folder, catalog or imported; only these can be removed.
    val files = remember(Jobs.revision) { Voices.installedIds(activity) }
    activity.pickedVoiceFile?.let { uri ->
        var importName by remember(uri) { mutableStateOf(Voices.sanitize(activity.displayName(uri) ?: "My voice")) }
        AlertDialog(
            onDismissRequest = { activity.pickedVoiceFile = null },
            title = { Text("Name this voice") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Apps and the system settings show it under this name, in every language the model speaks.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = importName, onValueChange = { importName = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    activity.importVoice(uri, importName)
                    activity.pickedVoiceFile = null
                }, enabled = importName.isNotBlank()) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { activity.pickedVoiceFile = null }) { Text("Cancel") } },
        )
    }
    Section("Voices") {
        Text(
            "The voice apps get when they only ask for a language. Apps and the system settings can also pick any of these voices by name.",
            style = MaterialTheme.typography.bodySmall,
        )
        for (lang in model.languages) {
            val voices = model.voicesFor(lang)
            if (voices.isEmpty()) continue
            if (model.languages.size > 1) Text(Catalog.LANGUAGE_NAMES[lang] ?: lang, style = MaterialTheme.typography.labelLarge)
            val current = picks.let { effectiveDefault(activity.prefs, voices, lang) }
            for (voice in voices) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = voice === current, onClick = {
                        activity.prefs.setPreferredVoice(lang, voice.speaker)
                        picks++
                    })
                    Column(Modifier.weight(1f)) {
                        Text(if (voice.speaker == "default" || voice.speaker == null) "Default (built in)" else voice.label)
                        val info = voice.info
                        val sub = when {
                            info != null -> listOfNotNull(info.locale.getDisplayCountry(Locale.ENGLISH).ifEmpty { info.localeTag }, info.gender, info.age).joinToString(" · ")
                            voice.speaker in files -> "Imported"
                            else -> "Ships with the model"
                        }
                        Text(sub, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { activity.preview(voice) }) { Icon(Icons.Filled.PlayArrow, "Preview ${voice.label}") }
                    if (voice.speaker != null && voice.speaker in files) {
                        IconButton(onClick = { activity.deleteVoice(voice.speaker) }, enabled = !Jobs.busy) {
                            Icon(Icons.Filled.Delete, "Remove ${voice.label}")
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBrowseVoices) { Text("Get more voices") }
            OutlinedButton(onClick = { activity.pickVoiceFile.launch(arrayOf("*/*")) }, enabled = !Jobs.busy) { Text("Import a voice file") }
        }
        // Also shown at the top, which is out of sight from here.
        Jobs.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Text(
            "Import takes a .safetensors voice embedding, like the ones the voice catalog has or ptts's create_voice example makes.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Which voice the engine uses for [lang] when none is named; see PhononService.defaultVoice. */
private fun effectiveDefault(prefs: Prefs, voices: List<Model.EngineVoice>, lang: String): Model.EngineVoice? {
    prefs.preferredVoice(lang)?.let { pref -> voices.firstOrNull { it.speaker == pref }?.let { return it } }
    return voices.firstOrNull { it.speaker == "default" || it.speaker == null } ?: voices.firstOrNull()
}

@Composable
private fun TryCard(activity: MainActivity) {
    val lang = Model.languages(activity).firstOrNull() ?: "en"
    var text by rememberSaveable { mutableStateOf(Catalog.SAMPLE_TEXT[lang] ?: "") }
    var rate by rememberSaveable { mutableFloatStateOf(1f) }
    var pitch by rememberSaveable { mutableFloatStateOf(1f) }
    Section("Try it") {
        OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Text("Speed ${"%.2f".format(rate)}×", style = MaterialTheme.typography.bodySmall)
        Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.5f..3f)
        Text("Pitch ${"%.2f".format(pitch)}×", style = MaterialTheme.typography.bodySmall)
        Slider(value = pitch, onValueChange = { pitch = it }, valueRange = 0.5f..2f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { activity.speak(text, rate, pitch) }, enabled = text.isNotBlank()) { Text("Speak") }
            OutlinedButton(onClick = activity::stopSpeaking) { Text("Stop") }
        }
        Text(
            "Speed and pitch here work like the sliders in the system settings and in TalkBack.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PerformanceCard(activity: MainActivity) {
    val saved = remember { activity.prefs.threads }
    var threads by remember { mutableIntStateOf(saved) }
    Section("Performance") {
        Text("CPU threads: $threads", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = threads.toFloat(),
            onValueChange = { threads = it.toInt() },
            valueRange = 1f..Prefs.MAX_THREADS.toFloat(),
            steps = Prefs.MAX_THREADS - 2,
        )
        Text(
            "2 suits most phones. More can help on phones with several fast cores, and can hurt on phones that mix fast and slow ones.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (threads != saved) {
            Button(onClick = {
                activity.prefs.threads = threads
                activity.restartEngine()
            }) { Text("Apply and restart the engine") }
        }
    }
}

@Composable
private fun AboutCard(activity: MainActivity, onDebug: () -> Unit) {
    Section("About") {
        Text(
            "Powered by Phonon from Gradium: open-weight neural text-to-speech that runs entirely on this phone. Phonon 31m is Apache 2.0; the Phonon 40m models are CC BY-NC 4.0 (non-commercial).",
            style = MaterialTheme.typography.bodySmall,
        )
        Row {
            TextButton(onClick = { activity.openUrl("https://github.com/gradium-ai/xn-ptts") }) { Text("Phonon on GitHub") }
            TextButton(onClick = { activity.openUrl("https://huggingface.co/${Catalog.VOICES_REPO}") }) { Text("Voice catalog") }
        }
        Text("Crashing or not loading? Debug info has the details to put in a bug report.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onDebug) { Text("Debug info") }
        Spacer(Modifier.height(4.dp))
    }
}
