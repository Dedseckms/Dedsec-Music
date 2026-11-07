package ru.dedseckms.music

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import ru.dedseckms.music.playback.SessionArtwork

class SessionArtworkTest {
    @Test fun aLargePngIsReducedBeforeBeingSentThroughTheSystemSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("session-art-test", ".png", context.cacheDir)
        val source = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888)
        try {
            val random = kotlin.random.Random(7)
            val pixels = IntArray(768 * 768) { random.nextInt() or (0xFF shl 24) }
            source.setPixels(pixels, 0, 768, 0, 0, 768, 768)
            file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(file.length() > 1024 * 1024)
            val bytes = SessionArtwork.encode(file)!!
            assertTrue(bytes.size < 256 * 1024)
            val thumbnail = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertTrue(maxOf(thumbnail.width, thumbnail.height) <= 192)
            thumbnail.recycle()
        } finally { source.recycle(); file.delete() }
    }
}
