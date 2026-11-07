package ru.dedseckms.music.data

data class CoverTarget(val coverArt: String, val size: Int)

/** Warm the actual playback neighbours, never the whole queue. Large covers are ready before a swipe. */
fun neighborArtwork(current: Song?, next: Song?, previous: Song?): List<CoverTarget> = buildList {
    current?.coverArt?.takeIf(String::isNotBlank)?.let { add(CoverTarget(it, 192)); add(CoverTarget(it, 768)) }
    listOfNotNull(next, previous).forEach { song ->
        song.coverArt.takeIf(String::isNotBlank)?.let { add(CoverTarget(it, 768)); add(CoverTarget(it, 192)) }
    }
}.distinct()
