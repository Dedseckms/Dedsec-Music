package ru.dedseckms.music.data

import org.junit.Assert.*
import org.junit.Test

class UploadPolicyTest {
    @Test fun validAudioAtTheExactLimitsIsAccepted() { UploadPolicy.validate("track.flac", 100_000_000, 1_200_000, false) }
    @Test fun oversizedAudioIsRejectedBeforeNetworkAccess() { assertThrows(UploadRejected::class.java) { UploadPolicy.validate("track.mp3", 100_000_001, 120000, false) } }
    @Test fun longAudioAndVideoRenamedToAudioAreRejected() {
        assertThrows(UploadRejected::class.java) { UploadPolicy.validate("track.mp3", 10, 1_200_001, false) }
        assertThrows(UploadRejected::class.java) { UploadPolicy.validate("video.m4a", 10, 120000, true) }
        assertThrows(UploadRejected::class.java) { UploadPolicy.validate("fake.mp3", 10, 0, false) }
    }
    @Test fun unknownDeclaredSizeStillHasAnActualStreamingByteLimit() {
        UploadPolicy.validate("track.ogg", -1, 120000, false)
        UploadPolicy.checkBytes(100_000_000)
        assertThrows(UploadRejected::class.java) { UploadPolicy.checkBytes(100_000_001) }
    }
}
