/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.nio.charset.MalformedInputException

class M3uParserTest {
    private fun parse(text: String) = M3uParser.parse(StringReader(text))

    @Test fun bomCrLfAndPolishCharacters() {
        val result = parse("\uFEFF#EXTM3U\r\n#PLAYLIST:Siłka\r\n#EXTINF:-1,Żółć - Tytuł\r\n/storage/emulated/0/Music/Żółć/a #1.opus\r\n")
        assertEquals("Siłka", result.name)
        assertEquals("Żółć - Tytuł", result.entries.single().title)
        assertNull(result.entries.single().durationSeconds)
        assertEquals(4, result.entries.single().lineNumber)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test fun intentionalDuplicatesAndOrderSurvive() {
        val entries = parse("a.mp3\nb.opus\na.mp3").entries
        assertEquals(listOf("a.mp3", "b.opus", "a.mp3"), entries.map { it.location })
        assertEquals(listOf(0, 1, 2), entries.map { it.ordinal })
    }

    @Test fun extinfCommaInTitleAndCommentsBetweenTagAndPath() {
        val entries = parse("#EXTINF:202.514,Artist, Guest - Song\n# comment\n\na.mp3\nb.mp3").entries
        assertEquals("Artist, Guest - Song", entries[0].title)
        assertEquals(202.514, entries[0].durationSeconds!!, 0.0001)
        assertNull(entries[1].title)
        assertNull(entries[1].durationSeconds)
    }

    @Test fun malformedDurationDoesNotLoseThePath() {
        listOf("NaN", "Infinity", "-2", "bad").forEach { value ->
            val result = parse("#EXTINF:$value,Title\na.mp3")
            assertEquals("a.mp3", result.entries.single().location)
            assertNull(result.entries.single().durationSeconds)
            assertEquals(M3uDiagnostic.Reason.INVALID_DURATION, result.diagnostics.single().reason)
        }
    }

    @Test fun orphanExtinfIsReportedWithoutPhantomSongs() {
        val result = parse("#EXTINF:2,Orphan\n#EXTINF:3,Kept\na.mp3\n#EXTINF:4,At end")
        assertEquals(1, result.entries.size)
        assertEquals("Kept", result.entries.single().title)
        assertEquals(listOf(1, 4), result.diagnostics.map { it.lineNumber })
    }

    @Test fun hlsIsMarkedAndRemoteEntriesNeverBecomeLocalPaths() {
        val result = parse("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nhttps://example.com/a.ts\n#EXT-X-ENDLIST")
        assertTrue(result.isHls)
        assertEquals(M3uLocationKind.REMOTE_URI, result.entries.single().kind)
        assertEquals(1, result.diagnostics.count { it.reason == M3uDiagnostic.Reason.HLS_PLAYLIST })
    }

    @Test fun classifyWindowsAndUrisWithoutFetchingAnything() {
        val result = parse("C:\\Music\\a.mp3\nfile:///sdcard/Music/a.mp3\ncontent://media/external/audio/media/1\nftp://example.com/a.mp3")
        assertEquals(listOf(M3uLocationKind.LOCAL_PATH, M3uLocationKind.FILE_URI, M3uLocationKind.CONTENT_URI, M3uLocationKind.UNSUPPORTED_URI), result.entries.map { it.kind })
    }

    @Test(expected = MalformedInputException::class)
    fun invalidUtf8DoesNotSilentlyCorruptFilenames() {
        M3uParser.parse(ByteArrayInputStream(byteArrayOf(0xC3.toByte(), 0x28)))
    }

    @Test fun parserDoesNotCloseCallerStream() {
        var closed = false
        val stream = object : ByteArrayInputStream("a.mp3".toByteArray()) {
            override fun close() { closed = true; super.close() }
        }
        assertEquals(1, M3uParser.parse(stream).entries.size)
        assertFalse(closed)
    }

    @Test fun largePlaylistPreservesMetadataDuplicatesAndOrder() {
        val paths = List(6000) { ordinal ->
            val track = ordinal % 500
            "../Music/Album ${track % 37}/track $track.opus"
        }
        val text = buildString {
            append("\uFEFF#EXTM3U\r\n#PLAYLIST:Large playlist\r\n")
            paths.forEachIndexed { ordinal, path ->
                append("#EXTINF:${180 + ordinal % 60},Artist - Track $ordinal\r\n")
                append(path).append("\r\n")
            }
        }
        val parsed = M3uParser.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        assertEquals("Large playlist", parsed.name)
        assertEquals(paths, parsed.entries.map { it.location })
        assertEquals(paths.indices.toList(), parsed.entries.map { it.ordinal })
        assertEquals(paths.indices.map { "Artist - Track $it" }, parsed.entries.map { it.title })
        assertEquals(paths.indices.map { (180 + it % 60).toDouble() }, parsed.entries.map { it.durationSeconds })
        assertTrue(parsed.diagnostics.isEmpty())
        assertFalse(parsed.isHls)
        assertEquals(500, parsed.entries.map { it.location }.toSet().size)
    }
}
