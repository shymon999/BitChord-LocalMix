package moe.rukamori.archivetune.localmix.equivalent

import org.json.JSONArray
import org.json.JSONObject

/**
 * The listener's verdicts on "official track = local file" matches.
 *
 * A verdict is about one pair: a catalog id and one file. The file is named by
 * its content URI and, when known, its path; the path survives a MediaStore
 * re-index that changes the URI, the URI covers files whose path is unknown.
 *
 *  - [reject] ("Detach file"): this file is never used for this track again,
 *    whatever the library does. Another file can still match later.
 *  - [confirm]: the listener checked the pair; it is no longer flagged as
 *    uncertain. A confirmation of a different file does not carry over.
 *
 * Not thread-safe; the owner synchronises.
 */
class MatchReviewBook {

    data class FileRef(val uri: String, val path: String?) {
        fun sameFile(uri: String, path: String?): Boolean =
            this.uri == uri || (!this.path.isNullOrBlank() && samePath(this.path, path))
    }

    /** One detached pair, with what is needed to show and restore it. */
    data class Rejection(
        val catalogId: String,
        val file: FileRef,
        val title: String? = null,
        val artist: String? = null,
        val at: Long = 0L,
    )

    private val rejected = LinkedHashMap<String, MutableList<Rejection>>()
    private val confirmed = HashMap<String, FileRef>()

    fun isRejected(catalogId: String, uri: String, path: String?): Boolean =
        rejected[catalogId]?.any { it.file.sameFile(uri, path) } == true

    fun isConfirmed(catalogId: String, uri: String, path: String?): Boolean =
        confirmed[catalogId]?.sameFile(uri, path) == true

    fun reject(rejection: Rejection) {
        val list = rejected.getOrPut(rejection.catalogId) { mutableListOf() }
        list.removeAll { it.file.sameFile(rejection.file.uri, rejection.file.path) }
        list += rejection
        val c = confirmed[rejection.catalogId]
        if (c != null && c.sameFile(rejection.file.uri, rejection.file.path)) confirmed.remove(rejection.catalogId)
    }

    /** Undoes [reject] for the pair; true when there was one. */
    fun restore(catalogId: String, uri: String, path: String?): Boolean {
        val list = rejected[catalogId] ?: return false
        val removed = list.removeAll { it.file.sameFile(uri, path) }
        if (list.isEmpty()) rejected.remove(catalogId)
        return removed
    }

    fun confirm(catalogId: String, file: FileRef) {
        confirmed[catalogId] = file
    }

    fun rejections(): List<Rejection> = rejected.values.flatten().sortedByDescending { it.at }

    fun toJson(): JSONObject {
        val rej = JSONArray()
        for (r in rejected.values.flatten()) {
            rej.put(
                JSONObject().apply {
                    put("id", r.catalogId)
                    put("u", r.file.uri)
                    r.file.path?.let { put("p", it) }
                    r.title?.let { put("t", it) }
                    r.artist?.let { put("a", it) }
                    put("at", r.at)
                },
            )
        }
        val conf = JSONObject()
        for ((id, f) in confirmed) {
            conf.put(id, JSONObject().apply {
                put("u", f.uri)
                f.path?.let { put("p", it) }
            })
        }
        return JSONObject().put("rejected", rej).put("confirmed", conf)
    }

    companion object {
        /** Reads what [toJson] wrote; missing or malformed parts are skipped, never fatal. */
        fun fromJson(o: JSONObject?): MatchReviewBook {
            val book = MatchReviewBook()
            if (o == null) return book
            val rej = o.optJSONArray("rejected") ?: JSONArray()
            for (i in 0 until rej.length()) {
                val r = rej.optJSONObject(i) ?: continue
                val id = r.optString("id").takeIf { it.isNotBlank() } ?: continue
                val uri = r.optString("u").takeIf { it.isNotBlank() } ?: continue
                book.reject(
                    Rejection(
                        catalogId = id,
                        file = FileRef(uri, r.optString("p").takeIf { it.isNotBlank() }),
                        title = r.optString("t").takeIf { it.isNotBlank() },
                        artist = r.optString("a").takeIf { it.isNotBlank() },
                        at = r.optLong("at", 0L),
                    ),
                )
            }
            val conf = o.optJSONObject("confirmed") ?: JSONObject()
            for (id in conf.keys()) {
                val c = conf.optJSONObject(id) ?: continue
                val uri = c.optString("u").takeIf { it.isNotBlank() } ?: continue
                book.confirm(id, FileRef(uri, c.optString("p").takeIf { it.isNotBlank() }))
            }
            return book
        }

        private fun samePath(a: String, b: String?): Boolean =
            !b.isNullOrBlank() && a.trim().equals(b.trim(), ignoreCase = true)
    }
}
