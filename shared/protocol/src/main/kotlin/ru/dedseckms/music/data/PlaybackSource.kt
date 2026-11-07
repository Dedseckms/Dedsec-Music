package ru.dedseckms.music.data

fun collectionSource(kind: String, title: String): String = when (kind) {
    "playlist" -> "Плейлист · $title"
    "album" -> "Альбом · $title"
    "artist" -> "Исполнитель · $title"
    "genre" -> "Жанр · $title"
    else -> title
}

/** The label is derived from the playing queue's metadata, not the currently browsed page. */
fun playbackSourceLabel(source: String, mix: Boolean, mood: MixMood): String = if (mix) {
    val name = when (mood) {
        MixMood.ANY -> "Любое настроение"
        MixMood.ENERGY -> "Драйв"
        MixMood.CHILL -> "Чилл"
        MixMood.FOCUS -> "Фокус"
        MixMood.WORKOUT -> "Спорт"
        MixMood.PARTY -> "Вечер"
        MixMood.SAD -> "Грусть"
        MixMood.HAPPY -> "Радость"
    }
    "Моя волна · $name"
} else source.ifBlank { "Сейчас играет" }
