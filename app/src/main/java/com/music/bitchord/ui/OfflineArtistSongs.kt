package com.music.bitchord.ui

import com.music.bitchord.data.model.Song
import com.music.bitchord.localmix.match.LocalMatchEntry
import moe.rukamori.archivetune.localmix.ArtistCredits

/** Builds an artist's device listing without changing files or their tags. */
internal fun offlineArtistSongs(
    browseId: String,
    title: String,
    downloaded: List<Song>,
    files: List<Song>,
    matches: List<LocalMatchEntry>,
): Pair<String, List<Song>>? {
    val name = downloaded.firstNotNullOfOrNull { song ->
        song.artists.firstOrNull { it.browseId == browseId }?.name
            ?: song.artist.takeIf { song.artistId == browseId }?.let(ArtistCredits::pageArtist)
    } ?: ArtistCredits.pageArtist(title) ?: return null
    fun byName(song: Song) = ArtistCredits.credits(song.artist, song.title, name) ||
        song.artists.any { ArtistCredits.credits(it.name, "", name) }

    val fromDownloads = downloaded.filter { song ->
        song.artistId == browseId || song.artists.any { it.browseId == browseId } || byName(song)
    }
    val byUri = files.mapNotNull { song -> song.localUri?.let { it to song } }.toMap()
    val fromMatches = matches.filterNot { it.uncertain }.mapNotNull { entry ->
        val file = byUri[entry.hit.uri] ?: return@mapNotNull null
        // A restored content URI may name another file on this device.
        if (entry.hit.path != null && entry.hit.path != file.localPath) return@mapNotNull null
        file.copy(
            title = entry.hit.title?.takeIf { it.isNotBlank() } ?: file.title,
            artist = entry.hit.artist?.takeIf { it.isNotBlank() } ?: file.artist,
        ).takeIf(::byName)
    }
    val fromFiles = files.mapNotNull { file ->
        if (byName(file)) return@mapNotNull file
        // Some taggers retained only the guest. A filename with an explicit
        // "Artist - Title" credit supplies the missing names, without guesses
        // from arbitrary album folders or partial name matches.
        val credit = file.localPath?.replace('\\', '/')?.substringAfterLast('/')
            ?.substringBefore(" - ", missingDelimiterValue = "").orEmpty()
        file.copy(artist = credit).takeIf { credit.isNotBlank() && byName(it) }
    }
    val songs = (fromDownloads + fromMatches + fromFiles)
        .distinctBy { it.localUri ?: it.videoId }
        .sortedBy { it.title.lowercase() }
    return if (songs.isEmpty()) null else name to songs
}
