package moe.rukamori.archivetune.localmix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistCreditsTest {
    @Test fun pageArtistIsTheLeadOfAFullCredit() {
        assertEquals("Calvin Harris", ArtistCredits.pageArtist("Calvin Harris, Ayah Marar"))
        assertEquals("Red Hot Chili Peppers", ArtistCredits.pageArtist("Red Hot Chili Peppers"))
        assertEquals("Daft Punk", ArtistCredits.pageArtist("Daft Punk - Topic"))
        assertNull(ArtistCredits.pageArtist("  "))
    }

    @Test fun leadAndCoCreditsCount() {
        assertTrue(ArtistCredits.credits("Calvin Harris", "Feel So Close", "Calvin Harris"))
        assertTrue(ArtistCredits.credits("Calvin Harris, Ayah Marar", "Thinking About You", "Ayah Marar"))
        assertTrue(ArtistCredits.credits("David Guetta & Sia", "Titanium", "sia"))
        assertTrue(ArtistCredits.credits("Beyoncé", "Halo", "Beyonce"))
    }

    @Test fun guestInTheTitleCounts() {
        assertTrue(ArtistCredits.credits("Calvin Harris", "Thinking About You (feat. Ayah Marar)", "Ayah Marar"))
        assertTrue(ArtistCredits.credits("Avicii", "Wake Me Up - feat. Aloe Blacc", "Aloe Blacc"))
    }

    @Test fun aNameInsideAnotherIsNotACredit() {
        assertFalse(ArtistCredits.credits("Steve Aoki", "Pursuit of Happiness", "Eve"))
        assertFalse(ArtistCredits.credits("Calvin Harris", "Summer", "Harris"))
        assertFalse(ArtistCredits.credits("Calvin Harris", "Summer", ""))
    }
}
