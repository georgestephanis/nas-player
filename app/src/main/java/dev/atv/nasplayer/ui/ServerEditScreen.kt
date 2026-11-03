package dev.atv.nasplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import dev.atv.nasplayer.source.MediaBrowser
import dev.atv.nasplayer.source.SmbSessionPool
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
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }

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
                ServerType.entries.forEachIndexed { i, t ->
                    FilterChip(
                        selected = type == t,
                        onClick = { type = t },
                        modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    ) { Text(t.label) }
                }
            }
        }
        Field("Display name", name, { name = it }, placeholder = "Living room NAS")
        run {
            Field(
                if (type == ServerType.SMB) "Host or IP address" else "WebDAV URL",
                host, { host = it },
                placeholder = if (type == ServerType.SMB) "192.168.1.20 or nas.local" else "http://192.168.1.20:5005/video",
                keyboardType = if (type == ServerType.SMB) KeyboardType.Uri else KeyboardType.Uri,
            )
        }
        if (type == ServerType.SMB) {
            run {
                Field("Share / folder (optional)", path, { path = it }, placeholder = "Media/Movies — leave blank to list all shares")
            }
        }
        Field("Port (optional)", port, { port = it.filter(Char::isDigit) }, placeholder = if (type == ServerType.SMB) "445" else "", keyboardType = KeyboardType.Number)
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
                    trailingContent = { Switch(checked = insecureTls, onCheckedChange = { insecureTls = it }) },
                    modifier = Modifier.widthIn(max = 720.dp),
                )
            }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary) }
        run {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = {
                    build()?.let { ServerRepository.save(it); SmbSessionPool.invalidate(it.id); onDone() }
                }) { Text("Save") }
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
                            "Failed: " + (generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.firstOrNull() ?: e.javaClass.simpleName)
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

    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
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
