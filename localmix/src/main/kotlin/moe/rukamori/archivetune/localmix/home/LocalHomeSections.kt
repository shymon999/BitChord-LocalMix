package moe.rukamori.archivetune.localmix.home

import java.util.Locale

/** A track from the listening record: how often and how recently it was played. */
data class PlayedTrack(
    val id: String,
    val plays: Int,
    val listenedMs: Long,
    val lastPlayedAt: Long,
)

/** A file on the device: who it is credited to and when MediaStore first saw it. */
data class DiskTrack(
    val id: String,
    val artist: String,
    val addedAtSeconds: Long,
)

/** An artist with files on the device, for the "Your artists" section. */
data class DiskArtist(
    val key: String,
    val name: String,
    /** Ids of that artist's files, newest first. */
    val trackIds: List<String>,
)

/**
 * The ranking behind the home sections built from local data. Pure: the app
 * hands in what it read from the listening record and MediaStore, and turns
 * the ids that come back into cards.
 */
object LocalHomeSections {
    /** Played at least once, most recent first. */
    fun recentlyPlayed(tracks: List<PlayedTrack>, limit: Int): List<String> =
        tracks.asSequence()
            .filter { it.lastPlayedAt > 0 }
            .sortedByDescending { it.lastPlayedAt }
            .map { it.id }
            .distinct()
            .take(limit)
            .toList()

    /**
     * Whole listens first, then minutes, then recency: a track skipped many
     * times does not outrank one played through.
     */
    fun mostPlayed(tracks: List<PlayedTrack>, limit: Int): List<String> =
        tracks.asSequence()
            .filter { it.plays > 0 }
            .sortedWith(
                compareByDescending<PlayedTrack> { it.plays }
                    .thenByDescending { it.listenedMs }
                    .thenByDescending { it.lastPlayedAt },
            )
            .map { it.id }
            .distinct()
            .take(limit)
            .toList()

    /** Newest files first; ties keep MediaStore's order. */
    fun recentlyAdded(files: List<DiskTrack>, limit: Int): List<String> =
        files.asSequence()
            .filter { it.addedAtSeconds > 0 }
            .sortedByDescending { it.addedAtSeconds }
            .map { it.id }
            .distinct()
            .take(limit)
            .toList()

    /**
     * Artists by how many files they have, most first. Each file counts for
     * its lead artist only, so a guest verse does not make a second artist.
     * The name shown is the spelling most of their files use.
     */
    fun artists(files: List<DiskTrack>, limit: Int): List<DiskArtist> {
        val groups = LinkedHashMap<String, MutableList<DiskTrack>>()
        files.forEach { file ->
            val key = artistKey(file.artist) ?: return@forEach
            groups.getOrPut(key) { mutableListOf() } += file
        }
        return groups.entries
            .map { (key, tracks) ->
                val name = tracks.groupingBy { leadArtist(it.artist).orEmpty() }
                    .eachCount()
                    .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })!!
                    .key
                DiskArtist(
                    key = key,
                    name = name,
                    trackIds = tracks.sortedByDescending { it.addedAtSeconds }.map { it.id },
                )
            }
            .sortedWith(compareByDescending<DiskArtist> { it.trackIds.size }.thenBy { it.key })
            .take(limit)
    }

    /**
     * The lead artist of a credit: "Calvin Harris feat. Ayah Marar" is Calvin
     * Harris. Only unambiguous separators are cut; "&", "," and "/" are left alone
     * because they are part of names like "Simon & Garfunkel" or "AC/DC".
     */
    fun leadArtist(credit: String): String? {
        var lead = credit.trim()
        val lower = lead.lowercase(Locale.ROOT)
        FEATURING.mapNotNull { marker -> lower.indexOf(marker).takeIf { it > 0 } }
            .minOrNull()
            ?.let { lead = lead.substring(0, it) }
        lead = lead.split(';').first().trim()
        if (lead.isEmpty() || lead.lowercase(Locale.ROOT) in UNKNOWN) return null
        return lead
    }

    /** The grouping key of a credit's lead artist, or null when it names nobody. */
    fun artistKey(credit: String): String? =
        leadArtist(credit)?.lowercase(Locale.ROOT)?.replace(WHITESPACE, " ")

    private val FEATURING = listOf(" feat. ", " feat ", " ft. ", " ft ", " featuring ", " (feat", " (ft", " [feat")
    private val UNKNOWN = setOf("unknown artist", "<unknown>", "unknown", "various artists")
    private val WHITESPACE = Regex("\\s+")
}
