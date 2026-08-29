package de.rdoe.weeklydjshows.uicomponents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.CachePolicy
import coil.request.ImageRequest
import de.rdoe.weeklydjshows.database.*
import de.rdoe.weeklydjshows.model.EpisodeSourceType
import de.rdoe.weeklydjshows.model.ShowSourceType
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun Artwork(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    diskCache: Boolean = true,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(BrandNavy),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            ArtworkPlaceholder()
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(url)
                    .diskCacheKey(url)
                    .memoryCacheKey(url)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(if (diskCache) CachePolicy.ENABLED else CachePolicy.DISABLED)
                    .crossfade(false)
                    .build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun EpisodeArtwork(item: EpisodeWithShow, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val episode = item.episode
    val localArtwork = episode.localArtworkPath
        ?.takeIf { episode.downloadStatus == DownloadStatus.COMPLETE && File(it).isFile }
        ?.let { File(it).toURI().toString() }
    val episodeUrl = localArtwork ?: episode.artworkUrl

    if (episodeUrl.isNullOrBlank() || episodeUrl == item.show.artworkUrl) {
        Artwork(item.show.artworkUrl, episode.title, modifier, diskCache = true)
        return
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(BrandNavy),
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(episodeUrl)
                .memoryCachePolicy(CachePolicy.ENABLED)
                // Episode artwork is transient unless it belongs to an explicit offline download.
                .diskCachePolicy(if (localArtwork != null) CachePolicy.ENABLED else CachePolicy.DISABLED)
                .crossfade(false)
                .build(),
            contentDescription = episode.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = {
                Artwork(item.show.artworkUrl, episode.title, Modifier.fillMaxSize(), diskCache = true)
            },
            error = {
                Artwork(item.show.artworkUrl, episode.title, Modifier.fillMaxSize(), diskCache = true)
            },
            success = { SubcomposeAsyncImageContent() },
        )
    }
}

@Composable
private fun ArtworkPlaceholder() {
    Icon(
        Icons.Default.Headphones,
        contentDescription = null,
        tint = BrandPink,
        modifier = Modifier.size(30.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShowGridItem(
    show: ShowEntity,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    artworkOverride: (@Composable (Modifier) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val source = showSourceLabel(show)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 3.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val artworkModifier = Modifier.fillMaxWidth().aspectRatio(1f)
        if (artworkOverride != null) {
            artworkOverride(artworkModifier)
        } else {
            Artwork(show.artworkUrl, show.title, artworkModifier, diskCache = true)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            show.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        // On the home screen RSS is the normal case, not useful per-card metadata. Keep only
        // platform labels here so YouTube/SoundCloud/etc. remain immediately distinguishable.
        if (show.lastRefreshError != null) {
            Text(
                "Aktualisierung fehlgeschlagen",
                style = MaterialTheme.typography.labelSmall,
                color = BrandPink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else if (source != "RSS") {
            Text(
                source,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun ShowCard(show: ShowEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
    ) {
        Row(Modifier.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(show.artworkUrl, show.title, Modifier.size(78.dp), diskCache = true)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    show.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(showSourceLabel(show), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                if (show.lastRefreshError != null) {
                    Spacer(Modifier.height(3.dp))
                    Text("Letzte Aktualisierung fehlgeschlagen", style = MaterialTheme.typography.labelSmall, color = BrandPink)
                }
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeCard(
    item: EpisodeWithShow,
    onOpen: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onShow: (() -> Unit)? = null,
    onPlay: () -> Unit,
    onLike: () -> Unit,
    onDownload: () -> Unit,
    onQueue: () -> Unit,
    isQueued: Boolean = false,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val episode = item.episode
    val completed = episode.completedAtEpochMs != null
    val scheduled = episode.availability == EpisodeAvailability.SCHEDULED
    val duration = episode.playbackDurationMs ?: episode.durationMs
    val progress = episode.displayPlaybackProgress()
    val normalCard = MaterialTheme.colorScheme.surface
    val cardColor = when {
        scheduled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)
        completed -> Color.Black.copy(alpha = 0.18f).compositeOver(normalCard)
        else -> normalCard
    }
    val shape = RoundedCornerShape(16.dp)

    Box(modifier.fillMaxWidth().clip(shape)) {
        Surface(
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLongClick),
            shape = shape,
            color = cardColor,
            tonalElevation = if (completed) 0.dp else 1.dp,
        ) {
            Row(
                Modifier
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                EpisodeArtwork(
                    item,
                    // SubcomposeAsyncImage cannot participate in intrinsic measurements. A
                    // bounded square also makes Coil down-sample very large episode artwork.
                    Modifier.size(124.dp).alpha(if (scheduled) 0.48f else 1f),
                )
                Spacer(Modifier.width(12.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 124.dp),
                ) {
                    Text(
                        episode.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.show.title,
                        modifier = if (onShow != null) {
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(onClick = onShow)
                                .padding(vertical = 2.dp)
                        } else {
                            Modifier
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    if (scheduled) {
                        Text(
                            "Geplant · noch nicht verfügbar",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        episode.publishedAtEpochMs?.let {
                            Text(shortDate(it), style = MaterialTheme.typography.labelSmall)
                        }
                        duration?.takeIf { it > 0L }?.let {
                            if (episode.publishedAtEpochMs != null) Spacer(Modifier.width(7.dp))
                            Text(
                                episodeDurationLabel(it),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        if (episode.downloadStatus == DownloadStatus.COMPLETE) {
                            if (episode.publishedAtEpochMs != null || duration?.let { it > 0L } == true) {
                                Spacer(Modifier.width(6.dp))
                            }
                            Icon(Icons.Default.OfflinePin, "Offline verfügbar", Modifier.size(15.dp), tint = BrandGreen)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val mixcloudExternal = episode.sourceType == EpisodeSourceType.MIXCLOUD
                        SmallActionButton(onClick = onLike) {
                            Icon(
                                if (episode.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = if (episode.liked) "Gefällt mir entfernen" else "Gefällt mir",
                                tint = if (episode.liked) BrandPink else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!mixcloudExternal) {
                            if (!scheduled) DownloadAction(episode, onDownload)
                            SmallActionButton(onClick = onQueue) {
                                Icon(
                                    if (isQueued) Icons.Filled.PlaylistAddCheck else Icons.Filled.PlaylistAdd,
                                    contentDescription = if (isQueued) "Aus Warteschlange entfernen" else "Zur Warteschlange",
                                    tint = if (isQueued) BrandGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        FilledIconButton(onClick = onPlay, modifier = Modifier.size(42.dp)) {
                            Icon(
                                if (mixcloudExternal) {
                                    Icons.Default.OpenInNew
                                } else if (scheduled) {
                                    Icons.Outlined.Schedule
                                } else if (isCurrent && isPlaying) {
                                    Icons.Default.Pause
                                } else {
                                    Icons.Default.PlayArrow
                                },
                                contentDescription = if (mixcloudExternal) {
                                    "In Mixcloud öffnen"
                                } else if (scheduled) {
                                    "Noch nicht verfügbar"
                                } else if (isCurrent && isPlaying) {
                                    "Pause"
                                } else {
                                    "Abspielen"
                                },
                            )
                        }
                    }
                }
            }
        }
        if (progress != null && progress > 0f) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress)
                    .height(3.dp)
                    .background(BrandPink),
            )
        }
    }
}

@Composable
private fun SmallActionButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(38.dp), content = content)
}

@Composable
fun DownloadAction(episode: EpisodeEntity, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (episode.downloadStatus) {
            DownloadStatus.DOWNLOADING -> {
                val total = episode.downloadTotalBytes
                if (total != null && total > 0) {
                    CircularProgressIndicator(
                        progress = { (episode.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.size(25.dp),
                        strokeWidth = 2.5.dp,
                    )
                } else {
                    CircularProgressIndicator(Modifier.size(25.dp), strokeWidth = 2.5.dp)
                }
            }
            DownloadStatus.QUEUED -> Icon(Icons.Default.Downloading, "Download wartet")
            DownloadStatus.COMPLETE -> Icon(Icons.Default.DownloadDone, "Download löschen")
            DownloadStatus.FAILED -> Icon(Icons.Outlined.Download, "Download erneut versuchen", tint = BrandPink)
            DownloadStatus.NONE -> Icon(Icons.Outlined.Download, "Herunterladen")
        }
    }
}

fun showSourceLabel(show: ShowEntity): String = when (show.sourceType) {
    ShowSourceType.YOUTUBE_CHANNEL -> when (platformPathSuffix(show.platformUrl)) {
        "streams" -> "YouTube · Livestreams"
        "videos" -> "YouTube · Videos"
        "shorts" -> "YouTube · Shorts"
        else -> "YouTube · Kanal"
    }
    ShowSourceType.YOUTUBE_PLAYLIST -> "YouTube · Playlist"
    ShowSourceType.SOUNDCLOUD -> if (isSoundCloudPlaylist(show.platformUrl)) {
        "SoundCloud · Playlist"
    } else {
        "SoundCloud · Profil"
    }
    ShowSourceType.MIXCLOUD -> "Mixcloud · Profil"
    ShowSourceType.SPOTIFY_PLAYLIST -> "Spotify · Playlist-Link"
    ShowSourceType.BANDCAMP -> "Bandcamp"
    ShowSourceType.PEERTUBE -> "PeerTube"
    // A feed host is transport, not provenance. In particular, feeds.soundcloud.com hosts real
    // RSS podcasts from the legacy catalogue; those must not be relabelled as platform sources.
    ShowSourceType.RSS -> "RSS"
    ShowSourceType.PLATFORM_LINK -> when {
        show.platformUrl?.contains("1001tracklists", true) == true -> "1001Tracklists · Webseite"
        show.platformUrl?.contains("spotify", true) == true -> "Spotify"
        show.platformUrl?.contains("mixcloud", true) == true -> "Mixcloud"
        show.platformUrl?.contains("youtube", true) == true -> "YouTube"
        show.platformUrl?.contains("soundcloud", true) == true -> "SoundCloud"
        else -> "Plattform"
    }
}

private fun platformPathSuffix(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        java.net.URI(raw).path.orEmpty().trimEnd('/').substringAfterLast('/').lowercase()
    }.getOrNull()
}

private fun isSoundCloudPlaylist(raw: String?): Boolean = raw?.let {
    Regex("https?://(?:www\\.)?soundcloud\\.com/[^/?#]+/sets/", RegexOption.IGNORE_CASE).containsMatchIn(it)
} == true

fun shortDate(epochMs: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))

fun episodeDurationLabel(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

fun sourceLabel(type: EpisodeSourceType): String = when (type) {
    EpisodeSourceType.YOUTUBE -> "YouTube"
    EpisodeSourceType.SOUNDCLOUD -> "SoundCloud"
    EpisodeSourceType.MIXCLOUD -> "Mixcloud"
    EpisodeSourceType.SPOTIFY -> "Spotify"
    EpisodeSourceType.BANDCAMP -> "Bandcamp"
    EpisodeSourceType.PEERTUBE -> "PeerTube"
    EpisodeSourceType.UNKNOWN_WEBPAGE -> "Web"
    EpisodeSourceType.DIRECT_AUDIO -> "RSS"
}
