package com.music.bitchord.localmix

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import moe.rukamori.archivetune.localmix.home.HomeLayout
import moe.rukamori.archivetune.localmix.home.HomeSection
import moe.rukamori.archivetune.localmix.home.SectionStyle

@Composable
private fun HomeSection.label(): String = stringResource(LocalMixHomeSections.titleOf(this))

@Composable
fun LocalMixHomeLayoutDialog(onDismiss: () -> Unit) {
    val layout by AppSettings.homeLayout.collectAsStateWithLifecycle()
    val favorites by AppSettings.localMixFavoritePlaylists.collectAsStateWithLifecycle()
    val playlists by ImportedPlaylistStore.playlists.collectAsStateWithLifecycle()
    var pickFavorites by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.localmix_home_layout_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.localmix_home_layout_description),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                layout.sections.forEachIndexed { index, setting ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(setting.section.label(), style = MaterialTheme.typography.bodyLarge)
                            if (setting.section == HomeSection.FAVORITE_PLAYLISTS) {
                                Text(
                                    stringResource(
                                        R.string.localmix_favorite_pick,
                                        playlists.count { it.id in favorites },
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clickable { pickFavorites = true }
                                        .padding(vertical = 4.dp),
                                )
                            }
                            // Recents keeps its own list/grid switch on the home screen.
                            if (setting.section != HomeSection.RECENTS) {
                                val grid = setting.style == SectionStyle.GRID
                                Text(
                                    stringResource(
                                        if (grid) R.string.localmix_home_style_grid else R.string.localmix_home_style_carousel,
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clickable {
                                            AppSettings.setHomeLayout(
                                                layout.withStyle(
                                                    setting.section,
                                                    if (grid) SectionStyle.CAROUSEL else SectionStyle.GRID,
                                                ),
                                            )
                                        }
                                        .padding(vertical = 4.dp),
                                )
                            }
                        }
                        IconButton(
                            onClick = { AppSettings.setHomeLayout(layout.moved(setting.section, -1)) },
                            enabled = index > 0,
                        ) {
                            Icon(
                                Icons.Rounded.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.localmix_home_move_up),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        IconButton(
                            onClick = { AppSettings.setHomeLayout(layout.moved(setting.section, 1)) },
                            enabled = index < layout.sections.lastIndex,
                        ) {
                            Icon(
                                Icons.Rounded.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.localmix_home_move_down),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Switch(
                            checked = setting.visible,
                            onCheckedChange = { AppSettings.setHomeLayout(layout.withVisible(setting.section, it)) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.localmix_home_layout_done)) }
        },
        dismissButton = {
            TextButton(onClick = { AppSettings.setHomeLayout(HomeLayout.DEFAULT) }) {
                Text(stringResource(R.string.localmix_home_layout_reset))
            }
        },
    )
    if (pickFavorites) {
        FavoritePlaylistsDialog(
            playlists = playlists,
            favorites = favorites,
            onDismiss = { pickFavorites = false },
        )
    }
}

@Composable
private fun FavoritePlaylistsDialog(
    playlists: List<ImportedPlaylist>,
    favorites: Set<String>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.localmix_favorite_pick_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (playlists.isEmpty()) {
                    Text(stringResource(R.string.localmix_favorite_pick_empty), style = MaterialTheme.typography.bodyMedium)
                }
                playlists.sortedBy { it.name.lowercase() }.forEach { playlist ->
                    val checked = playlist.id in favorites
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { AppSettings.setLocalMixFavoritePlaylist(playlist.id, !checked) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { AppSettings.setLocalMixFavoritePlaylist(playlist.id, it) },
                        )
                        Text(playlist.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.localmix_home_layout_done)) }
        },
    )
}
