package de.rdoe.weeklydjshows.database

/** True once a new playback session has begun after an earlier completed run. */
fun EpisodeEntity.isReplayAfterCompletion(): Boolean {
    val completedAt = completedAtEpochMs ?: return false
    return (lastPlayedAtEpochMs ?: 0L) > completedAt
}

/**
 * Progress shown in episode cards. A completed run stays visibly full until the episode is started
 * again; after that, the new session's position wins while the separate heard marker remains.
 */
fun EpisodeEntity.displayPlaybackProgress(): Float? {
    if (completedAtEpochMs != null && !isReplayAfterCompletion()) return 1f
    val duration = (playbackDurationMs ?: durationMs)?.takeIf { it > 0L } ?: return null
    return (positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
}
