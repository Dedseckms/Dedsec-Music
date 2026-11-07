package ru.dedseckms.music.data

enum class MixMood { ANY, ENERGY, CHILL, FOCUS, WORKOUT, PARTY, SAD, HAPPY }

/** A preference from existing genre/title tags, not an acoustic mood classifier. */
object MoodAffinity {
    private val terms = mapOf(
        MixMood.ENERGY to listOf("rock", "metal", "punk", "hard", "phonk", "dubstep", "рок", "метал"),
        MixMood.CHILL to listOf("chill", "ambient", "lounge", "lo-fi", "lofi", "downtempo", "acoustic", "джаз"),
        MixMood.FOCUS to listOf("instrumental", "ambient", "classical", "piano", "lofi", "lo-fi", "soundtrack", "инструмент", "классик"),
        MixMood.WORKOUT to listOf("phonk", "drum", "bass", "hard", "metal", "techno", "workout", "trap"),
        MixMood.PARTY to listOf("dance", "disco", "house", "club", "edm", "hyperpop", "remix", "танцев"),
        MixMood.SAD to listOf("sad", "emo", "melanch", "blues", "груст", "печаль", "грусть"),
        MixMood.HAPPY to listOf("happy", "funk", "disco", "ska", "reggae", "радость", "счаст"),
    )
    fun weight(song: Song, mood: MixMood): Double {
        if (mood == MixMood.ANY) return 1.0
        val text = (song.genres + listOf(song.genre, song.title, song.album)).joinToString(" ").lowercase(java.util.Locale.ROOT)
        return if (terms[mood].orEmpty().any(text::contains)) 8.0 else .35
    }
}
