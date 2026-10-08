package moe.rukamori.archivetune.localmix.home

/**
 * A block of the home screen the user can show, hide and move.
 *
 * [key] is what is stored in the settings, so it never changes once shipped;
 * a section added later just gets a new key.
 */
enum class HomeSection(val key: String, val visibleByDefault: Boolean) {
    /** YouTube Music's recently played shelf ("Recents"). */
    RECENTS("recents", true),

    /** Imported M3U/M3U8 playlists. */
    MY_PLAYLISTS("my_playlists", true),

    /**
     * Imported playlists the user marked as favourites. Shown by default: it
     * stays empty, and so off the page, until a playlist is marked.
     */
    FAVORITE_PLAYLISTS("favorite_playlists", true),

    /** Files on the device played most recently, from the listening record. */
    LOCAL_RECENT("local_recent", false),

    /** Files on the device played the most times. */
    LOCAL_MOST_PLAYED("local_most_played", false),

    /** Files MediaStore saw most recently. */
    LOCAL_RECENTLY_ADDED("local_added", false),

    /** Artists with the most files on the device. */
    LOCAL_ARTISTS("local_artists", false),

    /** Every other shelf of the YouTube Music home feed, in YouTube's own order. */
    YOUTUBE_FEED("youtube_feed", true),
    ;

    /** Built on the device from local files and the listening record, not fetched. */
    val isLocal: Boolean get() = this in LOCAL

    companion object {
        val LOCAL: Set<HomeSection> = setOf(LOCAL_RECENT, LOCAL_MOST_PLAYED, LOCAL_RECENTLY_ADDED, LOCAL_ARTISTS)

        fun fromKey(key: String): HomeSection? = entries.firstOrNull { it.key == key }
    }
}

/** How a section's cards are laid out. [key] is stored, so it never changes. */
enum class SectionStyle(val key: String) {
    /** One horizontal row, as YouTube Music's own shelves. */
    CAROUSEL("c"),

    /** A few rows of cards, all on screen at once. */
    GRID("g"),
    ;

    companion object {
        fun fromKey(key: String): SectionStyle? = entries.firstOrNull { it.key == key }
    }
}

data class HomeSectionSetting(
    val section: HomeSection,
    val visible: Boolean,
    val style: SectionStyle = SectionStyle.CAROUSEL,
)

/**
 * Which home sections are shown, and in what order.
 *
 * Always holds every [HomeSection] exactly once: [parse] drops unknown and
 * repeated keys and slots sections the stored value does not know yet into
 * their default place. The order covers every section, YouTube Music's feed
 * moving as one block ([HomeSection.YOUTUBE_FEED]).
 */
data class HomeLayout(val sections: List<HomeSectionSetting>) {

    fun isVisible(section: HomeSection): Boolean =
        sections.firstOrNull { it.section == section }?.visible ?: section.visibleByDefault

    /** The visible sections, top to bottom. */
    val visibleSections: List<HomeSection> get() = sections.filter { it.visible }.map { it.section }

    fun withVisible(section: HomeSection, visible: Boolean): HomeLayout =
        copy(sections = sections.map { if (it.section == section) it.copy(visible = visible) else it })

    fun styleOf(section: HomeSection): SectionStyle =
        sections.firstOrNull { it.section == section }?.style ?: SectionStyle.CAROUSEL

    fun withStyle(section: HomeSection, style: SectionStyle): HomeLayout =
        copy(sections = sections.map { if (it.section == section) it.copy(style = style) else it })

    /** [section] moved [delta] places (negative is up), clamped to the ends. */
    fun moved(section: HomeSection, delta: Int): HomeLayout {
        val from = sections.indexOfFirst { it.section == section }
        if (from < 0) return this
        val to = (from + delta).coerceIn(0, sections.lastIndex)
        if (to == from) return this
        val list = sections.toMutableList()
        val setting = list.removeAt(from)
        list.add(to, setting)
        return copy(sections = list)
    }

    /** The parts of the page in layout order, hidden sections left out. */
    fun <T> arrange(parts: Map<HomeSection, List<T>>): List<T> =
        visibleSections.flatMap { parts[it].orEmpty() }

    /**
     * `recents:1,my_playlists:1:g,youtube_feed:0`: key, shown flag and, only
     * when it is not the carousel, the style. A carousel section is written
     * exactly as before styles existed.
     */
    fun serialize(): String = sections.joinToString(",") { setting ->
        buildString {
            append(setting.section.key).append(':').append(if (setting.visible) 1 else 0)
            if (setting.style != SectionStyle.CAROUSEL) append(':').append(setting.style.key)
        }
    }

    companion object {
        val DEFAULT = HomeLayout(HomeSection.entries.map { HomeSectionSetting(it, it.visibleByDefault) })

        fun parse(raw: String?): HomeLayout {
            if (raw.isNullOrBlank()) return DEFAULT
            val seen = mutableSetOf<HomeSection>()
            val stored = raw.split(',').mapNotNull { part ->
                val key = part.substringBefore(':').trim()
                val section = HomeSection.fromKey(key) ?: return@mapNotNull null
                if (!seen.add(section)) return@mapNotNull null
                val fields = part.split(':').map { it.trim() }
                val flag = fields.getOrNull(1).orEmpty()
                // Unknown or missing style: the carousel, as before styles existed.
                val style = fields.getOrNull(2)?.let(SectionStyle::fromKey) ?: SectionStyle.CAROUSEL
                HomeSectionSetting(section, if (flag.isEmpty()) section.visibleByDefault else flag != "0", style)
            }
            val sections = stored.toMutableList()
            // A section the stored value does not know yet goes where it sits in
            // the default order: right after its nearest default predecessor the
            // user has, so their own order is kept around it.
            HomeSection.entries.forEachIndexed { defaultIndex, section ->
                if (section in seen) return@forEachIndexed
                val before = HomeSection.entries.take(defaultIndex).lastOrNull { it in seen }
                val at = if (before == null) 0 else sections.indexOfFirst { it.section == before } + 1
                sections.add(at, HomeSectionSetting(section, section.visibleByDefault))
                seen += section
            }
            return HomeLayout(sections)
        }
    }
}
