package moe.rukamori.archivetune.localmix

/**
 * Who a track credits, read off its plain-text byline and title.
 *
 * Used where there is no catalogue to ask: an artist page opened offline is
 * filled from the files and downloads on the device, and the only thing those
 * say about their artists is text such as "Calvin Harris, Ayah Marar" or a
 * title ending in "(feat. Ayah Marar)".
 */
object ArtistCredits {
    /**
     * The one artist an artist page is about, from the name it was opened with.
     *
     * Callers that open an artist from a track only have that track's full
     * credit to hand ("A, B & C"); its first name is the lead, whose page that
     * is. "- Topic" channel suffixes are dropped.
     */
    fun pageArtist(name: String): String? = TitleParser.splitArtists(listOf(name)).firstOrNull()

    /**
     * Whether a track with byline [credit] and title [title] is by [artist], as
     * the lead, one of several credited artists, or a guest named in the title.
     *
     * Names are compared folded (case, diacritics, punctuation), never by
     * substring: "Eve" must not claim "Steve Aoki".
     */
    fun credits(credit: String, title: String, artist: String): Boolean {
        val wanted = key(artist) ?: return false
        val names = TitleParser.splitArtists(listOf(credit)) + TitleParser.parse(title).featured
        return names.any { key(it) == wanted }
    }

    private fun key(name: String): String? =
        TextNormalizer.identityKey(name.trim()).takeIf { it.isNotBlank() }
}
