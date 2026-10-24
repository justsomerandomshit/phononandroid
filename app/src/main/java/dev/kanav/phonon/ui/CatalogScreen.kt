package dev.kanav.phonon.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kanav.phonon.engine.Catalog
import dev.kanav.phonon.engine.Model
import dev.kanav.phonon.engine.VoiceInfo
import dev.kanav.phonon.engine.Voices
import kotlin.concurrent.thread

/** The Hugging Face voice catalog, for the languages the installed model speaks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(activity: MainActivity, onBack: () -> Unit) {
    var catalog by remember { mutableStateOf<List<VoiceInfo>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var refreshes by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshes) {
        loadError = null
        thread {
            try {
                catalog = Voices.catalog(activity.applicationContext, refresh = refreshes > 0)
            } catch (e: Exception) {
                loadError = e.message ?: e.toString()
            }
        }
    }
    val revision = Jobs.revision
    val installed = remember(revision) { Voices.installedIds(activity) }
    val languages = remember(revision) { Model.languages(activity).ifEmpty { Model.SUPPORTED } }
    var lang by rememberSaveable { mutableStateOf(languages.first()) }
    if (lang !in languages) lang = languages.first()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice catalog") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { catalog = null; refreshes++ }) { Icon(Icons.Filled.Refresh, "Refresh") } },
            )
        },
    ) { padding ->
        val list = catalog
        Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
            val task = Jobs.task
            if (task != null) {
                Text(task.label, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp, 4.dp))
            }
            Jobs.error?.let { Text(it, Modifier.padding(16.dp, 4.dp), color = MaterialTheme.colorScheme.error) }
            if (languages.size > 1) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (l in languages) FilterChip(selected = l == lang, onClick = { lang = l }, label = { Text(Catalog.LANGUAGE_NAMES[l] ?: l) })
                }
            }
            when {
                loadError != null -> Column(Modifier.padding(16.dp)) {
                    Text("Could not load the catalog: $loadError")
                    TextButton(onClick = { refreshes++ }) { Text("Retry") }
                }
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> {
                    val shown = list.filter { it.language == lang }
                    val missing = shown.filter { it.id !in installed }
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = padding.calculateBottomPadding() + 24.dp),
                    ) {
                        item {
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${shown.size} voices, about 250 KB each. Downloaded voices appear in the system settings and in any app's voice list.",
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (missing.isNotEmpty()) {
                                    TextButton(onClick = { activity.downloadVoices(missing) }, enabled = !Jobs.busy) { Text("Get all") }
                                }
                            }
                        }
                        for (group in shown.groupBy { it.group }) {
                            item(key = "h-${group.key}") {
                                Text(group.key, Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            items(group.value, key = { it.id }) { voice ->
                                VoiceRow(voice, voice.id in installed, onGet = { activity.downloadVoices(listOf(voice)) }, onRemove = { activity.deleteVoice(voice.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceRow(voice: VoiceInfo, installed: Boolean, onGet: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(voice.name, fontWeight = FontWeight.Medium)
            val sub = listOfNotNull(voice.gender, voice.age).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(voice.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        if (installed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.CheckCircle, "Installed", tint = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onRemove, enabled = !Jobs.busy) { Text("Remove") }
            }
        } else {
            Button(onClick = onGet, enabled = !Jobs.busy) { Text("Get") }
        }
    }
}
