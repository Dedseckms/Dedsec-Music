package ru.dedseckms.music.data

import java.text.Normalizer
import java.util.Locale

object SearchText {
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace('ё', 'е')
    fun matches(query: String, text: String): Boolean {
        val haystack = normalize(text)
        return normalize(query).trim().split(Regex("\\s+")).filter(String::isNotBlank).all(haystack::contains)
    }
}
