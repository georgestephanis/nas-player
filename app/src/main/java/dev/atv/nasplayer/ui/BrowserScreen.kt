package dev.atv.nasplayer.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.DenseListItem
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.atv.nasplayer.data.ResumeStore
import dev.atv.nasplayer.data.ServerRepository
import dev.atv.nasplayer.data.parentPath
import dev.atv.nasplayer.playback.Engine
import dev.atv.nasplayer.images.AudioTagReader
import dev.atv.nasplayer.source.MediaBrowser
import dev.atv.nasplayer.source.MediaKind
import dev.atv.nasplayer.source.RemoteEntry
import kotlinx.coroutines.delay

private sealed interface ListState {
    data object Loading : ListState
    data class Loaded(val all: List<RemoteEntry>) : ListState
    data class Failed(val message: String) : ListState
}

@Composable
fun BrowserScreen(
    serverId: String,
    startPath: String?,
    onEditServer: () -> Unit,
    onLeave: () -> Unit,
) {
    val context = LocalContext.current
    val servers by ServerRepository.servers.collectAsState()
    val server = servers.firstOrNull { it.id == serverId }
    if (server == null) {
        LaunchedEffect(Unit) { onLeave() }
        return
    }
    val browser = remember(server) { MediaBrowser.forServer(server) }
    val history = remember(serverId) { mutableStateListOf(startPath ?: browser.rootPath) }
    val path = history.last()
    var state by remember { mutableStateOf<ListState>(ListState.Loading) }
    var reload by remember { mutableStateOf(0) }
    var focusPath by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<RemoteEntry?>(null) }
    var sortOrder by remember { mutableStateOf(dev.atv.nasplayer.data.Settings.sortOrder) }
    var sortMenu by remember { mutableStateOf(false) }
    val thumbs = remember { dev.atv.nasplayer.data.Settings.showThumbnails }
    val resumeVersion by ResumeStore.version.collectAsState()
    val listState = rememberLazyListState()
    val focusTarget = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }

    LaunchedEffect(path, reload, browser) {
        state = ListState.Loading
        state = try {
            ListState.Loaded(browser.list(path))
        } catch (e: Exception) {
            ListState.Failed(friendlyError(e))
        }
    }

    fun goUp() {
        if (history.size > 1) {
            focusPath = history.removeAt(history.lastIndex)
        } else if (path.trimEnd('/') != browser.rootPath.trimEnd('/') && path != "/") {
            // Opened deep (e.g. from "Continue watching"); walk up toward the root.
            focusPath = path
            history[0] = parentPath(path)
        } else {
            onLeave()
        }
    }

    BackHandler { goUp() }

    Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(server.name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (path == "/" || path.isEmpty()) "/" else path,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
            }
            OutlinedButton(onClick = { sortMenu = true }) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(sortOrder.label)
            }
        }
        Spacer(Modifier.height(8.dp))

        when (val s = state) {
            ListState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading…", style = MaterialTheme.typography.titleMedium)
            }
            is ListState.Failed -> {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("Couldn't open this folder", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(s.message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(max = 700.dp))
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(onClick = { reload++ }, modifier = Modifier.focusRequester(retryFocus)) { Text("Retry") }
                        OutlinedButton(onClick = onEditServer) { Text("Edit source") }
                    }
                }
                LaunchedEffect(s) { delay(50); runCatching { retryFocus.requestFocus() } }
            }
            is ListState.Loaded -> {
                val entries = remember(s, resumeVersion, sortOrder) { visibleEntries(s.all) }
                val albumFolder = remember(s) { dev.atv.nasplayer.source.isAlbumFolder(s.all) }
                if (entries.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No media in this folder", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    val targetIndex = entries.indexOfFirst { it.path == focusPath }.coerceAtLeast(0)
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        itemsIndexed(entries, key = { _, e -> e.path }) { i, entry ->
                            EntryRow(
                                serverId = server.id,
                                entry = entry,
                                showTags = albumFolder && entry.kind == MediaKind.AUDIO,
                                thumb = if (thumbs) dev.atv.nasplayer.images.Thumb(
                                    server.id, entry.path, entry.kind, entry.size, entry.modifiedMs, artworkFor(entry, s.all),
                                ) else null,
                                modifier = if (i == targetIndex) Modifier.focusRequester(focusTarget) else Modifier,
                                onClick = {
                                    when {
                                        entry.isDirectory -> { focusPath = null; history.add(entry.path) }
                                        entry.kind in dev.atv.nasplayer.source.MediaTypes.playable ->
                                            launchPlayback(context, server, path, s.all, entry)
                                        else -> launchPlayback(context, server, path, s.all, entry, Engine.VLC)
                                    }
                                },
                                onLongClick = { if (!entry.isDirectory) menuFor = entry },
                            )
                        }
                    }
                    LaunchedEffect(s) {
                        listState.scrollToItem(targetIndex)
                        delay(50)
                        runCatching { focusTarget.requestFocus() }
                    }
                }
            }
        }
    }

    if (sortMenu) {
        SortDialog(current = sortOrder, onDismiss = { sortMenu = false }) {
            sortOrder = it
            dev.atv.nasplayer.data.Settings.sortOrder = it
            sortMenu = false
        }
    }

    menuFor?.let { entry ->
        val loaded = state as? ListState.Loaded
        FileMenu(
            entry = entry,
            serverId = server.id,
            onDismiss = { menuFor = null },
            onPlay = { engine ->
                menuFor = null
                if (loaded != null) launchPlayback(context, server, path, loaded.all, entry, engine)
            },
            onStartOver = {
                menuFor = null
                ResumeStore.forget(server.id, entry.path)
                if (loaded != null) launchPlayback(context, server, path, loaded.all, entry)
            },
        )
    }
}

@Composable
private fun EntryRow(
    serverId: String,
    entry: RemoteEntry,
    showTags: Boolean,
    thumb: dev.atv.nasplayer.images.Thumb?,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val resume = if (entry.isDirectory) null else ResumeStore.get(serverId, entry.path)
    val context = LocalContext.current
    val tags by produceState(if (showTags) AudioTagReader.cached(serverId, entry) else null, entry, showTags) {
        if (showTags) value = AudioTagReader.read(context, serverId, entry)
    }
    val tagLine = tags?.let { t -> listOfNotNull(t.artist, t.album, t.year).joinToString(" · ") }.orEmpty()
    val duration = tags?.durationMs?.takeIf { it > 0 }
    DenseListItem(
        selected = false,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.height(46.dp),
        leadingContent = {
            val icon = when (entry.kind) {
                MediaKind.FOLDER -> Icons.Default.Folder
                MediaKind.VIDEO -> Icons.Default.Movie
                MediaKind.AUDIO -> Icons.Default.MusicNote
                MediaKind.IMAGE -> Icons.Default.Image
                else -> Icons.AutoMirrored.Filled.InsertDriveFile
            }
            if (thumb == null || entry.kind == MediaKind.OTHER) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            } else {
                Thumbnail(thumb, icon)
            }
        },
        headlineContent = {
            // The fixed row height leaves less than DenseListItem's padding expects; let two lines overflow into it.
            Column(Modifier.wrapContentHeight(unbounded = true)) {
                val title = tags?.title?.let { t -> listOfNotNull(tags?.track, t).joinToString("  ") } ?: entry.name
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (tagLine.isNotEmpty()) {
                    Text(
                        tagLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (resume != null && !resume.watched && resume.positionMs > 0) {
                    Spacer(Modifier.height(2.dp))
                    LinearProgressIndicator(
                        progress = { resume.progress },
                        modifier = Modifier.width(220.dp).height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.White.copy(alpha = 0.2f),
                    )
                }
            }
        },
        trailingContent = if (entry.isDirectory || (entry.size < 0 && resume?.watched != true)) null else {
            {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (resume?.watched == true) Icon(Icons.Default.CheckCircle, contentDescription = "Watched", modifier = Modifier.size(16.dp))
                    val info = duration?.let(::formatDuration) ?: entry.size.takeIf { it >= 0 }?.let(::formatSize)
                    if (info != null) Text(info, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}

@Composable
private fun FileMenu(
    entry: RemoteEntry,
    serverId: String,
    onDismiss: () -> Unit,
    onPlay: (Engine?) -> Unit,
    onStartOver: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val resume = ResumeStore.get(serverId, entry.path)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.width(520.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, modifier = Modifier.padding(8.dp))
                ListItem(selected = false, onClick = { onPlay(null) }, headlineContent = { Text("Play") },
                    modifier = Modifier.focusRequester(first))
                if (resume != null && resume.positionMs > 0) {
                    ListItem(selected = false, onClick = onStartOver, headlineContent = { Text("Play from the beginning") })
                }
                ListItem(selected = false, onClick = { onPlay(Engine.VLC) }, headlineContent = { Text("Play with VLC engine") },
                    supportingContent = { Text("Widest format support; software decoding") })
                ListItem(selected = false, onClick = { onPlay(Engine.EXO) }, headlineContent = { Text("Play with ExoPlayer engine") },
                    supportingContent = { Text("Hardware decoding, HDR, audio passthrough") })
                if (resume?.watched == true) {
                    ListItem(selected = false, onClick = { ResumeStore.forget(serverId, entry.path); onDismiss() },
                        headlineContent = { Text("Mark as unwatched") })
                } else {
                    ListItem(selected = false, onClick = {
                        ResumeStore.save(serverId, parentPath(entry.path), entry.name, 1, 1); onDismiss()
                    }, headlineContent = { Text("Mark as watched") })
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
}

private fun friendlyError(e: Exception): String {
    val msg = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.firstOrNull() ?: e.javaClass.simpleName
    return when {
        msg.contains("STATUS_LOGON_FAILURE") -> "The username or password was rejected."
        msg.contains("STATUS_ACCESS_DENIED") -> "This account doesn't have access here."
        msg.contains("STATUS_BAD_NETWORK_NAME") -> "That share doesn't exist on the server."
        msg.contains("STATUS_OBJECT_NAME_NOT_FOUND") || msg.contains("STATUS_OBJECT_PATH_NOT_FOUND") -> "That folder no longer exists."
        msg.contains("ECONNREFUSED") || msg.contains("Connection refused") -> "The server refused the connection. Is file sharing enabled?"
        msg.contains("timed out", true) || msg.contains("EHOSTUNREACH") -> "Couldn't reach the server. Is it powered on and on the same network?"
        e is java.net.UnknownHostException -> "Couldn't find that host name. Try the server's IP address."
        else -> msg
    }
}


@Composable
private fun Thumbnail(thumb: dev.atv.nasplayer.images.Thumb, fallback: androidx.compose.ui.graphics.vector.ImageVector) {
    val context = LocalContext.current
    Box(
        Modifier
            .size(width = 42.dp, height = 24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center,
    ) {
        coil3.compose.SubcomposeAsyncImage(
            model = coil3.request.ImageRequest.Builder(context).data(thumb).memoryCacheKey(thumb.cacheKey).build(),
            contentDescription = null,
            // Fit, not crop: posters and album art are portrait/square; letterbox them on the tile.
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(fallback, null, Modifier.size(18.dp)) } },
            error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(fallback, null, Modifier.size(18.dp)) } },
        )
    }
}

@Composable
private fun SortDialog(
    current: dev.atv.nasplayer.source.SortOrder,
    onDismiss: () -> Unit,
    onPick: (dev.atv.nasplayer.source.SortOrder) -> Unit,
) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.width(420.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Sort by", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
                dev.atv.nasplayer.source.SortOrder.entries.forEach { o ->
                    ListItem(
                        selected = o == current,
                        onClick = { onPick(o) },
                        headlineContent = { Text(o.label) },
                        modifier = if (o == current) Modifier.focusRequester(first) else Modifier,
                    )
                }
                Text("Folders are always listed first.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            }
        }
    }
    LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
}
