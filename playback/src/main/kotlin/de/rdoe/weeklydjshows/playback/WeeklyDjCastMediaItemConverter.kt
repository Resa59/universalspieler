package de.rdoe.weeklydjshows.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaQueueItem

/**
 * Gives the Cast receiver network-safe media metadata while local playback may keep using
 * downloaded file:// audio/artwork. Google's default converter then transports title, artist,
 * album and artwork as Cast metadata independently from the audio URL.
 */
@OptIn(UnstableApi::class)
internal class WeeklyDjCastMediaItemConverter : MediaItemConverter {
    private val delegate = DefaultMediaItemConverter()

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val extras = mediaItem.mediaMetadata.extras
        val castArtworkUri = extras?.getString(MEDIA_EXTRA_CAST_ARTWORK_URI)
            ?.takeIf { it.isNotBlank() }
        val castPlaybackUri = extras?.getString(MEDIA_EXTRA_CAST_PLAYBACK_URI)
            ?.takeIf { it.isNotBlank() }

        val castMetadata = mediaItem.mediaMetadata.buildUpon()
            .apply { castArtworkUri?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        val castItem = mediaItem.buildUpon()
            .setMediaMetadata(castMetadata)
            .apply { castPlaybackUri?.let { setUri(Uri.parse(it)) } }
            .build()
        return delegate.toMediaQueueItem(castItem)
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem =
        delegate.toMediaItem(mediaQueueItem)
}
