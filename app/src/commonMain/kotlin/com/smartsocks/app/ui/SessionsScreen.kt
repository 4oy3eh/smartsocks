package com.smartsocks.app.ui

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartsocks.app.platform.SessionActions
import com.smartsocks.app.playback.OpenState
import com.smartsocks.app.playback.PlayerController
import com.smartsocks.app.playback.SessionList
import com.smartsocks.app.playback.SessionsController
import com.smartsocks.app.recording.SessionNames
import com.smartsocks.app.recording.StoredSession
import kotlinx.coroutines.launch

@Composable
internal fun SessionsTopBar(onBack: () -> Unit, actions: SessionActions) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = "Back") }
            Spacer(Modifier.width(4.dp))
            Text("Sessions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (actions.canShowInFolder) {
                IconButton(onClick = { actions.showInFolder(null) }) {
                    Icon(AppIcons.Folder, contentDescription = "Open the recordings folder")
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * Recordings, newest first. A tap opens one in the player ([onOpened] once it is read); the menu renames, deletes
 * (after asking), shares or shows it in its folder, as the platform allows.
 */
@Composable
fun SessionsScreen(
    sessions: SessionsController,
    actions: SessionActions,
    compact: Boolean,
    onOpened: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val list by sessions.list.collectAsState()
    val opening by sessions.opening.collectAsState()
    val message by sessions.message.collectAsState()
    val activeRecording by sessions.activeRecording.collectAsState()
    var renaming by remember { mutableStateOf<StoredSession?>(null) }
    var deleting by remember { mutableStateOf<StoredSession?>(null) }
    LaunchedEffect(sessions) { sessions.refresh() }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        when (val l = list) {
            SessionList.Loading -> CircularProgressIndicator(Modifier.padding(top = 48.dp))

            is SessionList.Failed -> EmptyState("Cannot read the recordings", l.message)

            is SessionList.Loaded -> if (l.sessions.isEmpty()) {
                EmptyState(
                    "No recordings yet",
                    "On the live screen, connect a foot and press Record. Press Mark while recording to flag a " +
                        "moment; stop, and the session appears here to play back.\n\nSaved to " +
                        sessions.store.locationText,
                )
            } else {
                LazyColumn(
                    Modifier.widthIn(max = 840.dp).fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = if (compact) 16.dp else 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            "${l.sessions.size} recording${if (l.sessions.size == 1) "" else "s"} in " +
                                sessions.store.locationText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                        )
                    }
                    items(l.sessions, key = { it.id }) { session ->
                        SessionRow(
                            session,
                            recording = session.id == activeRecording,
                            actions,
                            onOpen = { sessions.open(session, onOpened) },
                            onRename = { renaming = session },
                            onDelete = { deleting = session },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { s -> RenameDialog(s, sessions, onDone = { renaming = null }) }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this recording?") },
            text = {
                Text(
                    "${SessionNames.display(s.id)} will be deleted" +
                        (if (s.hasRaw) " with its raw packet file" else "") + ". This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    sessions.delete(s)
                    deleting = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    when (val o = opening) {
        is OpenState.Reading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Opening ${SessionNames.display(o.session.id)}") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { o.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${(o.progress * 100).toInt()}% of ${sizeText(o.session.sizeBytes)}")
                }
            },
            confirmButton = { TextButton(onClick = sessions::cancelOpen) { Text("Cancel") } },
        )

        is OpenState.Failed -> AlertDialog(
            onDismissRequest = sessions::dismissOpenError,
            title = { Text("Cannot open ${SessionNames.display(o.session.id)}") },
            text = { Text(o.message) },
            confirmButton = { TextButton(onClick = sessions::dismissOpenError) { Text("OK") } },
        )

        null -> Unit
    }
    message?.let { m ->
        AlertDialog(
            onDismissRequest = sessions::dismissMessage,
            text = { Text(m) },
            confirmButton = { TextButton(onClick = sessions::dismissMessage) { Text("OK") } },
        )
    }
}

@Composable
private fun EmptyState(title: String, text: String) {
    Column(
        Modifier.widthIn(max = 460.dp).padding(horizontal = 24.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            AppIcons.Sessions,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** "1:42 · Both feet · 200 Hz · 3.1 MB · 2 marks". */
internal fun sessionDetails(s: StoredSession): String = buildList {
    add(s.durationMillis?.let { PlayerController.timeText(it, tenths = false) } ?: "Duration unknown")
    add(feetText(s.feet))
    s.header.rateHz?.let { add("$it Hz") }
    add(sizeText(s.sizeBytes))
    s.summary?.markers?.takeIf { it > 0 }?.let { add(if (it == 1) "1 mark" else "$it marks") }
}.joinToString(" · ")

@Composable
private fun SessionRow(
    s: StoredSession,
    /** Still being written: no play, rename or delete. */
    recording: Boolean,
    actions: SessionActions,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onOpen,
        enabled = !recording,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onOpen, enabled = !recording) {
                if (recording) {
                    Icon(
                        AppIcons.Record,
                        contentDescription = "Recording",
                        tint = LocalSockColors.current.error,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Icon(AppIcons.Play, contentDescription = "Play", modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    SessionNames.display(s.id),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (recording) "Recording…" else sessionDetails(s),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menu = true }, enabled = !recording) {
                    Icon(AppIcons.More, contentDescription = "More")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = {
                        menu = false
                        onRename()
                    })
                    if (actions.canShare) {
                        DropdownMenuItem(
                            text = { Text("Share") },
                            leadingIcon = { Icon(AppIcons.Share, contentDescription = null) },
                            onClick = {
                                menu = false
                                actions.share(s)
                            },
                        )
                    }
                    if (actions.canShowInFolder) {
                        DropdownMenuItem(
                            text = { Text("Show in folder") },
                            leadingIcon = { Icon(AppIcons.Folder, contentDescription = null) },
                            onClick = {
                                menu = false
                                actions.showInFolder(s)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(s: StoredSession, sessions: SessionsController, onDone: () -> Unit) {
    var name by remember(s.id) { mutableStateOf(s.id) }
    var error by remember(s.id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val problem = SessionNames.problem(name)
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Rename recording") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    error = null
                },
                singleLine = true,
                isError = problem != null || error != null,
                supportingText = { Text(error ?: problem ?: "The files are renamed too.") },
            )
        },
        confirmButton = {
            TextButton(
                enabled = problem == null && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        error = sessions.rename(s, name)
                        busy = false
                        if (error == null) onDone()
                    }
                },
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
