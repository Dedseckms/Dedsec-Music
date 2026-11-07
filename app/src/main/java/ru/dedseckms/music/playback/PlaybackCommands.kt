package ru.dedseckms.music.playback

import androidx.media3.common.Player

internal const val SHUFFLE_COMMAND = "dedsec.shuffle"
internal const val NEXT_COMMAND = "dedsec.next"
internal const val PREVIOUS_COMMAND = "dedsec.previous"

/** Resolve the next item in the owning player, never in a temporarily stale controller timeline. */
internal fun sessionSkip(player: Player, forward: Boolean) {
    if (forward && player.hasNextMediaItem() || !forward && player.hasPreviousMediaItem()) {
        player.pause()
        if (forward) player.seekToNextMediaItem() else player.seekToPreviousMediaItem()
        player.play()
    }
}

/** Keep a finished queue paused at the start of its last item, ready for any play control. */
internal fun pauseAtEnd(player: Player) {
    if (player.playbackState == Player.STATE_ENDED && player.repeatMode == Player.REPEAT_MODE_OFF && player.mediaItemCount > 0) {
        player.pause()
        player.seekTo(0L)
    }
}
