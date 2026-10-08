/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix.radio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class QueueSnapshotStoreTest {
    private val dir: File = Files.createTempDirectory("lm-queue").toFile()

    @After fun cleanup() {
        dir.deleteRecursively()
    }

    private fun entry(n: Int, catalog: String? = null) = PersistedEntry("occ-$n", "local-$n", "pl", 3, "e-$n", catalog)

    private fun queue(
        revision: Long,
        generation: Long = revision,
        size: Int = 3,
        radio: PersistedRadio? = null,
        ret: PersistedReturn? = null,
    ) = PersistedQueue(
        revision, generation, (1..size).map { entry(it) }, currentIndex = 1, positionMs = 12_345,
        modes = PlayerModes(false, RepeatMode.ALL), radio = radio, returnContext = ret,
    )

    private class CrashingOps(
        private val crashAt: Int,
    ) : QueueFileOps {
        var step = 0

        private fun tick() {
            if (++step == crashAt) throw java.io.IOException("simulated crash at step $crashAt")
        }

        override fun writeDurably(file: File, bytes: ByteArray) {
            tick()
            RealQueueFileOps.writeDurably(file, bytes)
        }

        override fun moveAtomically(from: File, to: File) {
            tick()
            RealQueueFileOps.moveAtomically(from, to)
        }

        override fun delete(file: File) = RealQueueFileOps.delete(file)

        override fun syncDirectory(directory: File) {
            tick()
            RealQueueFileOps.syncDirectory(directory)
        }
    }

    @Test fun roundTripKeepsEveryFieldIncludingRadioAndReturnContext() {
        val radio = PersistedRadio("s1", listOf("seed", "alias"), listOf("a", "b"), "tok", ended = false)
        val ret = PersistedReturn("pl", 3, "occ-1", listOf(entry(7), entry(8)), PlayerModes(true, RepeatMode.OFF))
        val q = queue(1, radio = radio, ret = ret)
        QueueSnapshotStore(dir).save(q)
        assertEquals(q, QueueSnapshotStore(dir).load())
    }

    @Test fun typedMetadataAndVerbatimLocatorSurviveIncludingReturnTailRepeatedSong() {
        val metadata = PersistedMetadata("Zażółć 🎵", listOf(PersistedArtist("a", "AC/DC", "content://covers/2")), 203,
            "content://covers/1", "album", "Album", "set", "spotify", true, true, "2026-10-07T20:00", "2026-10-06T10:00",
            true, false, "ISRC", "suggestion")
        val first = PersistedEntry("occ-a", "content://media/external/audio/media/1", "pl", 2, "entry-a", "seed",
            "content://media/external/audio/media/99", "audio/flac", metadata)
        val second = first.copy(occurrenceId = "occ-b", entryId = "entry-b")
        val q = queue(1).copy(entries = listOf(first, second), returnContext = PersistedReturn("pl", 2, "occ-a", listOf(second), PlayerModes(true, RepeatMode.ONE)))
        QueueSnapshotStore(dir).save(q)
        assertEquals(q, QueueSnapshotStore(dir).load())
    }

    @Test fun originalVersionOneWithoutMetadataStillDecodes() {
        val text = PersistedQueueCodec.encode(queue(1))
        val json = org.json.JSONObject(text)
        val entries = json.getJSONArray("entries")
        for (index in 0 until entries.length()) {
            entries.getJSONObject(index).remove("sourceUri")
            entries.getJSONObject(index).remove("mimeType")
            entries.getJSONObject(index).remove("metadata")
        }
        assertEquals(queue(1), PersistedQueueCodec.decode(json.toString()))
    }

    @Test fun nullablesAndEmptyQueueSurvive() {
        val q = PersistedQueue(1, 0, emptyList(), 0, 0, PlayerModes(false, RepeatMode.OFF))
        QueueSnapshotStore(dir).save(q)
        assertEquals(q, QueueSnapshotStore(dir).load())
        val withNulls = q.copy(revision = 2, entries = listOf(PersistedEntry("o", "p")), radio = PersistedRadio("s", emptyList(), emptyList(), null, true))
        QueueSnapshotStore(dir).save(withNulls)
        assertEquals(withNulls, QueueSnapshotStore(dir).load())
    }

    @Test fun nothingSavedMeansNothingLoaded() {
        assertNull(QueueSnapshotStore(dir).load())
    }

    @Test fun newestRevisionWinsAndOnlyThePreviousSnapshotIsKept() {
        val store = QueueSnapshotStore(dir)
        for (r in 1L..4L) store.save(queue(r))
        assertEquals(4L, store.load()!!.revision)
        val snapshots = dir.listFiles()!!.map { it.name }.filter { it.startsWith("queue-") }.sorted()
        assertEquals(listOf("queue-3.json", "queue-4.json"), snapshots)
    }

    @Test fun revisionMustGrow() {
        val store = QueueSnapshotStore(dir)
        store.save(queue(5))
        try {
            store.save(queue(5))
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(5L, store.load()!!.revision)
    }

    @Test fun aCrashAtAnyStepOfASaveLeavesTheLastCompleteQueue() {
        // Snapshot and pointer rename each require a directory fsync before obsolete snapshots can be removed.
        for (crashAt in 1..6) {
            val d = Files.createTempDirectory("lm-crash").toFile()
            try {
                QueueSnapshotStore(d).save(queue(1, size = 2))
                val crashing = QueueSnapshotStore(d, CrashingOps(crashAt))
                try {
                    crashing.save(queue(2, size = 5))
                    fail("expected a simulated crash at step $crashAt")
                } catch (_: java.io.IOException) {
                }
                val loaded = QueueSnapshotStore(d).load()
                assertNotNull("crash at step $crashAt lost the queue", loaded)
                // Step 6 already renamed the pointer: a process crash may restore the complete new queue.
                assertEquals("crash at step $crashAt", if (crashAt < 6) 1L else 2L, loaded!!.revision)
                assertEquals(if (crashAt < 6) 2 else 5, loaded.entries.size)
                // The store recovers: a later save works and wins.
                QueueSnapshotStore(d).save(queue(3, size = 4))
                assertEquals(3L, QueueSnapshotStore(d).load()!!.revision)
                assertFalse(d.listFiles()!!.any { it.name.endsWith(".tmp") && it.name.startsWith("queue-") })
            } finally {
                d.deleteRecursively()
            }
        }
    }

    @Test fun aTornPointerIsIgnoredAndTheOldPointerStaysValid() {
        QueueSnapshotStore(dir).save(queue(1))
        File(dir, "queue.pointer.tmp").writeText("1;2;2;garb")
        assertEquals(1L, QueueSnapshotStore(dir).load()!!.revision)
    }

    @Test fun aCorruptNewestSnapshotFallsBackToThePreviousOne() {
        val store = QueueSnapshotStore(dir)
        store.save(queue(1, size = 2))
        store.save(queue(2, size = 6))
        File(dir, "queue-2.json").writeText("{\"format\":1,\"truncated\"")
        val loaded = store.load()!!
        assertEquals(1L, loaded.revision)
        assertEquals(2, loaded.entries.size)
    }

    @Test fun savingAfterFallbackRetainsTheLastVerifiedSnapshot() {
        val store = QueueSnapshotStore(dir)
        store.save(queue(1, size = 2))
        store.save(queue(2, size = 4))
        File(dir, "queue-2.json").writeText("corrupt")
        assertEquals(1L, store.load()!!.revision)
        store.save(queue(3, size = 6))
        assertTrue(File(dir, "queue-1.json").isFile)
        assertFalse(File(dir, "queue-2.json").exists())
        File(dir, "queue-3.json").writeText("corrupt again")
        assertEquals(1L, store.load()!!.revision)
        assertEquals(2, store.load()!!.entries.size)
    }

    @Test fun naturalNextRevisionAfterFallbackCanReplaceTheCorruptSnapshot() {
        val store = QueueSnapshotStore(dir)
        store.save(queue(1, size = 2))
        store.save(queue(2, size = 4))
        File(dir, "queue-2.json").writeText("corrupt")
        val restored = store.load()!!
        val next = queue(restored.revision + 1, size = 6)
        store.save(next)
        assertEquals(next, store.load())
        File(dir, "queue-2.json").writeText("corrupt again")
        assertEquals(restored, store.load())
    }

    @Test fun interruptedNaturalNextRevisionAfterFallbackKeepsAVerifiedQueue() {
        for (crashAt in 1..6) {
            val d = Files.createTempDirectory("lm-fallback-crash").toFile()
            try {
                val store = QueueSnapshotStore(d)
                val original = queue(1, size = 2)
                store.save(original)
                store.save(queue(2, size = 4))
                File(d, "queue-2.json").writeText("corrupt")
                val next = queue(store.load()!!.revision + 1, size = 6)
                try {
                    QueueSnapshotStore(d, CrashingOps(crashAt)).save(next)
                    fail("expected interrupted save at $crashAt")
                } catch (_: java.io.IOException) {
                }
                val loaded = store.load()
                assertTrue("crash at $crashAt lost verified queues", loaded == original || loaded == next)
                if (loaded == original) {
                    store.save(next)
                    assertEquals(next, store.load())
                }
            } finally {
                d.deleteRecursively()
            }
        }
    }

    @Test fun aSnapshotThatDoesNotMatchThePointerChecksumIsRejected() {
        val store = QueueSnapshotStore(dir)
        store.save(queue(1))
        store.save(queue(2))
        // A well formed but different queue under the name the pointer vouches for (stale file swapped in).
        File(dir, "queue-2.json").writeText(PersistedQueueCodec.encode(queue(2, size = 9)))
        assertEquals(1L, store.load()!!.revision)
    }

    @Test fun unreadablePointerOrUnknownFormatYieldsNullNotAGuess() {
        QueueSnapshotStore(dir).save(queue(1))
        File(dir, "queue.pointer").writeText("garbage")
        assertNull(QueueSnapshotStore(dir).load())
        assertNull(PersistedQueueCodec.decode("""{"format":2,"revision":1}"""))
        assertNull(PersistedQueueCodec.decode("not json"))
        assertNull(PersistedQueueCodec.decode(PersistedQueueCodec.encode(queue(1)).replace("\"currentIndex\":1", "\"currentIndex\":99")))
    }

    @Test fun generationMismatchBetweenPointerAndSnapshotIsRejected() {
        val store = QueueSnapshotStore(dir)
        store.save(queue(1, generation = 10))
        store.save(queue(2, generation = 11))
        val pointer = File(dir, "queue.pointer")
        pointer.writeText(pointer.readText().replaceFirst(";11;", ";99;"))
        assertEquals(1L, store.load()!!.revision)
    }

    @Test fun clearRemovesEverything() {
        val store = QueueSnapshotStore(dir)
        store.save(queue(1))
        store.save(queue(2))
        store.clear()
        assertNull(store.load())
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("queue") })
    }
}
