package dev.atv.nasplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.FilterChip
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.atv.nasplayer.playback.Outcome
import dev.atv.nasplayer.playback.PlaybackLog
import dev.atv.nasplayer.playback.PlaybackLogEntry
import java.text.DateFormat
import java.util.Date

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun PlaybackLogScreen() {
    val all by PlaybackLog.entries.collectAsState()
    var problemsOnly by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val shown = if (problemsOnly) all.filter { it.problem } else all
    val gaps = remember(all) { PlaybackLog.gapSummary(all) }
    val first = remember { FocusRequester() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Text("Playback log", style = MaterialTheme.typography.headlineMedium) }
        item {
            Text(
                "Every playback attempt with the codecs found and what decoded them. Files ExoPlayer hands to VLC, " +
                    "or that nothing could play, show up under \"Problems only\".",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.widthIn(max = 900.dp),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                FilterChip(selected = !problemsOnly, onClick = { problemsOnly = false }, modifier = Modifier.focusRequester(first)) {
                    Text("All (${all.size})")
                }
                FilterChip(selected = problemsOnly, onClick = { problemsOnly = true }) {
                    Text("Problems only (${all.count { it.problem }})")
                }
                OutlinedButton(onClick = {
                    if (confirmClear) { PlaybackLog.clear(); confirmClear = false } else confirmClear = true
                }) { Text(if (confirmClear) "Confirm clear" else "Clear log") }
            }
        }
        if (problemsOnly && gaps.isNotEmpty()) {
            item { Text("Missing support, most frequent first", style = MaterialTheme.typography.titleMedium) }
            items(gaps, key = { "gap-" + it.first }) { (what, n) ->
                ListItem(selected = false, onClick = {}, headlineContent = { Text(what) }, trailingContent = { Text("×$n") },
                    modifier = Modifier.widthIn(max = 900.dp))
            }
            item { Text("Attempts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
        }
        if (shown.isEmpty()) {
            item { Text("Nothing logged yet.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 24.dp)) }
        }
        items(shown, key = { it.at.toString() + it.path + it.engine }) { e -> LogRow(e) }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun LogRow(e: PlaybackLogEntry) {
    val status = when (e.outcome) {
        Outcome.PLAYED -> if (e.tracks.any { !it.supported }) "played, some tracks unsupported" else "played"
        Outcome.FELL_BACK -> "handed to VLC"
        Outcome.FAILED -> "failed"
    }
    ListItem(
        selected = false,
        onClick = {},
        modifier = Modifier.widthIn(max = 1100.dp),
        headlineContent = { Text(e.path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        overlineContent = {
            Text("${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.at))} · ${e.serverName} · ${e.engine} · $status")
        },
        supportingContent = {
            Column {
                Text("Container: ${e.container}")
                e.tracks.forEach { t ->
                    val dec = when {
                        !t.supported -> "NOT SUPPORTED"
                        t.decoder != null -> t.decoder
                        else -> "—"
                    }
                    Text("${t.type}: ${t.codec} ${t.detail} → $dec", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                e.reason?.let { Text("Reason: $it", maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        },
    )
}
