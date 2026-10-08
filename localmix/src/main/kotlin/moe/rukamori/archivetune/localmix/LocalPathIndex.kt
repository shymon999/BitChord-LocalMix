/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

import java.net.URI
import java.text.Normalizer
import java.util.Locale

data class LocalFileLocator(
    val volume: String,
    val relativePath: String,
    val displayName: String,
) {
    val path: String
        get() = relativePath.trim('/') + (if (relativePath.trim('/').isEmpty()) "" else "/") + displayName
}

data class IndexedLocalFile(val songId: String, val locator: LocalFileLocator)
data class PlaylistParent(val volume: String, val relativePath: String)
data class LocalRootMapping(val sourceRoot: String, val target: PlaylistParent)

sealed interface LocalPathResolution {
    data class Resolved(val file: IndexedLocalFile) : LocalPathResolution
    data class Missing(val locator: LocalFileLocator) : LocalPathResolution
    data class Ambiguous(val candidates: List<IndexedLocalFile>) : LocalPathResolution
    data object ParentRequired : LocalPathResolution
    data object RootMappingRequired : LocalPathResolution
    data object Unsupported : LocalPathResolution
    data object InvalidPath : LocalPathResolution
}

/** One index per MediaStore snapshot, shared by every entry in an import batch. */
class LocalPathIndex(files: List<IndexedLocalFile>) {
    private val exact = files.groupBy { key(it.locator, fold = false) }
    private val folded = files.groupBy { key(it.locator, fold = true) }
    private val bySongId = files.groupBy(IndexedLocalFile::songId)

    fun resolve(
        entry: M3uEntry,
        parent: PlaylistParent? = null,
        mappings: List<LocalRootMapping> = emptyList(),
    ): LocalPathResolution {
        if (entry.kind == M3uLocationKind.CONTENT_URI) {
            val candidates = bySongId[entry.location].orEmpty()
            return when (candidates.size) {
                1 -> LocalPathResolution.Resolved(candidates.single())
                0 -> LocalPathResolution.Unsupported
                else -> LocalPathResolution.Ambiguous(candidates)
            }
        }
        if (entry.kind == M3uLocationKind.REMOTE_URI || entry.kind == M3uLocationKind.UNSUPPORTED_URI) {
            return LocalPathResolution.Unsupported
        }
        val path = if (entry.kind == M3uLocationKind.FILE_URI) {
            val uri = runCatching { URI(entry.location) }.getOrNull() ?: return LocalPathResolution.InvalidPath
            if (uri.rawAuthority?.let { it.isNotEmpty() && !it.equals("localhost", true) } == true ||
                uri.rawQuery != null || uri.rawFragment != null
            ) return LocalPathResolution.InvalidPath
            uri.path ?: return LocalPathResolution.InvalidPath
        } else {
            entry.location
        }.replace('\\', '/')
        if (path.isBlank() || '\u0000' in path) return LocalPathResolution.InvalidPath

        val matchingRoots = mappings.filter { underRoot(path, it.sourceRoot.replace('\\', '/')) }
        val mapped = matchingRoots.maxByOrNull { it.sourceRoot.length }
        if (mapped != null && matchingRoots.count { it.sourceRoot.length == mapped.sourceRoot.length } > 1) {
            return LocalPathResolution.InvalidPath
        }
        val (volume, relative) = when {
            mapped != null -> {
                val suffix = path.drop(mapped.sourceRoot.replace('\\', '/').trimEnd('/').length).trimStart('/')
                val normalizedSuffix = normalize(suffix) ?: return LocalPathResolution.InvalidPath
                mapped.target.volume to (mapped.target.relativePath.trim('/') + "/" + normalizedSuffix)
            }
            path == "/storage/emulated/0" || path == "/sdcard" || path == "/storage/self/primary" -> return LocalPathResolution.InvalidPath
            underRoot(path, "/storage/emulated/0") -> "external_primary" to path.removePrefix("/storage/emulated/0/")
            underRoot(path, "/sdcard") -> "external_primary" to path.removePrefix("/sdcard/")
            underRoot(path, "/storage/self/primary") -> "external_primary" to path.removePrefix("/storage/self/primary/")
            path.startsWith("/storage/") -> {
                val parts = path.removePrefix("/storage/").split('/', limit = 2)
                if (parts.size != 2 || parts[0] == "emulated") return LocalPathResolution.RootMappingRequired
                parts[0].lowercase(Locale.ROOT) to parts[1]
            }
            path.startsWith('/') || Regex("^[a-zA-Z]:/").containsMatchIn(path) -> {
                return LocalPathResolution.RootMappingRequired
            }
            parent == null -> return LocalPathResolution.ParentRequired
            else -> parent.volume to (parent.relativePath.trim('/') + "/" + path)
        }
        if (volume.isBlank()) return LocalPathResolution.InvalidPath
        val normalized = normalize(relative) ?: return LocalPathResolution.InvalidPath
        val locator = LocalFileLocator(volume, normalized.substringBeforeLast('/', ""), normalized.substringAfterLast('/'))
        // Literal equality wins. A case/Unicode fallback never resolves a collision arbitrarily.
        val candidates = exact[key(locator, fold = false)] ?: folded[key(locator, fold = true)].orEmpty()
        return when (candidates.size) {
            0 -> LocalPathResolution.Missing(locator)
            1 -> LocalPathResolution.Resolved(candidates.single())
            else -> LocalPathResolution.Ambiguous(candidates)
        }
    }

    private fun underRoot(path: String, root: String): Boolean {
        val normalizedRoot = root.trimEnd('/')
        return path == normalizedRoot || path.startsWith("$normalizedRoot/")
    }

    private fun normalize(path: String): String? {
        val segments = mutableListOf<String>()
        path.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        return segments.takeIf { it.isNotEmpty() }?.joinToString("/")
    }

    private fun key(locator: LocalFileLocator, fold: Boolean): String {
        val path = normalize(locator.path) ?: locator.path
        val value = locator.volume + '\u0000' + path
        return if (fold) Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT) else value
    }
}
