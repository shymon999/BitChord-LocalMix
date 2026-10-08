/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

import java.net.URI

/**
 * Proposes source roots for absolute paths written on another device (e.g. `C:/Users/a/Music/...`
 * or `/home/a/Music/...`). The user confirms each root by choosing the matching folder on the
 * phone; nothing is guessed from basenames.
 */
object LocalRootSuggestions {
    /** Deepest common directory per drive / top-level folder, never deeper than a file's own folder. */
    fun commonRoots(locations: List<String>): List<String> =
        locations.mapNotNull(::absoluteSegments)
            .groupBy { it.first() }
            .map { (_, group) ->
                var common = group.first().dropLast(1)
                for (segments in group.drop(1)) {
                    val folder = segments.dropLast(1)
                    common = common.zip(folder).takeWhile { (a, b) -> a == b }.map { it.first }
                }
                render(common)
            }
            .filter { it.isNotEmpty() }
            .sorted()

    /** Merges confirmed mappings; a newer mapping for the same source root replaces the older one. */
    fun merge(existing: List<LocalRootMapping>, added: List<LocalRootMapping>): List<LocalRootMapping> {
        val byRoot = LinkedHashMap<String, LocalRootMapping>()
        for (mapping in existing + added) byRoot[canonicalRoot(mapping.sourceRoot)] = mapping
        return byRoot.values.toList()
    }

    fun isAbsolute(location: String): Boolean = absoluteSegments(location) != null

    fun canonicalRoot(root: String): String = root.replace('\\', '/').trimEnd('/')

    private fun absoluteSegments(location: String): List<String>? {
        val raw = if (location.startsWith("file:", ignoreCase = true)) {
            runCatching { URI(location).path }.getOrNull() ?: return null
        } else location
        val path = raw.replace('\\', '/')
        val drive = Regex("^([a-zA-Z]:)/").find(path)
        val segments = when {
            drive != null -> listOf(drive.groupValues[1]) + path.substring(3).split('/')
            path.startsWith('/') -> listOf("") + path.drop(1).split('/')
            else -> return null
        }.filterIndexed { index, segment -> index == 0 || segment.isNotEmpty() }
        return segments.takeIf { it.size >= 2 }
    }

    private fun render(segments: List<String>): String = when {
        segments.isEmpty() -> ""
        segments.first().isEmpty() -> if (segments.size == 1) "" else "/" + segments.drop(1).joinToString("/")
        else -> segments.first() + "/" + segments.drop(1).joinToString("/")
    }.trimEnd('/')
}
