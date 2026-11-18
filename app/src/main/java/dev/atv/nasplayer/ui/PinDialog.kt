package dev.atv.nasplayer.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.atv.nasplayer.data.Pin
import kotlinx.coroutines.delay

/**
 * Asks for a [Pin.LENGTH]-digit PIN using the on-screen keypad or the remote's number keys.
 * [onEntered] returns an error message to show and clear the entry, or null to accept it.
 */
@Composable
fun PinDialog(title: String, onDismiss: () -> Unit, onEntered: (String) -> String?) {
    var entry by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var wait by remember { mutableStateOf(Pin.waitSeconds()) }
    val focus = remember { FocusRequester() }

    fun type(d: Char) {
        if (wait > 0 || entry.length >= Pin.LENGTH) return
        entry += d
        if (entry.length == Pin.LENGTH) {
            message = onEntered(entry)
            if (message != null) entry = ""
            wait = Pin.waitSeconds()
        }
    }
    LaunchedEffect(wait) { if (wait > 0) { delay(1000); wait = Pin.waitSeconds() } }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.onPreviewKeyEvent { ev ->
                val code = ev.nativeKeyEvent.keyCode
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (code) {
                    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> { type('0' + (code - AndroidKeyEvent.KEYCODE_0)); true }
                    AndroidKeyEvent.KEYCODE_DEL -> { entry = entry.dropLast(1); true }
                    else -> false
                }
            },
        ) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    "●".repeat(entry.length) + "○".repeat(Pin.LENGTH - entry.length),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    if (wait > 0) "Too many tries. Wait $wait s." else message ?: "Use the keypad or your remote's number keys.",
                    style = MaterialTheme.typography.bodySmall,
                )
                listOf("123", "456", "789").forEachIndexed { row, digits ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        digits.forEachIndexed { i, d ->
                            OutlinedButton(
                                onClick = { type(d) },
                                modifier = Modifier.width(72.dp).then(if (row == 0 && i == 0) Modifier.focusRequester(focus) else Modifier),
                            ) { Text(d.toString()) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.width(72.dp)) { Text("✕") }
                    OutlinedButton(onClick = { type('0') }, modifier = Modifier.width(72.dp)) { Text("0") }
                    Button(onClick = { entry = entry.dropLast(1) }, modifier = Modifier.width(72.dp)) { Text("⌫") }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(100); runCatching { focus.requestFocus() } }
}

/** Asks for the PIN, then runs [action]; runs it straight away when no PIN is needed. */
class PinGate {
    var pending by mutableStateOf<(() -> Unit)?>(null)
        private set

    fun run(needed: Boolean, action: () -> Unit) { if (needed) pending = action else action() }

    @Composable
    fun Prompt() {
        val action = pending ?: return
        PinDialog("Enter PIN", onDismiss = { pending = null }) { pin ->
            if (Pin.check(pin)) { pending = null; action(); null } else "Wrong PIN."
        }
    }
}
