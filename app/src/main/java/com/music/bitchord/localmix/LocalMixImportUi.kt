package com.music.bitchord.localmix

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.ui.screens.LibraryLink

/** Browse id of the Library row that opens the import dialog; never a page. */
const val LOCALMIX_IMPORT_LINK_ID = "localmix:import"

@Composable
fun localMixImportLink(): LibraryLink = LibraryLink(
    item = ShelfItem(
        title = stringResource(R.string.localmix_import_title),
        subtitle = "",
        thumbnailUrl = null,
        videoId = null,
        browseId = LOCALMIX_IMPORT_LINK_ID,
    ),
    icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
)

@Composable
fun localMixLibraryItems(): List<ShelfItem> {
    val playlists by ImportedPlaylistStore.playlists.collectAsStateWithLifecycle()
    return playlists.sortedBy { it.name.lowercase() }.map { playlist ->
        ShelfItem(
            title = playlist.name,
            subtitle = pluralStringResource(R.plurals.track_count_plural, playlist.entries.size, playlist.entries.size),
            thumbnailUrl = playlist.coverUrl,
            videoId = null,
            browseId = playlist.browseId,
        )
    }
}

class LocalMixPickers(
    val pickFiles: () -> Unit,
    val pickFolder: () -> Unit,
)

@Composable
fun rememberLocalMixPickers(
    onFiles: (List<Uri>) -> Unit,
    onFolder: (Uri) -> Unit,
): LocalMixPickers {
    val files by rememberUpdatedState(onFiles)
    val folder by rememberUpdatedState(onFolder)
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) files(uris)
    }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) folder(uri)
    }
    return LocalMixPickers(
        // Several providers type .m3u8 as audio/x-mpegurl, some as
        // application/vnd.apple.mpegurl, and some as octet-stream; the file
        // name is checked again after picking.
        pickFiles = {
            documents.launch(
                arrayOf(
                    "audio/x-mpegurl",
                    "audio/mpegurl",
                    "application/x-mpegurl",
                    "application/vnd.apple.mpegurl",
                    "application/octet-stream",
                    "text/plain",
                    "*/*",
                ),
            )
        },
        pickFolder = { tree.launch(null) },
    )
}

@Composable
fun LocalMixImportDialog(
    importing: Boolean,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!importing) onDismiss() },
        title = { Text(stringResource(R.string.localmix_import_title)) },
        text = {
            if (importing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.localmix_importing))
                }
            } else {
                Column {
                    Text(stringResource(R.string.localmix_import_description))
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onPickFiles, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.localmix_import_files))
                    }
                    TextButton(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.localmix_import_folder))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            if (!importing) TextButton(onClick = onDismiss) { Text(stringResource(R.string.localmix_cancel)) }
        },
    )
}

@Composable
fun LocalMixPlaylistActionsDialog(
    playlist: ImportedPlaylist,
    busy: Boolean,
    onReimport: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val favorites by com.music.bitchord.data.settings.AppSettings.localMixFavoritePlaylists.collectAsStateWithLifecycle()
    val favorite = playlist.id in favorites
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(playlist.name) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.localmix_playlist_summary,
                        playlist.availableCount,
                        playlist.entries.size,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(stringResource(R.string.localmix_importing))
                    }
                } else {
                    TextButton(
                        onClick = {
                            com.music.bitchord.data.settings.AppSettings.setLocalMixFavoritePlaylist(playlist.id, !favorite)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (favorite) R.string.localmix_favorite_remove else R.string.localmix_favorite_add,
                            ),
                        )
                    }
                    TextButton(onClick = onReimport, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.localmix_reimport))
                    }
                    TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.localmix_delete))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            if (!busy) TextButton(onClick = onDismiss) { Text(stringResource(R.string.localmix_cancel)) }
        },
    )
}

@Composable
fun rememberImportSummary(): (LocalMixImportResult) -> String {
    val context = LocalContext.current
    return { result ->
        val tracks = result.imported.sumOf { it.entries.size }
        val missing = result.imported.sumOf { it.missingCount }
        buildString {
            append(
                context.resources.getQuantityString(
                    R.plurals.localmix_imported_playlists,
                    result.imported.size,
                    result.imported.size,
                    tracks,
                ),
            )
            if (missing > 0) {
                append(" · ")
                append(context.resources.getQuantityString(R.plurals.localmix_missing_count, missing, missing))
            }
            if (result.failed.isNotEmpty()) {
                append(" · ")
                append(context.getString(R.string.localmix_import_failed, result.failed.joinToString { it.first.ifBlank { it.second } }))
            }
        }
    }
}
