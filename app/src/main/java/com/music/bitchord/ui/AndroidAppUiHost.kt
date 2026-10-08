package com.music.bitchord.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.LibraryViewType
import com.music.bitchord.download.Downloads
import com.music.bitchord.localmix.match.LocalMatches

/** The shared pages' settings and downloads, answered from the phone's own stores. */
object AndroidAppUiHost : AppUiHost {
    override val swipeToPlayNext = AppSettings.swipeToPlayNext
    override val homeRecentsViewType = AppSettings.homeRecentsViewType
    override fun setHomeRecentsViewType(value: LibraryViewType) = AppSettings.setHomeRecentsViewType(value)
    override val pinnedPlaylists = AppSettings.pinnedPlaylists
    override val librarySort = AppSettings.librarySort

    @Composable
    override fun downloadedIds(): Set<String> {
        val saved by Downloads.saved.collectAsStateWithLifecycle()
        // LocalMix: catalogue tracks that are already on the device as the same
        // recording read as downloaded too (see LocalMatches).
        val matched by LocalMatches.matched.collectAsStateWithLifecycle()
        val preferLocal by AppSettings.preferLocalFiles.collectAsStateWithLifecycle()
        return remember(saved, matched, preferLocal) {
            if (!preferLocal || matched.isEmpty()) saved.keys else saved.keys + matched.keys
        }
    }
}
