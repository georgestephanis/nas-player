package dev.atv.nasplayer.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.tv.material3.Card
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.atv.nasplayer.data.ResumeEntry
import dev.atv.nasplayer.data.ResumeStore
import dev.atv.nasplayer.data.ServerConfig
import dev.atv.nasplayer.data.ServerRepository
import dev.atv.nasplayer.data.ServerType
import dev.atv.nasplayer.discovery.DiscoveredServer
import dev.atv.nasplayer.discovery.NetworkDiscovery
import dev.atv.nasplayer.source.MediaBrowser
import dev.atv.nasplayer.source.MediaKind
import dev.atv.nasplayer.source.MediaTypes
import dev.atv.nasplayer.source.RemoteEntry
import dev.atv.nasplayer.source.UsbDrives
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onOpenServer: (ServerConfig) -> Unit,
    onEditServer: (ServerConfig) -> Unit,
    onAddServer: (ServerConfig?) -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val servers by ServerRepository.servers.collectAsState()
    val resumeVersion by ResumeStore.version.collectAsState()
    var drives by remember { mutableStateOf(UsbDrives.list()) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { drives = UsbDrives.list() }
        }
        ContextCompat.registerReceiver(context, receiver, UsbDrives.changes, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val continueWatching = remember(resumeVersion, servers, drives) {
        ResumeStore.continueWatching { e -> (servers + drives).any { it.id == e.serverId && it.showInContinueWatching } }
    }
    val discovered by remember { NetworkDiscovery.discover(context) }.collectAsState(initial = emptyList())
    val unsaved = discovered.filter { d -> servers.none { it.host.equals(d.host, true) || it.host.equals(d.name, true) } }
    val firstFocus = remember { FocusRequester() }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("NAS Player", style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
            }
        }

        if (continueWatching.isNotEmpty()) {
            item {
                Shelf("Continue watching") {
                    items(continueWatching, key = { it.serverId + it.path }) { entry ->
                        ResumeCard(
                            entry,
                            ServerRepository.get(entry.serverId)?.name.orEmpty(),
                            if (entry == continueWatching.first()) Modifier.focusRequester(firstFocus) else Modifier,
                        ) {
                            val server = ServerRepository.get(entry.serverId) ?: return@ResumeCard
                            scope.launch {
                                val siblings = runCatching { MediaBrowser.forServer(server).list(entry.folder) }.getOrNull()
                                val target = siblings?.firstOrNull { it.name == entry.name }
                                    ?: RemoteEntry(entry.name, entry.path, false)
                                launchPlayback(context, server, entry.folder, siblings ?: listOf(target), target)
                            }
                        }
                    }
                }
            }
        }

        item {
            Shelf("Sources") {
                items(servers, key = { it.id }) { server ->
                    TileCard(
                        icon = if (server.type == ServerType.SMB) Icons.Default.Dns else Icons.Default.Cloud,
                        title = server.name,
                        subtitle = server.subtitle,
                        onClick = { onOpenServer(server) },
                        onLongClick = { onEditServer(server) },
                        modifier = if (continueWatching.isEmpty() && server == servers.first()) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
                item(key = "add") {
                    TileCard(
                        icon = Icons.Default.Add,
                        title = "Add source",
                        subtitle = "SMB share or WebDAV",
                        onClick = { onAddServer(null) },
                        modifier = if (servers.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
                    )
                }
            }
        }

        if (drives.isNotEmpty()) {
            item {
                Shelf("Local drives") {
                    items(drives, key = { it.id }) { drive ->
                        TileCard(
                            icon = Icons.Default.Usb,
                            title = drive.name,
                            subtitle = drive.subtitle,
                            onClick = { if (UsbDrives.hasAccess()) onOpenServer(drive) else UsbDrives.requestAccess(context as Activity) },
                        )
                    }
                }
            }
        }

        item {
            Shelf(if (unsaved.isEmpty()) "Searching your network…" else "Found on your network") {
                items(unsaved, key = { "d-" + it.name }) { d ->
                    TileCard(
                        icon = Icons.Default.Wifi,
                        title = d.name,
                        subtitle = d.host,
                        onClick = { onAddServer(d.toConfig()) },
                    )
                }
            }
        }

        if (servers.isNotEmpty()) {
            item {
                Text(
                    "Hold OK on a source to edit or remove it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 48.dp),
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { firstFocus.requestFocus() }
        listState.scrollToItem(0)
    }
}

private fun DiscoveredServer.toConfig() = ServerConfig(
    name = name,
    type = ServerType.SMB,
    host = host,
    port = port.takeIf { it != 445 && it > 0 },
)

@Composable
private fun Shelf(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 48.dp, bottom = 12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            content = content,
        )
    }
}

@Composable
private fun TileCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    Card(onClick = onClick, onLongClick = onLongClick, modifier = modifier.width(260.dp).height(150.dp)) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = LocalContentColorAlpha(),
                )
            }
        }
    }
}

@Composable
private fun ResumeCard(entry: ResumeEntry, serverName: String, modifier: Modifier, onClick: () -> Unit) {
    val kind = MediaTypes.kindOf(entry.name)
    val context = LocalContext.current
    Card(onClick = onClick, modifier = modifier.width(320.dp).height(250.dp)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(160.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                Icon(if (kind == MediaKind.AUDIO) Icons.Default.MusicNote else Icons.Default.Movie, null, Modifier.size(40.dp))
                coil3.compose.AsyncImage(
                    model = coil3.request.ImageRequest.Builder(context)
                        .data(dev.atv.nasplayer.images.Thumb(entry.serverId, entry.path, kind, -2, 0))
                        .build(),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                LinearProgressIndicator(
                    progress = { entry.progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.25f),
                )
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(entry.name.substringBeforeLast('.'), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatDuration(entry.durationMs - entry.positionMs)} left · $serverName",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    color = LocalContentColorAlpha(),
                )
            }
        }
    }
}

@Composable
private fun LocalContentColorAlpha() = androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.7f)
