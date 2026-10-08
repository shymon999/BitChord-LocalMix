/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

/** Preview identity includes EXTINF; edits to metadata are shown as removal + addition. */
data class LocalManifestItem(val path: String, val title: String? = null, val durationSeconds: Double? = null)
data class LocalManifestOccurrence(val position: Int, val item: LocalManifestItem)
data class LocalManifestMove(val previousPosition: Int, val nextPosition: Int, val item: LocalManifestItem)
data class LocalReimportDiff(
    val added: List<LocalManifestOccurrence>,
    val removed: List<LocalManifestOccurrence>,
    val moved: List<LocalManifestMove>,
) {
    companion object {
        /** Pair the nth occurrence of an identical row, solely for preview; never reuse entry IDs. */
        fun between(previous: List<LocalManifestItem>, next: List<LocalManifestItem>): LocalReimportDiff {
            val available = previous.withIndex().groupBy { it.value }
                .mapValues { (_, rows) -> ArrayDeque(rows.map { it.index }) }
            val pairs = mutableListOf<Pair<Int, Int>>()
            val added = mutableListOf<LocalManifestOccurrence>()
            next.forEachIndexed { index, item ->
                val before = available[item]?.removeFirstOrNull()
                if (before == null) added += LocalManifestOccurrence(index, item)
                else pairs += before to index
            }
            val matched = pairs.map { it.first }.toSet()
            val removed = previous.mapIndexedNotNull { index, item ->
                if (index in matched) null else LocalManifestOccurrence(index, item)
            }
            // Compare relative ranks among retained rows: insertion/deletion alone is not a reorder.
            val previousRanks = pairs.sortedBy { it.first }.mapIndexed { rank, pair -> pair.first to rank }.toMap()
            val moved = pairs.mapIndexedNotNull { rank, (before, after) ->
                if (previousRanks.getValue(before) == rank) null else LocalManifestMove(before, after, next[after])
            }
            return LocalReimportDiff(added, removed, moved)
        }
    }
}

/** Recover the exact saved locator for relative entries after their source context disappeared. */
object LocalLocatorFallback {
    fun entryFor(entry: M3uEntry, parent: PlaylistParent?, locator: LocalFileLocator?): M3uEntry {
        if (parent != null || locator == null || entry.kind != M3uLocationKind.LOCAL_PATH ||
            entry.location.startsWith('/') || Regex("""^[a-zA-Z]:[\\/]""").containsMatchIn(entry.location)) return entry
        val root = if (locator.volume == "external_primary") "/storage/emulated/0" else "/storage/${locator.volume}"
        return entry.copy(location = "$root/${locator.path}")
    }
}
