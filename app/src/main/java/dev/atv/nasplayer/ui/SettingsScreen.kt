package dev.atv.nasplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.RadioButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.atv.nasplayer.BuildConfig
import dev.atv.nasplayer.data.Settings
import dev.atv.nasplayer.playback.EnginePreference

@Composable
fun SettingsScreen(onOpenLog: () -> Unit) {
    var engine by remember { mutableStateOf(Settings.enginePreference) }
    var autoNext by remember { mutableStateOf(Settings.autoPlayNext) }
    var showAll by remember { mutableStateOf(Settings.showAllFiles) }
    val first = remember { FocusRequester() }
    val w = Modifier.widthIn(max = 760.dp)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }
        item { Text("Playback engine", style = MaterialTheme.typography.titleMedium) }
        EnginePreference.entries.forEachIndexed { i, pref ->
            item {
                ListItem(
                    selected = false,
                    onClick = { engine = pref; Settings.enginePreference = pref },
                    headlineContent = { Text(pref.label) },
                    supportingContent = when (pref) {
                        EnginePreference.AUTO -> ({ Text("Hardware decoding and passthrough first; VLC takes over for anything ExoPlayer can't play.") })
                        EnginePreference.EXO -> ({ Text("Best for HDR and surround passthrough to an AV receiver.") })
                        EnginePreference.VLC -> ({ Text("Widest container and codec coverage, using software decoding when needed.") })
                    },
                    leadingContent = { RadioButton(selected = engine == pref, onClick = null) },
                    modifier = if (i == 0) w.focusRequester(first) else w,
                )
            }
        }
        item { Text("Browsing", style = MaterialTheme.typography.titleMedium) }
        item {
            ListItem(
                selected = false,
                onClick = { autoNext = !autoNext; Settings.autoPlayNext = autoNext },
                headlineContent = { Text("Play next file in folder automatically") },
                trailingContent = { Switch(checked = autoNext, onCheckedChange = null) },
                modifier = w,
            )
        }
        item {
            ListItem(
                selected = false,
                onClick = { showAll = !showAll; Settings.showAllFiles = showAll },
                headlineContent = { Text("Show non-media files") },
                supportingContent = { Text("Unrecognised files open with the VLC engine.") },
                trailingContent = { Switch(checked = showAll, onCheckedChange = null) },
                modifier = w,
            )
        }
        item { Text("Diagnostics", style = MaterialTheme.typography.titleMedium) }
        item {
            ListItem(
                selected = false,
                onClick = onOpenLog,
                headlineContent = { Text("Playback log") },
                supportingContent = { Text("Files played, codecs found, which decoder handled them, and what failed.") },
                modifier = w,
            )
        }
        item {
            Text(
                "NAS Player ${BuildConfig.VERSION_NAME} · GPL-3.0-or-later\n" +
                    "Uses Media3/ExoPlayer (Apache-2.0), Jellyfin FFmpeg decoder (GPL-3.0), libVLC (LGPL-2.1), smbj (Apache-2.0).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
