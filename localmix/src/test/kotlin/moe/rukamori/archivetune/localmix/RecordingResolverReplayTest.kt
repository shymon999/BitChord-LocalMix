/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays the 20 recorded YouTube Music result sets through the whole resolver (query building, matcher, link store). */
class RecordingResolverReplayTest {
    private class RecordedSearch(
        private val results: List<CatalogCandidate>,
    ) : CatalogSearchPort {
        var calls = 0

        override suspend fun searchSongs(query: String): List<CatalogCandidate> {
            calls++
            return results
        }
    }

    private class MemoryStore : LinkStorePort {
        val links = mutableMapOf<String, LocalOnlineLink>()

        override suspend fun find(assetKey: String) = links[assetKey]

        override suspend fun save(link: LocalOnlineLink) {
            links[link.localAssetKey] = link
        }

        override suspend fun compareAndSet(expected: LocalOnlineLink?, link: LocalOnlineLink): Boolean {
            if (links[link.localAssetKey] != expected) return false
            save(link)
            return true
        }
    }

    @Test fun everyRecordedSampleResolvesToACorrectCatalogEntryWithOneQueryAndIsCachedAfterwards() {
        val store = MemoryStore()
        for (s in CalibrationData.load()) {
            val search = RecordedSearch(s.candidates.map { it.candidate })
            val resolver = RecordingResolver(search, store, clock = { 7L })
            val asset = LocalAsset("local:${s.id}", s.local, fileSizeBytes = 1_000, modifiedAtMs = 2_000)
            val first = runSuspend { resolver.resolve(asset) }
            assertTrue("#${s.id} $first", first is ResolveResult.Confirmed)
            val id = (first as ResolveResult.Confirmed).catalogId
            assertTrue("#${s.id} chose $id", id in s.correctIds || (listOf(id) + equivalents(s, id)).any { it in s.correctIds })
            assertEquals("#${s.id} used more than one query", 1, search.calls)
            val again = runSuspend { resolver.resolve(asset) }
            assertEquals(ResolveResult.Source.CACHED_AUTO_LINK, (again as ResolveResult.Confirmed).source)
            assertEquals("#${s.id} cached lookup searched again", 1, search.calls)
            assertEquals(MatchPurpose.RADIO_SEED, store.links.getValue(asset.assetKey).purpose)
        }
        assertEquals(20, store.links.size)
    }

    private fun equivalents(s: CalibrationSample, id: String): List<String> =
        (RecordingMatcher().decide(s.local, s.candidates.map { it.candidate }) as? MatchDecision.Auto)
            ?.takeIf { it.chosen.candidate.catalogId == id }?.chosen?.equivalentIds.orEmpty()
}
