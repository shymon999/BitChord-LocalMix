package moe.rukamori.archivetune.localmix.equivalent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchReviewBookTest {
    private val file = MatchReviewBook.FileRef("content://media/1", "/storage/emulated/0/Music/a.mp3")

    @Test fun rejectionCoversTheSameFileUnderANewUri() {
        val book = MatchReviewBook()
        book.reject(MatchReviewBook.Rejection("yt", file, "Song", "Artist", 5))
        assertTrue(book.isRejected("yt", "content://media/1", null))
        assertTrue(book.isRejected("yt", "content://media/999", "/storage/emulated/0/Music/A.mp3"))
        assertFalse(book.isRejected("yt", "content://media/2", "/storage/emulated/0/Music/b.mp3"))
        assertFalse(book.isRejected("other", "content://media/1", null))
    }

    @Test fun restoreUndoesRejection() {
        val book = MatchReviewBook()
        book.reject(MatchReviewBook.Rejection("yt", file))
        assertTrue(book.restore("yt", file.uri, file.path))
        assertFalse(book.isRejected("yt", file.uri, file.path))
        assertFalse(book.restore("yt", file.uri, file.path))
    }

    @Test fun confirmationIsPerFile() {
        val book = MatchReviewBook()
        book.confirm("yt", file)
        assertTrue(book.isConfirmed("yt", "content://media/1", null))
        assertFalse(book.isConfirmed("yt", "content://media/2", "/x.mp3"))
        book.reject(MatchReviewBook.Rejection("yt", file))
        assertFalse(book.isConfirmed("yt", file.uri, file.path))
    }

    @Test fun jsonRoundTrip() {
        val book = MatchReviewBook()
        book.reject(MatchReviewBook.Rejection("yt", file, "Song", "Artist", 5))
        book.confirm("yt2", MatchReviewBook.FileRef("content://media/3", null))
        val back = MatchReviewBook.fromJson(JSONObject(book.toJson().toString()))
        assertTrue(back.isRejected("yt", file.uri, null))
        assertTrue(back.isConfirmed("yt2", "content://media/3", null))
        assertEquals("Song", back.rejections().single().title)
    }

    @Test fun missingOrBrokenJsonGivesAnEmptyBook() {
        assertTrue(MatchReviewBook.fromJson(null).rejections().isEmpty())
        val broken = JSONObject("""{"rejected":[{"id":""},5,{"id":"x"}],"confirmed":{"a":3}}""")
        assertTrue(MatchReviewBook.fromJson(broken).rejections().isEmpty())
    }
}
