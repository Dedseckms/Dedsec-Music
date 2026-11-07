package ru.dedseckms.music

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InterfaceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun searchHintDisappearsOnFocusAndIncompleteArtistAlreadyMatches() {
        compose.onNodeWithText("Посмотреть интерфейс без входа").performScrollTo().performClick()
        compose.onNodeWithText("Поиск").performClick()
        compose.onNodeWithText("Трек, исполнитель или жанр").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNodeWithText("Трек, исполнитель или жанр").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("Тих")
        compose.onNodeWithText("Исполнители").performClick()
        compose.waitUntil(3000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Тихий сигнал")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Тихий сигнал").assertIsDisplayed()
    }

    @Test fun returningFromPlaylistAndAlbumKeepsTheLibrarySection() {
        compose.onNodeWithText("Посмотреть интерфейс без входа").performScrollTo().performClick()
        compose.onNodeWithText("Музыка").performClick()
        compose.onNodeWithText("Плейлисты").performClick()
        compose.onNodeWithText("Ночной маршрут").performClick()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Плейлисты").assertIsSelected()
        compose.onNodeWithText("Ночной маршрут").assertIsDisplayed()
        compose.onNodeWithText("Альбомы").performClick()
        compose.onNodeWithText(Demo.albums.first().name).performClick()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Альбомы").assertIsSelected()
    }

    @Test fun loginDemoLibraryUploadAndLogoutAreNavigable() {
        compose.onNodeWithText("DedSec Music").assertIsDisplayed()
        compose.onNodeWithText("Посмотреть интерфейс без входа").performScrollTo().performClick()
        compose.onNodeWithText("Моя волна").assertIsDisplayed()
        compose.onNodeWithText("Музыка").performClick()
        compose.onNodeWithText("Плейлисты").performClick()
        compose.onNodeWithText("Ночной маршрут").assertIsDisplayed()
        compose.onNodeWithText("Добавить").performClick()
        compose.onNodeWithText("Добавить музыку").assertIsDisplayed()
        compose.onNodeWithText("Войти").performClick()
        compose.onNodeWithText("Твоя музыка.\nСвои правила.").assertIsDisplayed()
    }

    @Test fun searchOpensAnArtistPageAndReturnsToTheResults() {
        compose.onNodeWithText("Посмотреть интерфейс без входа").performScrollTo().performClick()
        compose.onNodeWithText("Поиск").performClick()
        compose.onNodeWithText("Исполнители").performClick()
        compose.onNodeWithText("Тихий сигнал").performClick()
        compose.onNodeWithText("Слушать исполнителя").assertIsDisplayed()
        compose.onNodeWithText("Альбомы").assertIsDisplayed()
    }
}
