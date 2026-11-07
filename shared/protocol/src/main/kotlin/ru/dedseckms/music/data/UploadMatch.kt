package ru.dedseckms.music.data

/** A completed scan alone does not prove that a particular upload was indexed. */
object UploadMatch {
    fun find(filename: String, title: String, artist: String, durationMs: Long, candidates: List<Song>): Song? {
        candidates.firstOrNull { it.sourceFilename.equals(filename, true) }?.let { return it }
        if (title.isBlank() || artist.isBlank() || durationMs <= 0) return null
        return candidates.firstOrNull { song ->
            song.sourceFilename.isBlank() && song.title.equals(title.trim(), true) && song.artist.equals(artist.trim(), true) &&
                kotlin.math.abs(song.duration * 1000L - durationMs) <= 2000
        }
    }
}
