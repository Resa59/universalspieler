package de.rdoe.weeklydjshows.playback

import android.content.Context
import android.os.Environment
import androidx.work.*
import de.rdoe.weeklydjshows.database.DownloadStatus
import de.rdoe.weeklydjshows.database.WeeklyDjDatabase
import de.rdoe.weeklydjshows.model.DeliveryType
import de.rdoe.weeklydjshows.model.ResolveResult
import de.rdoe.weeklydjshows.model.StreamingPreferenceKeys
import de.rdoe.weeklydjshows.model.StreamingQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class EpisodeDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val episodeId = inputData.getString(KEY_EPISODE_ID) ?: return@withContext Result.failure()
        val db = WeeklyDjDatabase.get(applicationContext)
        val dao = db.episodeDao()
        val row = dao.getWithShow(episodeId) ?: return@withContext Result.failure()
        dao.updateDownload(episodeId, DownloadStatus.DOWNLOADING, null, 0, null)

        val downloadQuality = applicationContext
            .getSharedPreferences(StreamingPreferenceKeys.FILE, Context.MODE_PRIVATE)
            .getString(StreamingPreferenceKeys.DOWNLOAD_QUALITY, StreamingPreferenceKeys.DEFAULT_DOWNLOAD)
            .let { stored ->
                runCatching { StreamingQuality.valueOf(stored ?: StreamingPreferenceKeys.DEFAULT_DOWNLOAD) }
                    .getOrDefault(StreamingQuality.MAXIMUM)
            }
        val resolvedPair = PlaybackRepository(applicationContext, db).resolveEpisode(
            episodeId,
            preferredQuality = downloadQuality,
            downloadCompatibleOnly = true,
        )
        val source = (resolvedPair?.second as? ResolveResult.Success)?.source
        if (source == null || source.deliveryType !in setOf(DeliveryType.PROGRESSIVE_HTTP, DeliveryType.LOCAL_FILE)) {
            dao.updateDownload(episodeId, DownloadStatus.FAILED, null, 0, null)
            return@withContext Result.failure()
        }
        if (source.deliveryType == DeliveryType.LOCAL_FILE) {
            return@withContext Result.success()
        }

        val root = applicationContext.getExternalFilesDir(Environment.DIRECTORY_PODCASTS)
            ?: File(applicationContext.filesDir, "podcasts")
        val dir = File(root, "downloads").apply { mkdirs() }
        val extension = extensionFor(source.mimeType, source.playbackUrl)
        val target = File(dir, "$episodeId$extension")
        val partial = File(dir, "$episodeId$extension.part")

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val request = Request.Builder().url(source.playbackUrl).apply {
            source.requestHeaders.forEach { (name, value) -> header(name, value) }
        }.build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("Leere Audiodatei")
                val total = body.contentLength().takeIf { it >= 0 }
                var downloaded = 0L
                var lastReported = 0L
                var lastReportAt = System.currentTimeMillis()
                body.byteStream().use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (isStopped) throw CancellationException("Download abgebrochen")
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            val now = System.currentTimeMillis()
                            if (downloaded - lastReported >= 256 * 1024 || now - lastReportAt >= 500) {
                                lastReported = downloaded
                                lastReportAt = now
                                dao.updateDownload(episodeId, DownloadStatus.DOWNLOADING, null, downloaded, total)
                                setProgress(workDataOf("downloaded" to downloaded, "total" to (total ?: -1L)))
                            }
                        }
                    }
                }
                if (target.exists()) target.delete()
                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                val artworkUrl = source.artworkUrl ?: row.episode.artworkUrl ?: row.show.artworkUrl
                val localArtwork = artworkUrl?.let { downloadArtwork(client, it, dir, episodeId) }
                dao.setLocalArtworkPath(episodeId, localArtwork?.absolutePath)
                dao.updateDownload(episodeId, DownloadStatus.COMPLETE, target.absolutePath, downloaded, total)
                Result.success()
            }
        } catch (cancelled: CancellationException) {
            // WorkManager cancellation is an explicit user action. Do not turn it into FAILED
            // (or schedule a retry) after EpisodeDownloads.cancel() has cleared the DB state.
            partial.delete()
            throw cancelled
        } catch (error: Throwable) {
            partial.delete()
            dao.updateDownload(episodeId, DownloadStatus.FAILED, null, 0, null)
            Result.retry().takeIf { runAttemptCount < 2 } ?: Result.failure()
        }
    }

    private fun extensionFor(mime: String?, url: String): String {
        val lower = url.substringBefore('?').lowercase()
        return when {
            mime == "audio/mpeg" || lower.endsWith(".mp3") -> ".mp3"
            mime == "audio/mp4" || lower.endsWith(".m4a") -> ".m4a"
            mime == "audio/ogg" || lower.endsWith(".ogg") -> ".ogg"
            mime == "audio/opus" || lower.endsWith(".opus") -> ".opus"
            mime == "audio/aac" || lower.endsWith(".aac") -> ".aac"
            else -> ".audio"
        }
    }

    private fun downloadArtwork(client: OkHttpClient, url: String, dir: File, episodeId: String): File? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val target = File(dir, "$episodeId.cover")
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "WeeklyDJShows/1.3.1 (Android)")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                val declared = body.contentLength()
                if (declared > MAX_ARTWORK_BYTES) return@use null
                var copied = 0L
                target.outputStream().buffered().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            if (copied > MAX_ARTWORK_BYTES) error("Artwork too large")
                            output.write(buffer, 0, count)
                        }
                    }
                }
                target
            }
        }.getOrElse {
            target.delete()
            null
        }
    }

    companion object {
        const val KEY_EPISODE_ID = "episode_id"
        private const val MAX_ARTWORK_BYTES = 8L * 1024L * 1024L
    }
}

object EpisodeDownloads {
    suspend fun enqueue(context: Context, episodeId: String) {
        val db = WeeklyDjDatabase.get(context)
        db.episodeDao().updateDownload(episodeId, DownloadStatus.QUEUED, null, 0, null)
        val request = OneTimeWorkRequestBuilder<EpisodeDownloadWorker>()
            .setInputData(workDataOf(EpisodeDownloadWorker.KEY_EPISODE_ID to episodeId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "episode-download-$episodeId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    suspend fun delete(context: Context, episodeId: String) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork("episode-download-$episodeId")
        val dao = WeeklyDjDatabase.get(context).episodeDao()
        val episode = dao.get(episodeId) ?: return@withContext
        episode.localFilePath?.let(::File)?.takeIf { it.isFile }?.delete()
        episode.localArtworkPath?.let(::File)?.takeIf { it.isFile }?.delete()
        dao.clearDownload(episodeId)
    }

    suspend fun cancel(context: Context, episodeId: String) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork("episode-download-$episodeId")
        WeeklyDjDatabase.get(context).episodeDao().clearDownload(episodeId)
    }
}
