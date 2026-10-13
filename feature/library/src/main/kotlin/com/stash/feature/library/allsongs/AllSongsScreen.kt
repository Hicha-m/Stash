package com.stash.feature.library.allsongs

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.common.extensions.pluralize
import com.stash.core.model.Track
import com.stash.core.ui.components.ListenTogetherRole
import com.stash.core.ui.components.LocalListenTogetherRole
import com.stash.core.ui.components.TrackListItem
import com.stash.core.ui.components.TrackRowFitScope
import com.stash.core.ui.components.VerticalScrollbar
import com.stash.core.ui.components.bottomChromePadding
import com.stash.core.ui.components.fastScrollSectionLetter
import com.stash.core.ui.selection.SelectionAction
import com.stash.core.ui.selection.SelectionScaffoldOverlay
import com.stash.core.ui.selection.rememberSelectionState
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.library.LibrarySortFilterSheet
import com.stash.feature.library.SortOrder
import com.stash.feature.library.SourceFilter

@Composable
fun AllSongsScreen(
    modifier: Modifier = Modifier,
    // Hides the mini player and tab bar while songs are selected (as the Library does).
    onSelectionModeChanged: (Boolean) -> Unit = {},
    viewModel: AllSongsViewModel = hiltViewModel(),
) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val playingId by viewModel.playingId.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()

    var showFolders by rememberSaveable { mutableStateOf(false) }
    var showSort by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val listState = rememberLazyListState()
    val alphabetical = sortOrder == SortOrder.ALPHABETICAL

    val selection = rememberSelectionState()
    LaunchedEffect(selection.isActive) { onSelectionModeChanged(selection.isActive) }
    // A search that hides selected songs must not leave them selected: Delete acts on what is shown.
    LaunchedEffect(query) { selection.clear() }
    BackHandler(enabled = selection.isActive) { selection.clear() }
    val selected = songs.filter { it.id in selection.selectedIds }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.addFolder(uri)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "All Songs",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = viewModel::shuffle, enabled = songs.isNotEmpty()) {
                    Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle songs", tint = MaterialTheme.colorScheme.primary)
                }
                // Closing the search clears it: a hidden field over a filtered list is a dead end.
                IconButton(onClick = {
                    if (searchOpen) viewModel.setQuery("")
                    searchOpen = !searchOpen
                }) {
                    Icon(Icons.Filled.Search, contentDescription = "Search songs")
                }
                IconButton(onClick = { showSort = true }) {
                    Icon(Icons.Filled.Tune, contentDescription = "Sort")
                }
                IconButton(onClick = { showFolders = true }) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = "Scan folders")
                }
            }

            if (scanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
            }
            status?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            if (searchOpen) {
                AllSongsSearchBar(
                    query = query,
                    onQueryChange = viewModel::setQuery,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            if (songs.isEmpty() && !scanning) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(32.dp)
                        .padding(bottom = bottomChromePadding(0.dp)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (query.isNotBlank()) {
                        Text(
                            text = "No songs match \"$query\"",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { viewModel.setQuery("") }) { Text("Clear search") }
                    } else {
                        Text(
                            text = if (folders.isEmpty()) "Pick the folders to scan for music" else "No songs found in your folders",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { showFolders = true }) { Text("Choose folders") }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    TrackRowFitScope {
                        LazyColumn(
                            state = listState,
                            // While selecting, the bottom selection bar takes the mini player's place.
                            contentPadding = PaddingValues(
                                bottom = bottomChromePadding(if (selection.isActive) 140.dp else 0.dp),
                            ),
                        ) {
                            items(songs, key = { it.id }) { track ->
                                TrackListItem(
                                    track = track,
                                    onClick = {
                                        if (selection.isActive) selection.toggle(track.id)
                                        else viewModel.play(track.id)
                                    },
                                    isPlaying = track.id == playingId,
                                    onLongPress = { if (!selection.isActive) selection.enter(track.id) },
                                    selectionActive = selection.isActive,
                                    selected = selection.isSelected(track.id),
                                )
                            }
                        }
                    }
                    // The letter bubble only means something when the list runs A–Z.
                    VerticalScrollbar(
                        state = listState,
                        sectionLabel = if (alphabetical) titleSectionLabel(songs) else null,
                    )
                }
            }
        }

        // Scanned songs can't be queued into a Listen Together session (they stay on this phone).
        val listenRole = LocalListenTogetherRole.current
        val canQueue = listenRole == ListenTogetherRole.NONE
        val actions = listOfNotNull(
            SelectionAction("play_next", "Play next", Icons.Default.PlaylistPlay) {
                viewModel.playNext(selected); selection.clear()
            }.takeIf { canQueue },
            SelectionAction("add_queue", "Add to queue", Icons.Default.PlaylistAdd) {
                viewModel.addToQueue(selected); selection.clear()
            }.takeIf { canQueue },
            SelectionAction("share", "Share", Icons.Default.Share) {
                shareSongs(context, selected); selection.clear()
            },
            SelectionAction("delete", "Delete", Icons.Default.Delete) {
                confirmDelete = true
            },
        )
        SelectionScaffoldOverlay(
            selection = selection,
            allIds = remember(songs) { songs.map { it.id } },
            actions = actions,
        )
    }

    if (showSort) {
        LibrarySortFilterSheet(
            sortOrder = sortOrder,
            sourceFilter = SourceFilter.ALL,
            showDuration = true,
            showSourceFilter = false,
            showMostPlayed = false,
            onSortSelected = viewModel::setSortOrder,
            onFilterSelected = {},
            onDismiss = { showSort = false },
        )
    }

    if (confirmDelete) {
        val doomed = selected
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${pluralize(doomed.size, "song")}?") },
            text = { Text("The files are deleted from your phone. This can't be undone.") },
            confirmButton = {
                TextButton(
                    enabled = doomed.isNotEmpty(),
                    onClick = {
                        viewModel.deleteSongs(doomed)
                        confirmDelete = false
                        selection.clear()
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    if (showFolders) {
        AlertDialog(
            onDismissRequest = { showFolders = false },
            title = { Text("Scan folders") },
            text = {
                Column {
                    if (folders.isEmpty()) {
                        Text("No folders yet. Songs in the folders you add (and their subfolders) show up in All Songs.")
                    }
                    folders.forEach { folder ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = folder.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { viewModel.removeFolder(folder) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove ${folder.displayName}")
                            }
                        }
                    }
                    if (folders.isNotEmpty()) {
                        TextButton(onClick = viewModel::rescan, enabled = !scanning) { Text("Rescan now") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picker.launch(null) }) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add folder")
                }
            },
            dismissButton = { TextButton(onClick = { showFolders = false }) { Text("Done") } },
        )
    }
}

/** The scrollbar bubble's letter for the row at [index]: the first letter of that song's title. */
private fun titleSectionLabel(songs: List<Track>): (Int) -> String? = { index ->
    songs.getOrNull(index)?.let { fastScrollSectionLetter(it.title) }
}

/** The system share sheet with the song files themselves (a read grant rides along for the receiver). */
private fun shareSongs(context: Context, tracks: List<Track>) {
    val uris = tracks.mapNotNull { it.filePath?.let(Uri::parse) }
    if (uris.isEmpty()) return
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    intent.type = "audio/*"
    intent.clipData = ClipData.newRawUri(null, uris[0]).also { clip ->
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, "Share")) }
}

@Composable
private fun AllSongsSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        color = extendedColors.glassBackground,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search songs...", color = extendedColors.textTertiary) },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = "Search", tint = extendedColors.textTertiary)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = extendedColors.textTertiary)
                    }
                }
            },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}