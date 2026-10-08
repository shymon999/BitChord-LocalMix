package moe.rukamori.archivetune.localmix

import java.util.Locale

/**
 * Where an imported playlist file lives, as far as the app can tell.
 *
 * @param uri the document URI it was read through. The same file read through
 *   a folder grant and through a file pick has two different URIs.
 * @param document "<provider authority>|<document id>": the same for both of those URIs.
 * @param path "<volume>:<folder>/<file name>" when the provider reveals it.
 */
data class PlaylistSource(
    val uri: String,
    val document: String? = null,
    val path: String? = null,
) {
    internal val authority: String? get() = document?.substringBefore('|')
    internal val normalizedPath: String? get() = path?.replace('\\', '/')?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
}

/**
 * Finds the imported playlist that a (re)imported file updates.
 *
 * The file's location decides: the same URI, the same provider document, or
 * the same path. The playlist name is only a fallback, and only when nothing
 * says the two are different files: a same-named playlist whose file is known
 * to be somewhere else, and still there, is another playlist.
 */
object PlaylistSourceMatcher {

    data class Known(
        val id: String,
        val name: String,
        val source: PlaylistSource,
    )

    /**
     * @param sourceGone asked only for a same-named candidate whose location
     *   differs: true when its file can no longer be read (moved or deleted),
     *   which makes the name match acceptable again.
     */
    fun find(
        incoming: PlaylistSource,
        name: String,
        known: List<Known>,
        sourceGone: (Known) -> Boolean = { false },
    ): Known? {
        known.firstOrNull { it.source.uri == incoming.uri }?.let { return it }
        incoming.document?.let { doc -> known.firstOrNull { it.source.document == doc }?.let { return it } }
        incoming.normalizedPath?.let { p -> known.firstOrNull { it.source.normalizedPath == p }?.let { return it } }

        val sameName = known.filter { it.name.trim().equals(name.trim(), ignoreCase = true) }
        val only = sameName.singleOrNull() ?: return null
        return only.takeIf { !knownElsewhere(incoming, it.source) || sourceGone(it) }
    }

    /** True when the two locations are known well enough to say they are different files. */
    private fun knownElsewhere(a: PlaylistSource, b: PlaylistSource): Boolean {
        if (a.normalizedPath != null && b.normalizedPath != null) return true
        // Two ids from one provider name two documents; ids from different providers say nothing.
        return a.document != null && b.document != null && a.authority == b.authority
    }
}
