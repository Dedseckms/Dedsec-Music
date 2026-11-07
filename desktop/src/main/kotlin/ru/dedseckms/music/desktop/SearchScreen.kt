package ru.dedseckms.music.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.dedseckms.music.data.*

@Composable internal fun SearchScreen(c: DesktopController, s: DesktopState, add: (Song) -> Unit) {
    val searching = s.query.isNotBlank()
    val matchingGenres = if (searching) s.genres.filter { SearchText.matches(s.query, it.name) } else emptyList()
    val showArtists = s.filter in listOf("Все", "Исполнители")
    val showSongs = s.filter in listOf("Все", "Треки")
    val showGenres = s.filter in listOf("Все", "Жанры")
    LazyColumn(contentPadding = PaddingValues(horizontal = 26.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(if (searching) "Результаты поиска" else "Найди свой звук", style = MaterialTheme.typography.headlineLarge)
            Text(if (searching) "По запросу «${s.query}»" else "Треки и исполнители — в строке выше. Или начни с жанра и настроения.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp, bottom = 12.dp))
        }
        if (searching) {
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(listOf("Все", "Треки", "Исполнители", "Жанры")) { filter -> FilterChip(s.filter == filter, { c.filter(filter) }, { Text(filter) }) } } }
            if (showArtists && s.search.artists.isNotEmpty()) {
                item { Section("Исполнители") }
                items(s.search.artists.take(20), key = { "artist:${it.id}" }) { artist ->
                    ListItem(headlineContent = { Text(artist.name) }, supportingContent = { Text("Открыть страницу исполнителя") }, leadingContent = { Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { c.rememberQuery(); c.artist(artist) })
                }
            }
            if (showSongs && s.search.songs.isNotEmpty()) {
                item { Section("Треки") }
                itemsIndexed(s.search.songs, key = { index, song -> "song:$index:${song.id}" }) { index, song ->
                    TrackRow(song, index, s, { c.rememberQuery(); c.play(s.search.songs, index, "Поиск · ${s.query}") }, { c.favorite(song) }, { add(song) }, { c.download(song) }, null)
                }
            }
            if (showGenres && matchingGenres.isNotEmpty()) {
                item { Section("Жанры") }
                items(matchingGenres, key = { "genre:${it.name}" }) { genre ->
                    ListItem(headlineContent = { Text(genre.name) }, supportingContent = { Text("${genre.songCount} треков · по тегам библиотеки") }, leadingContent = { Icon(Icons.Rounded.GraphicEq, null) }, modifier = Modifier.clickable { c.rememberQuery(); c.genre(genre) })
                }
            }
            if (!s.searching && (!showArtists || s.search.artists.isEmpty()) && (!showSongs || s.search.songs.isEmpty()) && (!showGenres || matchingGenres.isEmpty())) item {
                Text("Ничего не нашлось. Попробуй часть названия или другое написание.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
            }
        } else {
            if (s.recentSearches.isNotEmpty()) {
                item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Недавние запросы", Modifier.weight(1f), fontWeight = FontWeight.SemiBold); TextButton(c::clearSearchHistory) { Text("Очистить", fontSize = 11.sp) } } }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(s.recentSearches) { query -> InputChip(false, { c.search(query) }, { Text(query) }, leadingIcon = { Icon(Icons.Rounded.History, null, Modifier.size(16.dp)) }) } } }
            }
            item { Section("По настроению", "ДРУГОЙ СПОСОБ НАЙТИ МУЗЫКУ") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(listOf(Triple(MixMood.CHILL, "Чилл", Icons.Rounded.Waves), Triple(MixMood.ENERGY, "Драйв", Icons.Rounded.Bolt), Triple(MixMood.FOCUS, "Фокус", Icons.Rounded.CenterFocusStrong), Triple(MixMood.WORKOUT, "Спорт", Icons.Rounded.FitnessCenter)),
                        listOf(Triple(MixMood.PARTY, "Вечер", Icons.Rounded.Nightlife), Triple(MixMood.SAD, "Грусть", Icons.Rounded.Cloud), Triple(MixMood.HAPPY, "Радость", Icons.Rounded.WbSunny), Triple(MixMood.ANY, "Любое", Icons.Rounded.AutoAwesome))).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { (mood, title, icon) ->
                            Surface(onClick = { c.wave(mood) }, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp)); Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
                            }
                        } }
                    }
                }
            }
            if (s.genres.isNotEmpty()) {
                item { Section("Жанры библиотеки") }
                item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { s.genres.take(15).chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { genre ->
                        Surface(onClick = { c.genre(genre) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.weight(1f)) {
                            Column(Modifier.padding(16.dp)) { Text(genre.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, fontSize = 13.sp); Text("${genre.songCount} треков", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }; repeat(3 - row.size) { Spacer(Modifier.weight(1f)) } }
                } } }
            }
            if (s.artists.isNotEmpty()) {
                item { Section("Исполнители", "ПЕРЕЙТИ НА СТРАНИЦУ") }
                item { LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) { items(s.artists.take(20), key = Artist::id) { artist ->
                    Column(Modifier.width(110.dp).clip(RoundedCornerShape(18.dp)).clickable { c.artist(artist) }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(68.dp)) { Box(contentAlignment = Alignment.Center) { Text(artist.name.take(1).uppercase(), fontSize = 26.sp, color = MaterialTheme.colorScheme.onPrimaryContainer) } }
                        Text(artist.name, Modifier.padding(top = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
                    }
                } } }
            }
            if (s.catalogLoading) item { Text("Загружаем исполнителей и жанры…", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}
