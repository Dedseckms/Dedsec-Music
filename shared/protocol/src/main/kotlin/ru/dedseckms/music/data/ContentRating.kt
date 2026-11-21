package ru.dedseckms.music.data

/** Provider/file metadata is a rating, not a guarantee about every word in audio. */
enum class ContentRating(val wire: String) {
    UNKNOWN(""), CLEAN("clean"), EXPLICIT("explicit");
    companion object {
        fun parse(value: String): ContentRating = entries.firstOrNull { it.wire == value.trim().lowercase() } ?: UNKNOWN
    }
}

/** Child mode excludes explicit recordings; unrated recordings remain available. */
object ContentPolicy {
    const val BLOCKED_MESSAGE = "Трек недоступен в детском режиме: метка взрослого контента E"
    fun allowed(song: Song, childMode: Boolean): Boolean = !childMode || song.contentRating != ContentRating.EXPLICIT
    fun selection(songs: List<Song>, selected: Int, childMode: Boolean): ContentSelection? {
        if (selected !in songs.indices || !allowed(songs[selected], childMode)) return null
        val filtered = songs.filter { allowed(it, childMode) }
        val index = songs.take(selected).count { allowed(it, childMode) }
        return ContentSelection(filtered, index)
    }
}

data class ContentSelection(val songs: List<Song>, val index: Int)

data class ContentRatingUpdate(val rating: ContentRating, val overridden: Boolean)
internal data class ContentRatingBatch(val ratings: Map<String, ContentRating>, val overrides: Map<String, ContentRating>)
