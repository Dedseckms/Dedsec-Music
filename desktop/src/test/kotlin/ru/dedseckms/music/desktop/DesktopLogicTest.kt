package ru.dedseckms.music.desktop

import ru.dedseckms.music.data.Song
import org.junit.Test
import kotlin.test.*
import kotlin.random.Random
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class DesktopLogicTest {
    @Test fun theFirstShuffledSkipUsesTheNewOrderAndBackReturnsToTheSelectedOccurrence() {
        val q = QueueState(); q.set((0..15).map { Song("$it", "Track", "Artist") }, 5)
        q.shuffle(Random(42)); assertEquals(5, q.order.first()); assertEquals(0, q.cursor)
        val next = q.order[1]; assertTrue(q.move(true, false)); assertEquals("$next", q.current!!.id)
        assertTrue(q.move(false, false)); assertEquals("5", q.current!!.id)
    }
    @Test fun aSingleTrackEndsWithoutWrappingUnlessRepeatIsEnabled() {
        val q = QueueState(); q.set(listOf(Song("one", "One", "Artist")), 0)
        assertFalse(q.move(true, false)); assertEquals("one", q.current!!.id)
        assertTrue(q.move(true, true)); assertEquals("one", q.current!!.id)
    }
    @Test fun duplicateSongIdsRetainTheirDistinctSourcePositions() {
        val q = QueueState(); q.set(listOf(Song("a", "A", "Artist"), Song("b", "B", "Artist"), Song("a", "A", "Artist")), 2)
        q.shuffle(Random(1)); assertEquals(2, q.order.first()); assertEquals(setOf(0, 1, 2), q.order.toSet())
    }
    @Test fun versionsAreComparedNumericallyAndMalformedVersionsAreRejected() {
        assertTrue(UpdateClient.newer("0.10.0", "0.9.9")); assertFalse(UpdateClient.newer("0.1.0", "0.1.0"))
        assertFails { UpdateClient.newer("../../evil", "0.1.0") }
    }
    private fun signed(body: String): Triple<ByteArray, ByteArray, ByteArray> {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); val raw = body.toByteArray()
        val signer = Signature.getInstance("Ed25519"); signer.initSign(pair.private); signer.update(raw)
        return Triple(raw, Base64.getEncoder().encode(signer.sign()), pair.public.encoded)
    }
    private fun manifest(url: String = "https://github.com/Dedseckms/Dedsec-Music/releases/download/desktop-v0.2.0/client.zip") = """{"schema":1,"windows-x64":{"version":"0.2.0","url":"$url","sha256":"${"a".repeat(64)}","size":100}}"""
    @Test fun updateSignaturesBindTheExactManifestBytes() {
        val (raw, sig, key) = signed(manifest())
        assertEquals("0.2.0", UpdateClient.verify(raw, sig, key)!!.version)
        raw[raw.lastIndex - 5] = (raw[raw.lastIndex - 5].toInt() xor 1).toByte()
        assertFails { UpdateClient.verify(raw, sig, key) }
    }
    @Test fun otherRepositoriesAndUnsignedManifestsCannotOfferUpdates() {
        val (raw, sig, key) = signed(manifest("https://github.com/other/project/releases/download/v1/a.zip"))
        assertFails { UpdateClient.verify(raw, sig, key) }
        val (good, _, _) = signed(manifest())
        assertFails { UpdateClient.verify(good, byteArrayOf(0), key) }
    }
}
