/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

import java.io.ByteArrayInputStream
import java.security.MessageDigest

data class PreviewEntry(val source: M3uEntry, val resolution: LocalPathResolution)

data class LocalImportPreview(
    val name: String,
    val contentSha256: String,
    val entries: List<PreviewEntry>,
    val diagnostics: List<M3uDiagnostic>,
) {
    val totalCount: Int get() = entries.size
    val resolvedCount: Int get() = entries.count { it.resolution is LocalPathResolution.Resolved }
    val unavailableCount: Int get() = totalCount - resolvedCount
    val requiresPartialImportDecision: Boolean get() = unavailableCount != 0

    companion object {
        const val MAX_DOCUMENT_BYTES = 16 * 1024 * 1024

        /** Does not write to a database, deduplicate entries, or contact an online service. */
        fun prepare(
            document: ByteArray,
            fallbackName: String,
            index: LocalPathIndex,
            parent: PlaylistParent? = null,
            mappings: List<LocalRootMapping> = emptyList(),
        ): LocalImportPreview {
            require(document.size <= MAX_DOCUMENT_BYTES) { "Playlist document exceeds 16 MiB" }
            val parsed = M3uParser.parse(ByteArrayInputStream(document))
            require(!parsed.isHls) { "HLS is a streaming manifest, not a local playlist" }
            require(parsed.entries.isNotEmpty()) { "Playlist has no entries" }
            val name = parsed.name ?: fallbackName.trim().takeIf(String::isNotEmpty)
            requireNotNull(name) { "Playlist name is required" }
            val hash = MessageDigest.getInstance("SHA-256").digest(document)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return LocalImportPreview(
                name,
                hash,
                parsed.entries.map { PreviewEntry(it, index.resolve(it, parent, mappings)) },
                parsed.diagnostics,
            )
        }
    }
}
