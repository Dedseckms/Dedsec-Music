package ru.dedseckms.music.data

/** Queue repeat cycles independently of the decoder; radio never repeats the generated queue. */
enum class PlaybackRepeat {
    OFF, ALL, ONE;
    fun next(wave: Boolean): PlaybackRepeat = if (wave) {
        if (this == ONE) OFF else ONE
    } else when (this) { OFF -> ALL; ALL -> ONE; ONE -> OFF }
}
