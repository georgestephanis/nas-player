package dev.atv.nasplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.atv.nasplayer.BuildConfig
import dev.atv.nasplayer.R
import kotlinx.coroutines.launch

/** Licence notices and full licence texts (res/raw/licenses.txt), one focusable paragraph at a time so the D-pad scrolls it. */
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val paragraphs = remember {
        context.resources.openRawResource(R.raw.licenses).bufferedReader().use { it.readText() }
            .split(Regex("\n\\s*\n")).map { it.trimEnd() }.filter { it.isNotBlank() }
    }
    val firstLicence = remember { paragraphs.indexOfFirst { it.startsWith("====") }.takeIf { it >= 0 } ?: paragraphs.size }
    val first = remember { FocusRequester() }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp),
    ) {
        item { Text("About & licences", style = MaterialTheme.typography.headlineMedium) }
        item { Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium) }
        itemsIndexed(paragraphs) { i, p ->
            var focused by remember { mutableStateOf(false) }
            Text(
                p,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (i < firstLicence) null else FontFamily.Monospace, // licence texts are pre-wrapped
                modifier = Modifier
                    .widthIn(max = 900.dp)
                    .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                    .onFocusChanged {
                        focused = it.isFocused
                        if (i == 0 && it.isFocused) scope.launch { list.animateScrollToItem(0) } // headers aren't focusable; show them again
                    }
                    .focusable()
                    .background(
                        if (focused) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
