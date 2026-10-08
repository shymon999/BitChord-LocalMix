/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Runs a suspend block whose fakes never actually suspend. */
internal fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return checkNotNull(result) { "block suspended" }.getOrThrow()
}

class RecordingResolverTest {
    private class FakeSearch(
        private val answers: List<() -> List<CatalogCandidate>>,
    ) : CatalogSearchPort {
        val queries = mutableListOf<String>()

        override suspend fun searchSongs(query: String): List<CatalogCandidate> {
            queries += query
            return answers[minOf(queries.size - 1, answers.size - 1)]()
        }
    }

    private class FakeStore(
        var link: LocalOnlineLink? = null,
    ) : LinkStorePort {
        var saves = 0

        override suspend fun find(assetKey: String) = link?.takeIf { it.localAssetKey == assetKey }

        override suspend fun save(link: LocalOnlineLink) {
            saves++
            this.link = link
        }

        override suspend fun compareAndSet(expected: LocalOnlineLink?, link: LocalOnlineLink): Boolean {
            if (this.link != expected) return false
            save(link)
            return true
        }
    }

    private val asset =
        LocalAsset(
            "key-1",
            LocalRecording("Feel So Close", listOf("Calvin Harris"), "18 Months", 202_514),
            fileSizeBytes = 8_000_000,
            modifiedAtMs = 1_700_000_000_000,
        )
    private val good = CatalogCandidate("good", "Feel So Close (Radio Edit)", listOf("Calvin Harris"), "18 Months", 207)
    private val extended = CatalogCandidate("ext", "Feel So Close (Extended Mix)", listOf("Calvin Harris"), "Feel So Close", 331)

    private fun resolver(
        search: FakeSearch,
        store: FakeStore,
    ) = RecordingResolver(search, store, clock = { 42L })

    private fun link(
        id: String?,
        mode: LinkConfirmation,
        revision: String = asset.revision,
        algorithm: Int = RecordingResolver.ALGORITHM_VERSION,
    ) = LocalOnlineLink(asset.assetKey, revision, RecordingResolver.PROVIDER, id, mode, MatchPurpose.RADIO_SEED, algorithm, null, 1L)

    @Test fun autoMatchIsStoredAsRadioSeedOnly() {
        val store = FakeStore()
        val result = runSuspend { resolver(FakeSearch(listOf { listOf(good, extended) }), store).resolve(asset) }
        result as ResolveResult.Confirmed
        assertEquals("good", result.catalogId)
        assertEquals(ResolveResult.Source.AUTO_MATCH, result.source)
        val saved = checkNotNull(store.link)
        assertEquals(MatchPurpose.RADIO_SEED, saved.purpose)
        assertEquals(LinkConfirmation.AUTO, saved.confirmation)
        assertEquals(asset.revision, saved.recordingRevision)
        assertEquals(42L, saved.checkedAt)
    }

    @Test fun decisiveFirstQueryStopsSearching() {
        val search = FakeSearch(listOf { listOf(good) })
        runSuspend { resolver(search, FakeStore()).resolve(asset) }
        assertEquals(listOf("Calvin Harris Feel So Close"), search.queries)
    }

    @Test fun secondQueryRunsOnlyWhenTheFirstDoesNotDecideAndNeverMoreThanTwo() {
        val search = FakeSearch(listOf({ emptyList() }, { listOf(good) }))
        val result = runSuspend { resolver(search, FakeStore()).resolve(asset) }
        assertTrue(result is ResolveResult.Confirmed)
        assertEquals(2, search.queries.size)
        val none = FakeSearch(listOf { emptyList() })
        runSuspend { resolver(none, FakeStore()).resolve(asset) }
        assertEquals(2, none.queries.size)
    }

    @Test fun manualLinkOfTheSameRevisionIsUsedWithoutSearching() {
        val search = FakeSearch(listOf { fail("must not search"); emptyList() })
        val result = runSuspend { resolver(search, FakeStore(link("picked", LinkConfirmation.MANUAL))).resolve(asset) }
        result as ResolveResult.Confirmed
        assertEquals("picked", result.catalogId)
        assertEquals(ResolveResult.Source.MANUAL_LINK, result.source)
        assertTrue(search.queries.isEmpty())
    }

    @Test fun cachedAutoLinkIsReusedUntilTheAlgorithmVersionChanges() {
        val fresh = FakeSearch(listOf { fail("must not search"); emptyList() })
        val cached = runSuspend { resolver(fresh, FakeStore(link("old", LinkConfirmation.AUTO))).resolve(asset) }
        assertEquals(ResolveResult.Source.CACHED_AUTO_LINK, (cached as ResolveResult.Confirmed).source)
        val outdated = FakeSearch(listOf { listOf(good) })
        val again = runSuspend { resolver(outdated, FakeStore(link("old", LinkConfirmation.AUTO, algorithm = 0))).resolve(asset) }
        assertEquals("good", (again as ResolveResult.Confirmed).catalogId)
        assertEquals(1, outdated.queries.size)
    }

    @Test fun noCorrectMatchAnswerIsRememberedPerRevision() {
        val search = FakeSearch(listOf { listOf(good) })
        val store = FakeStore(link(null, LinkConfirmation.MANUAL))
        val declined = runSuspend { resolver(search, store).resolve(asset) }
        assertEquals(ResolveResult.NotFound(declinedByUser = true), declined)
        assertTrue(search.queries.isEmpty())
        val changed = asset.copy(fileSizeBytes = 9_999_999)
        store.link = link(null, LinkConfirmation.MANUAL)
        val searched = runSuspend { resolver(search, store).resolve(changed) }
        assertTrue(searched is ResolveResult.Confirmed)
    }

    @Test fun offlineNeverOverwritesAValidManualLink() {
        val manual = link("picked", LinkConfirmation.MANUAL, revision = "older-revision")
        val store = FakeStore(manual)
        val search = FakeSearch(listOf { throw CatalogSearchException(CatalogSearchException.Kind.OFFLINE) })
        assertEquals(ResolveResult.Offline, runSuspend { resolver(search, store).resolve(asset) })
        assertEquals(manual, store.link)
        assertEquals(0, store.saves)
    }

    @Test fun retryableFailureIsReportedAndStoreIsUntouched() {
        val store = FakeStore()
        val search = FakeSearch(listOf { throw CatalogSearchException(CatalogSearchException.Kind.RETRYABLE, "HTTP 503") })
        val result = runSuspend { resolver(search, store).resolve(asset) }
        assertEquals(ResolveResult.RetryableFailure("HTTP 503"), result)
        assertEquals(0, store.saves)
    }

    @Test fun cancellationIsNotNotFound() {
        val search = FakeSearch(listOf { throw CancellationException("closed") })
        try {
            runSuspend { resolver(search, FakeStore()).resolve(asset) }
            fail("expected cancellation")
        } catch (e: CancellationException) {
            assertEquals("closed", e.message)
        }
    }

    @Test fun unexpectedExceptionsPropagateInsteadOfBecomingNotFound() {
        val search = FakeSearch(listOf { throw IllegalStateException("parser bug") })
        try {
            runSuspend { resolver(search, FakeStore()).resolve(asset) }
            fail("expected exception")
        } catch (e: IllegalStateException) {
            assertEquals("parser bug", e.message)
        }
    }

    @Test fun staleManualLinkIsRevalidatedWhenTheCatalogStillHasIt() {
        val store = FakeStore(link("good", LinkConfirmation.MANUAL, revision = "older-revision"))
        val result = runSuspend { resolver(FakeSearch(listOf { listOf(good) }), store).resolve(asset) }
        result as ResolveResult.Confirmed
        assertEquals(ResolveResult.Source.REVALIDATED_MANUAL_LINK, result.source)
        assertEquals(LinkConfirmation.MANUAL, store.link!!.confirmation)
        assertEquals(asset.revision, store.link!!.recordingRevision)
    }

    @Test fun staleManualLinkMissingFromTheCatalogIsKeptAndTheUserIsAsked() {
        val manual = link("gone", LinkConfirmation.MANUAL, revision = "older-revision")
        val store = FakeStore(manual)
        val one = CatalogCandidate("a", "Feel So Close", listOf("Calvin Harris"), "x", 206)
        val two = CatalogCandidate("b", "Feel So Close", listOf("Calvin Harris"), "y", 215)
        val result = runSuspend { resolver(FakeSearch(listOf { listOf(one, two) }), store).resolve(asset) }
        assertTrue(result.toString(), result is ResolveResult.NeedsChoice)
        assertEquals(manual, store.link)
    }

    @Test fun manualChoiceIsStoredAndStaleChoiceIsRefused() {
        val store = FakeStore()
        val r = resolver(FakeSearch(listOf { emptyList() }), store)
        assertFalse(runSuspend { r.recordManualChoice(asset, good, isStillCurrent = { false }) })
        assertNull(store.link)
        assertTrue(runSuspend { r.recordManualChoice(asset, good) })
        assertEquals(LinkConfirmation.MANUAL, store.link!!.confirmation)
        assertEquals("good", store.link!!.catalogId)
        assertEquals(MatchPurpose.RADIO_SEED, store.link!!.purpose)
        assertEquals("Feel So Close (Radio Edit)", store.link!!.snapshot!!.title)
    }

    @Test fun noCorrectMatchChoiceStoresANullCatalogId() {
        val store = FakeStore()
        runSuspend { resolver(FakeSearch(listOf { emptyList() }), store).recordManualChoice(asset, null) }
        assertTrue(store.link!!.isNoCorrectMatch)
    }

    @Test fun revisionReactsToTagsLengthAndFileChangesButNotToTheLocator() {
        val base = asset.revision
        assertNotEquals(base, asset.copy(recording = asset.recording.copy(title = "Feel So Close (Live)")).revision)
        assertNotEquals(base, asset.copy(recording = asset.recording.copy(durationMs = 230_000)).revision)
        assertNotEquals(base, asset.copy(fileSizeBytes = 1).revision)
        assertNotEquals(base, asset.copy(modifiedAtMs = 5).revision)
        assertEquals(base, asset.copy(assetKey = "other-uri").revision)
        assertEquals(base, asset.copy(recording = asset.recording.copy(title = "feel so close!")).revision)
    }

    @Test fun queriesKeepNamesWithSeparatorsWholeAndCutOnlyLists() {
        val r = RecordingResolver(FakeSearch(emptyList()), FakeStore())
        assertEquals("AC/DC Highway to Hell", r.queries(LocalRecording("Highway to Hell", listOf("AC/DC"), null, 1))[0])
        assertEquals("Hall & Oates Rich Girl", r.queries(LocalRecording("Rich Girl", listOf("Hall & Oates"), null, 1))[0])
        assertEquals("Calvin Harris Miracle", r.queries(LocalRecording("Miracle", listOf("Calvin Harris, Ellie Goulding"), null, 1))[0])
        assertEquals("Calvin Harris Miracle", r.queries(LocalRecording("Miracle", listOf("Calvin Harris; Ellie Goulding"), null, 1))[0])
    }

    @Test fun queryBuildingUsesMainArtistFirst() {
        val r = RecordingResolver(FakeSearch(emptyList()), FakeStore())
        val q = r.queries(LocalRecording("Missing (Todd Terry Remix / Radio Edit)", listOf("Everything but the Girl"), null, 1))
        assertEquals("Everything but the Girl Missing (Todd Terry Remix / Radio Edit)", q[0])
        assertEquals(2, q.size)
    }

    @Test fun staleManualIdInSearchDoesNotValidateADifferentVersion() {
        val manual = link("good", LinkConfirmation.MANUAL)
        val store = FakeStore(manual)
        val changed = asset.copy(recording = asset.recording.copy(title = "Feel So Close (Live)"))
        val result = runSuspend { resolver(FakeSearch(listOf { listOf(good) }), store).resolve(changed) } as ResolveResult.NeedsChoice
        assertEquals(ChoiceReason.STALE_MANUAL_LINK, result.reason)
        assertEquals(manual, store.link)
        assertEquals(0, store.saves)
    }

    @Test fun staleManualIdInSearchDoesNotValidateChangedSongIdentity() {
        val manual = link("good", LinkConfirmation.MANUAL)
        val store = FakeStore(manual)
        val changed = asset.copy(recording = asset.recording.copy(title = "Sweet Nothing"))
        val result = runSuspend { resolver(FakeSearch(listOf { listOf(good) }), store).resolve(changed) }
        assertTrue(result is ResolveResult.NeedsChoice)
        assertEquals(manual, store.link)
    }

    @Test fun manualRevalidationRefreshesCatalogSnapshot() {
        val store = FakeStore(link("good", LinkConfirmation.MANUAL, revision = "old"))
        val result = runSuspend { resolver(FakeSearch(listOf { listOf(good) }), store).resolve(asset) } as ResolveResult.Confirmed
        assertEquals(ResolveResult.Source.REVALIDATED_MANUAL_LINK, result.source)
        assertEquals(good.title, store.link!!.snapshot!!.title)
        assertEquals(RecordingResolver.ALGORITHM_VERSION, store.link!!.algorithmVersion)
    }

    @Test fun symbolOnlyTagChangesInvalidateRecordingRevision() {
        val a = asset.copy(recording = asset.recording.copy(title = "!!!", artists = listOf("!!!")))
        assertNotEquals(a.revision, a.copy(recording = a.recording.copy(title = "***")).revision)
        assertNotEquals(a.revision, a.copy(recording = a.recording.copy(artists = listOf("+/-"))).revision)
    }

    @Test fun manualChoiceMadeDuringSearchWinsOverLateAuto() {
        val store = FakeStore()
        val newer = link("user-picked", LinkConfirmation.MANUAL)
        val search = FakeSearch(listOf { store.link = newer; listOf(good) })
        val result = runSuspend { resolver(search, store).resolve(asset) } as ResolveResult.Confirmed
        assertEquals("user-picked", result.catalogId)
        assertEquals(ResolveResult.Source.MANUAL_LINK, result.source)
        assertEquals(newer, store.link)
        assertEquals(0, store.saves)
    }

    @Test fun declinedChoiceMadeDuringSearchWinsOverLateAuto() {
        val store = FakeStore()
        val newer = link(null, LinkConfirmation.MANUAL)
        val search = FakeSearch(listOf { store.link = newer; listOf(good) })
        val result = runSuspend { resolver(search, store).resolve(asset) }
        assertEquals(ResolveResult.NotFound(declinedByUser = true), result)
        assertEquals(newer, store.link)
        assertEquals(0, store.saves)
    }

    @Test fun newManualChoiceWinsOverLateManualRevalidation() {
        val store = FakeStore(link("good", LinkConfirmation.MANUAL, revision = "old"))
        val newer = link("user-picked", LinkConfirmation.MANUAL)
        val search = FakeSearch(listOf { store.link = newer; listOf(good) })
        val result = runSuspend { resolver(search, store).resolve(asset) } as ResolveResult.Confirmed
        assertEquals("user-picked", result.catalogId)
        assertEquals(newer, store.link)
        assertEquals(0, store.saves)
    }

    @Test fun manualChoiceDoesNotOverwriteAChoiceChangedDuringItsGuard() {
        val store = FakeStore()
        val newer = link("newer-choice", LinkConfirmation.MANUAL)
        val r = resolver(FakeSearch(listOf { emptyList() }), store)
        val saved = runSuspend { r.recordManualChoice(asset, good, isStillCurrent = { store.link = newer; true }) }
        assertFalse(saved)
        assertEquals(newer, store.link)
        assertEquals(0, store.saves)
    }

    @Test fun automaticEquivalentCatalogIdsSurviveTheCachedLookup() {
        val store = FakeStore()
        val alias = good.copy(catalogId = "equivalent-release", album = "Compilation")
        val search = FakeSearch(listOf { listOf(good, alias) })
        val r = resolver(search, store)
        val first = runSuspend { r.resolve(asset) } as ResolveResult.Confirmed
        assertEquals(setOf("good", "equivalent-release"), first.seedCatalogIds)
        assertEquals(listOf("equivalent-release"), store.link!!.snapshot!!.equivalentCatalogIds)
        val cached = runSuspend { r.resolve(asset) } as ResolveResult.Confirmed
        assertEquals(ResolveResult.Source.CACHED_AUTO_LINK, cached.source)
        assertEquals(first.seedCatalogIds, cached.seedCatalogIds)
        assertEquals(1, search.queries.size)
    }

    @Test fun manualPickDoesNotEstablishUnverifiedSeedAliases() {
        val snapshot = CatalogSnapshot(good.title, good.artists, good.album, good.durationSec, listOf("unverified"))
        val store = FakeStore(link("picked", LinkConfirmation.MANUAL).copy(snapshot = snapshot))
        val r = resolver(FakeSearch(listOf { emptyList() }), store)
        val storedManual = runSuspend { r.resolve(asset) } as ResolveResult.Confirmed
        assertEquals(setOf("picked"), storedManual.seedCatalogIds)
        runSuspend { r.recordManualChoice(asset, good) }
        assertTrue(store.link!!.snapshot!!.equivalentCatalogIds.isEmpty())
        val picked = runSuspend { r.resolve(asset) } as ResolveResult.Confirmed
        assertEquals(setOf("good"), picked.seedCatalogIds)
    }
}
