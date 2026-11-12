package ru.dedseckms.music.data

data class RadioMix(val songs: List<Song>, val indexed: Int, val total: Int, val reason: String?) {
    fun emptyMessage(): String = when (reason) {
        "ANALYZING" -> "Сервер ещё анализирует музыку. Попробуй позже или выбери другое настроение"
        else -> "Нет подходящих треков. Попробуй другое настроение или режим"
    }
}
