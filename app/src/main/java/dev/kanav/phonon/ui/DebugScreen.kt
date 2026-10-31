package dev.kanav.phonon.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kanav.phonon.engine.Diagnostics
import dev.kanav.phonon.engine.Model
import dev.kanav.phonon.ffi.NativeLib
import kotlin.concurrent.thread

/** Device, CPU and engine details plus recent crashes, to copy into a bug report. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(activity: MainActivity, onBack: () -> Unit) {
    var report by remember { mutableStateOf<String?>(null) }
    var refreshes by remember { mutableIntStateOf(0) }
    val state = Model.state
    LaunchedEffect(refreshes, state) {
        thread { report = Diagnostics.report(activity.applicationContext) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug info") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { refreshes++ }) { Icon(Icons.Filled.Refresh, "Refresh") } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "If Phonon crashes or will not load, copy this and paste it into your bug report. It has no personal data: the device, its CPU, the engine's state and the app's own recent log.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { report?.let(activity::copyText) }, enabled = report != null) { Text("Copy") }
                    OutlinedButton(onClick = { report?.let(activity::shareText) }, enabled = report != null) { Text("Share") }
                    if (state is Model.State.Failed) {
                        OutlinedButton(onClick = { Model.retry(activity) }) { Text("Try loading again") }
                    }
                }
            }
            item { CompatibleCard(activity) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    val text = report
                    if (text == null) {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        SelectionContainer {
                            Text(
                                text,
                                Modifier.padding(12.dp).horizontalScroll(rememberScrollState()),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompatibleCard(activity: MainActivity) {
    val saved = remember { activity.prefs.compatibleCpu }
    var compatible by remember { mutableStateOf(saved) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Compatibility mode", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Running lib${NativeLib.name}.so: ${NativeLib.reason}. Compatibility mode uses the build for older CPUs (ARMv8.0) even if this one reports newer features. Slower, but try it if loading the model closes the app.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = compatible, onCheckedChange = { compatible = it })
            }
            if (compatible != saved) {
                Button(onClick = {
                    activity.prefs.compatibleCpu = compatible
                    // A different library is a fresh chance, even after a crash.
                    Diagnostics.unblock(activity)
                    activity.restartEngine()
                }) { Text("Apply and restart the engine") }
            }
        }
    }
}
