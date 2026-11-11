package dev.atv.nasplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.atv.nasplayer.data.ServerConfig
import dev.atv.nasplayer.data.ServerRepository
import dev.atv.nasplayer.data.ServerType
import dev.atv.nasplayer.data.hostSuggestions
import dev.atv.nasplayer.data.knownPaths
import dev.atv.nasplayer.data.parentPath
import dev.atv.nasplayer.data.sameServer
import dev.atv.nasplayer.data.savedLogins
import dev.atv.nasplayer.discovery.NetworkDiscovery
import dev.atv.nasplayer.source.RemoteEntry
import dev.atv.nasplayer.source.MediaBrowser
import dev.atv.nasplayer.source.SmbSessionPool
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun ServerEditScreen(existingId: String?, prefill: ServerConfig?, onDone: () -> Unit) {
    val existing = remember(existingId) { existingId?.let { ServerRepository.get(it) } }
    val initial = existing ?: prefill
    val scope = rememberCoroutineScope()

    var type by remember { mutableStateOf(initial?.type ?: ServerType.SMB) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf(initial?.port?.toString() ?: "") }
    var path by remember { mutableStateOf(initial?.path ?: "") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var domain by remember { mutableStateOf(initial?.domain ?: "") }
    var insecureTls by remember { mutableStateOf(initial?.allowInsecureTls ?: false) }
    var showInContinueWatching by remember { mutableStateOf(initial?.showInContinueWatching ?: true) }
    var picking by remember { mutableStateOf<ServerConfig?>(null) }
    // The source a suggested login was copied from: that password must not follow a change of host.
    var loginFrom by remember { mutableStateOf<ServerConfig?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val adding = existing == null
    val discovered by remember { if (adding) NetworkDiscovery.discover(context) else emptyFlow() }.collectAsState(initial = emptyList())
    val others = ServerRepository.servers.collectAsState().value.filter { it.id != existing?.id }

    fun changeHost(value: String) {
        host = value
        loginFrom?.let {
            if (!sameServer(type, it.host, value, discovered)) {
                username = ""; password = ""; domain = ""; loginFrom = null
            }
        }
    }

    fun build(): ServerConfig? {
        if (host.isBlank()) { status = "Enter a host name or IP address."; return null }
        return ServerConfig(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = name.ifBlank { host.substringAfter("://").substringBefore('/') },
            type = type,
            host = host.trim(),
            port = port.trim().toIntOrNull(),
            path = path.trim().trim('/'),
            username = username.trim(),
            password = password,
            domain = domain.trim(),
            allowInsecureTls = insecureTls,
            // Keep the remembered certificate only while the setting and the server stay the same.
            pinnedCert = existing?.takeIf { insecureTls && it.allowInsecureTls && it.host == host.trim() }?.pinnedCert ?: "",
            showInContinueWatching = showInContinueWatching,
        )
    }

    // Plain scrolling Column (not LazyColumn): inserting the status line must not shift focus.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(if (existing != null) "Edit source" else "Add source", style = MaterialTheme.typography.headlineMedium)
        run {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ServerType.entries.filter { it != ServerType.USB }.forEachIndexed { i, t ->
                    FilterChip(
                        selected = type == t,
                        onClick = {
                            if (t != type) {
                                type = t
                                loginFrom?.let { username = ""; password = ""; domain = ""; loginFrom = null }
                            }
                        },
                        modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    ) { Text(t.label) }
                }
            }
        }
        Field("Display name", name, { name = it }, placeholder = "Living room NAS")
        run {
            Field(
                if (type == ServerType.SMB) "Host or IP address" else "WebDAV URL",
                host, ::changeHost,
                placeholder = if (type == ServerType.SMB) "192.168.1.20 or nas.local" else "http://192.168.1.20:5005/video",
                keyboardType = if (type == ServerType.SMB) KeyboardType.Uri else KeyboardType.Uri,
            )
        }
        if (adding) {
            Suggestions("Suggested", hostSuggestions(type, others, discovered)) { h ->
                FilterChip(selected = host == h.host, onClick = {
                    changeHost(h.host)
                    port = h.port?.toString() ?: ""
                    if (name.isBlank()) name = h.name
                }) { Text(h.label) }
            }
        }
        Field("Port (optional)", port, { port = it.filter(Char::isDigit) }, placeholder = if (type == ServerType.SMB) "445" else "", keyboardType = KeyboardType.Number)
        if (adding) {
            Suggestions("Saved login for this server", savedLogins(type, host, others, discovered)) { s ->
                FilterChip(selected = loginFrom?.id == s.id, onClick = {
                    username = s.username; password = s.password; domain = s.domain; loginFrom = s
                }) { Text("${s.username.ifEmpty { "No username" }} · from ${s.name}") }
            }
        }
        Field("Username (blank for guest)", username, { username = it })
        Field("Password", password, { password = it }, password = true)
        if (type == ServerType.SMB) {
            Field("Domain / workgroup (optional)", domain, { domain = it })
        } else {
            run {
                ListItem(
                    selected = false,
                    onClick = { insecureTls = !insecureTls },
                    headlineContent = { Text("Accept self-signed HTTPS certificates") },
                    supportingContent = { Text("Trusts the first certificate this server presents, and only that one") },
                    trailingContent = { Switch(checked = insecureTls, onCheckedChange = { insecureTls = it }) },
                    modifier = Modifier.widthIn(max = 720.dp),
                )
            }
        }
        if (type == ServerType.SMB) {
            Field("Share / folder (optional)", path, { path = it }, placeholder = "Media/Movies — leave blank to list all shares")
            Suggestions("Known folders", knownPaths(type, host, others, discovered)) { p ->
                FilterChip(selected = path.trim('/') == p, onClick = { path = p }) { Text(p) }
            }
            OutlinedButton(onClick = { picking = build() }) { Text("Browse folders…") }
        }
        ListItem(
            selected = false,
            onClick = { showInContinueWatching = !showInContinueWatching },
            headlineContent = { Text("Show in Continue watching") },
            supportingContent = { Text("When off, its files stay off the home screen. Your place in them is still remembered.") },
            trailingContent = { Switch(checked = showInContinueWatching, onCheckedChange = { showInContinueWatching = it }) },
            // Without this, Down from this wide row lands on Test connection or Remove.
            modifier = Modifier.widthIn(max = 720.dp).focusProperties { down = saveFocus },
        )
        status?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary) }
        run {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = {
                    build()?.let { ServerRepository.save(it); SmbSessionPool.invalidate(it.id); onDone() }
                }, modifier = Modifier.focusRequester(saveFocus)) { Text("Save") }
                // Not disabled while testing: disabling the focused button would drop D-pad focus.
                OutlinedButton(onClick = {
                    if (testing) return@OutlinedButton
                    // Throwaway id: the test never shares (or blocks) the saved server's session.
                    val cfg = build()?.copy(id = "test-" + java.util.UUID.randomUUID()) ?: return@OutlinedButton
                    testing = true
                    status = "Connecting…"
                    scope.launch {
                        status = try {
                            SmbSessionPool.invalidate(cfg.id)
                            val b = MediaBrowser.forServer(cfg)
                            val n = b.list(b.rootPath).size
                            "Connected. Found $n item${if (n == 1) "" else "s"}."
                        } catch (e: Exception) {
                            "Failed: " + failureMessage(e)
                        } finally {
                            SmbSessionPool.invalidate(cfg.id)
                            testing = false
                        }
                    }
                }) { Text(if (testing) "Testing…" else "Test connection") }
                if (existing != null) {
                    OutlinedButton(onClick = {
                        if (confirmDelete) { ServerRepository.delete(existing.id); SmbSessionPool.invalidate(existing.id); onDone() }
                        else { confirmDelete = true; status = "Press Remove again to confirm." }
                    }) { Text(if (confirmDelete) "Confirm remove" else "Remove") }
                }
            }
        }
    }

    picking?.let { cfg ->
        FolderPicker(cfg, onDismiss = { picking = null }, onPick = { path = it; picking = null })
    }

    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
}

private fun failureMessage(e: Exception): String =
    generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.firstOrNull() ?: e.javaClass.simpleName

@Composable
private fun <T> Suggestions(title: String, items: List<T>, chip: @Composable (T) -> Unit) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.bodySmall)
        // Padding inside the scroll, or the focused chip's zoom is clipped.
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items.forEach { chip(it) }
        }
    }
}

/** Lists the server's folders using the login currently in the form. Back goes up a level. */
@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun FolderPicker(form: ServerConfig, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    // Throwaway id, as in Test connection: never shares the saved server's session.
    val server = remember { form.copy(id = "pick-" + java.util.UUID.randomUUID(), path = "") }
    val browser = remember { MediaBrowser.forServer(server) }
    var current by remember { mutableStateOf("/" + form.path) }
    var folders by remember { mutableStateOf<List<RemoteEntry>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

    DisposableEffect(Unit) { onDispose { SmbSessionPool.invalidate(server.id) } }
    LaunchedEffect(current) {
        folders = null
        failure = null
        try {
            folders = browser.list(current).filter { it.isDirectory }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = failureMessage(e)
        }
    }
    LaunchedEffect(folders, failure) {
        if (folders != null || failure != null) { kotlinx.coroutines.delay(50); runCatching { focus.requestFocus() } }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.activity.compose.BackHandler(enabled = current != "/") { current = parentPath(current) }
        androidx.tv.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(24.dp).widthIn(min = 480.dp, max = 640.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(current, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                val list = folders
                when {
                    failure != null -> Text("Couldn't list folders: $failure", style = MaterialTheme.typography.bodyLarge)
                    list == null -> Text("Connecting…", style = MaterialTheme.typography.bodyLarge)
                    list.isEmpty() -> Text("No folders here.", style = MaterialTheme.typography.bodyLarge)
                    else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(list, key = { it.path }) { f ->
                            ListItem(
                                selected = false,
                                onClick = { current = f.path },
                                headlineContent = { Text(f.name, maxLines = 1) },
                                modifier = if (f == list.first()) Modifier.focusRequester(focus) else Modifier,
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Wide, so Down from the list lands here rather than on Cancel.
                    Button(
                        onClick = { onPick(current.trim('/')) },
                        modifier = Modifier.weight(1f).then(if (list.isNullOrEmpty()) Modifier.focusRequester(focus) else Modifier),
                    ) { Text(if (current == "/") "Use all shares" else "Use this folder") }
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

/**
 * TV-friendly text field: a focusable row showing the value. OK opens an edit dialog, so D-pad
 * navigation through the form never gets trapped by the on-screen keyboard.
 */
@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var editing by remember { mutableStateOf(false) }
    ListItem(
        selected = false,
        onClick = { editing = true },
        headlineContent = { Text(label) },
        supportingContent = {
            Text(
                when {
                    value.isEmpty() -> placeholder.ifEmpty { "Not set" }
                    password -> "\u2022".repeat(value.length.coerceAtMost(12))
                    else -> value
                },
                maxLines = 1,
            )
        },
        modifier = Modifier.widthIn(max = 720.dp),
    )
    if (editing) {
        EditDialog(label, value, placeholder, password, keyboardType,
            onDismiss = { editing = false },
            onConfirm = { onChange(it); editing = false })
    }
}

@Composable
private fun EditDialog(
    label: String,
    initial: String,
    placeholder: String,
    password: Boolean,
    keyboardType: KeyboardType,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.tv.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(24.dp).widthIn(min = 480.dp, max = 640.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { androidx.compose.material3.Text(label) },
                    placeholder = if (placeholder.isNotEmpty()) ({ androidx.compose.material3.Text(placeholder) }) else null,
                    singleLine = true,
                    visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (password) KeyboardType.Password else keyboardType,
                        imeAction = ImeAction.Done,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onConfirm(text.text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { ev ->
                        // Hardware/remote Enter confirms too.
                        if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyUp &&
                            (ev.key == androidx.compose.ui.input.key.Key.Enter || ev.key == androidx.compose.ui.input.key.Key.NumPadEnter)
                        ) { onConfirm(text.text); true } else false
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 16.dp)) {
                    Button(onClick = { onConfirm(text.text) }) { Text("OK") }
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(100); runCatching { focus.requestFocus() } }
}
