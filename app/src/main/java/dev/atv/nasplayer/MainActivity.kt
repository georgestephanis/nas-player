package dev.atv.nasplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import dev.atv.nasplayer.data.ServerConfig
import dev.atv.nasplayer.ui.BrowserScreen
import dev.atv.nasplayer.ui.HomeScreen
import dev.atv.nasplayer.ui.NasTheme
import dev.atv.nasplayer.ui.ServerEditScreen
import dev.atv.nasplayer.ui.SettingsScreen

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data class Edit(val existingId: String?, val prefill: ServerConfig?) : Screen
    data class Browse(val serverId: String, val startPath: String?) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NasTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    App(onExit = ::finish)
                }
            }
        }
    }
}

@Composable
private fun App(onExit: () -> Unit) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onExit() }

    BackHandler(enabled = stack.size > 1) { pop() }

    when (val screen = stack.last()) {
        Screen.Home -> HomeScreen(
            onOpenServer = { push(Screen.Browse(it.id, null)) },
            onEditServer = { push(Screen.Edit(it.id, null)) },
            onAddServer = { prefill -> push(Screen.Edit(null, prefill)) },
            onSettings = { push(Screen.Settings) },
        )
        Screen.Settings -> SettingsScreen()
        is Screen.Edit -> ServerEditScreen(
            existingId = screen.existingId,
            prefill = screen.prefill,
            onDone = { pop() },
        )
        is Screen.Browse -> BrowserScreen(
            serverId = screen.serverId,
            startPath = screen.startPath,
            onEditServer = { push(Screen.Edit(screen.serverId, null)) },
            onLeave = { pop() },
        )
    }
}
