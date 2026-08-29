package de.rdoe.weeklydjshows

import android.content.Context
import android.net.Uri
import android.os.Environment
import de.rdoe.weeklydjshows.database.*
import de.rdoe.weeklydjshows.model.EpisodeSourceType
import de.rdoe.weeklydjshows.model.ResolverErrorType
import de.rdoe.weeklydjshows.model.StreamingQuality
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Complete, portable backup. The ZIP is streamed, so including downloads needs little RAM. */
internal object FullBackupTransfer {
    const val FORMAT = "weekly-dj-shows-backup"
    const val SCHEMA_VERSION = 1
    private const val MANIFEST_ENTRY = "backup.json"
    private const val MAX_MANIFEST_BYTES = 32L * 1024L * 1024L

    data class ImportSummary(
        val shows: Int,
        val episodes: Int,
        val historyEvents: Int,
        val downloads: Int,
        /** Present only for imports; the caller owns the live settings instance. */
        val importedSettings: AppSettingsState? = null,
    )

    suspend fun export(
        context: Context,
        database: WeeklyDjDatabase,
        settings: AppSettingsState,
        uri: Uri,
        appVersion: String,
        includeDownloads: Boolean,
    ): ImportSummary {
        val shows = database.showDao().getAll()
        val episodes = database.episodeDao().getAll()
        val history = database.playbackHistoryDao().getAll()
        val queue = database.queueDao().getAll()
        val audioEntries = linkedMapOf<String, File>()
        val artworkEntries = linkedMapOf<String, File>()
        if (includeDownloads) {
            episodes.forEach { episode ->
                episode.localFilePath?.let(::File)?.takeIf(File::isFile)?.let { file ->
                    audioEntries[episode.id] = file
                }
                episode.localArtworkPath?.let(::File)?.takeIf(File::isFile)?.let { file ->
                    artworkEntries[episode.id] = file
                }
            }
        }
        val root = JSONObject()
            .put("format", FORMAT)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("appVersion", appVersion)
            .put("exportedAtEpochMs", System.currentTimeMillis())
            .put("downloadsIncluded", includeDownloads)
            .put(
                "showView",
                JSONObject(ShowViewTransfer.encode(shows, settings.showOrderMode, settings, appVersion)),
            )
            .put("settings", encodeSettings(settings))
            .put("episodes", JSONArray().apply {
                episodes.forEach { episode ->
                    put(encodeEpisode(episode, audioEntries[episode.id], artworkEntries[episode.id]))
                }
            })
            .put("history", JSONArray().apply {
                history.forEach { event ->
                    put(JSONObject().put("episodeId", event.episodeId).put("playedAtEpochMs", event.playedAtEpochMs))
                }
            })
            .put("queue", JSONArray().apply {
                queue.sortedBy { it.position }.forEach { entry -> put(entry.episodeId) }
            })

        val output = context.contentResolver.openOutputStream(uri, "w")
            ?: error("Zieldatei konnte nicht geöffnet werden")
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(root.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            audioEntries.forEach { (id, file) -> zipFile(zip, file, audioEntry(id, file)) }
            artworkEntries.forEach { (id, file) -> zipFile(zip, file, artworkEntry(id, file)) }
        }
        return ImportSummary(shows.size, episodes.size, history.size, audioEntries.size)
    }

    suspend fun import(context: Context, database: WeeklyDjDatabase, uri: Uri): ImportSummary {
        val temporary = File(context.cacheDir, "full-backup-import-${System.nanoTime()}").apply { mkdirs() }
        try {
            var manifest: ByteArray? = null
            var extractedBytes = 0L
            val input = context.contentResolver.openInputStream(uri) ?: error("Importdatei konnte nicht geöffnet werden")
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    require(!entry.isDirectory && ".." !in name.split('/')) { "Ungültiger ZIP-Eintrag" }
                    when {
                        name == MANIFEST_ENTRY -> {
                            manifest = zip.readBounded(MAX_MANIFEST_BYTES)
                        }
                        name.startsWith("downloads/") -> {
                            val target = File(temporary, name.removePrefix("downloads/"))
                            require(target.canonicalPath.startsWith(temporary.canonicalPath + File.separator)) {
                                "Ungültiger Downloadpfad"
                            }
                            target.parentFile?.mkdirs()
                            target.outputStream().buffered().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    extractedBytes += count
                                    require(extractedBytes <= MAX_EXTRACTED_DOWNLOAD_BYTES) { "Sicherung ist zu groß" }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val root = JSONObject(String(requireNotNull(manifest) { "backup.json fehlt" }, Charsets.UTF_8))
            require(root.optString("format") == FORMAT) { "Keine vollständige Weekly-DJ-Shows-Sicherung" }
            require(root.optInt("schemaVersion", -1) == SCHEMA_VERSION) { "Nicht unterstützte Sicherungsversion" }

            val showSnapshot = ShowViewTransfer.decode(root.getJSONObject("showView").toString())
            mergeShows(database, showSnapshot)

            val downloadRoot = (context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS)
                ?: File(context.filesDir, "podcasts")).resolve("downloads").apply { mkdirs() }
            val importedEpisodes = mutableListOf<EpisodeEntity>()
            var restoredDownloads = 0
            val episodeArray = root.getJSONArray("episodes")
            require(episodeArray.length() <= MAX_EPISODES) { "Sicherung enthält zu viele Folgen" }
            for (index in 0 until episodeArray.length()) {
                val json = episodeArray.getJSONObject(index)
                val incoming = decodeEpisode(json)
                if (database.showDao().get(incoming.showId) == null) continue
                val old = database.episodeDao().get(incoming.id)
                val audio = restoreDownloadFile(temporary, downloadRoot, json.optString("audioEntry"), incoming.id)
                val artwork = restoreDownloadFile(temporary, downloadRoot, json.optString("artworkEntry"), "${incoming.id}.cover")
                if (audio != null) restoredDownloads++
                importedEpisodes += mergeEpisode(old, incoming, audio, artwork)
            }
            for (chunk in importedEpisodes.chunked(400)) {
                database.episodeDao().upsertAll(chunk)
            }

            val historyArray = root.optJSONArray("history") ?: JSONArray()
            val events = buildList {
                for (index in 0 until historyArray.length()) {
                    val json = historyArray.getJSONObject(index)
                    val id = json.optString("episodeId")
                    if (database.episodeDao().get(id) != null) {
                        add(PlaybackHistoryEntity(episodeId = id, playedAtEpochMs = json.optLong("playedAtEpochMs")))
                    }
                }
            }
            for (chunk in events.chunked(400)) {
                database.playbackHistoryDao().insertAll(chunk)
            }

            val existingQueue = database.queueDao().getAll().sortedBy { it.position }.map { it.episodeId }
            val queueArray = root.optJSONArray("queue") ?: JSONArray()
            val queueIds = buildList {
                for (index in 0 until queueArray.length()) {
                    queueArray.optString(index).takeIf { database.episodeDao().get(it) != null }?.let(::add)
                }
                existingQueue.filterNot { it in this }.forEach(::add)
            }.distinct()
            database.queueDao().insertAll(queueIds.mapIndexed { position, id ->
                QueueEntryEntity(episodeId = id, position = position)
            })
            database.queueDao().reorder(queueIds)

            val importedSettings = root.optJSONObject("settings")?.let(::decodeSettings)
            return ImportSummary(
                showSnapshot.shows.size,
                importedEpisodes.size,
                events.size,
                restoredDownloads,
                importedSettings,
            )
        } finally {
            temporary.deleteRecursively()
        }
    }

    private suspend fun mergeShows(database: WeeklyDjDatabase, snapshot: ShowViewTransfer.Snapshot) {
        val old = database.showDao().getAll().associateBy { it.id }
        val imported = snapshot.shows.map { entry ->
            val current = old[entry.id]
            ShowEntity(
                id = entry.id,
                title = entry.title,
                feedUrl = entry.feedUrl,
                platformUrl = entry.platformUrl,
                sourceType = entry.sourceType,
                description = entry.description,
                artworkUrl = entry.artworkUrl,
                subscribed = entry.subscribed,
                origin = entry.origin,
                standardCatalogVersion = current?.standardCatalogVersion,
                orderAnchorBeforeId = entry.orderAnchorBeforeId,
                orderAnchorAfterId = entry.orderAnchorAfterId,
                hideFromLatest = entry.latestMode == LatestMode.NONE,
                latestMode = entry.latestMode,
                category = entry.category,
                categoryUserAssigned = entry.categoryUserAssigned,
                autoPruneMissingEpisodes = entry.autoPruneMissingEpisodes,
                sortOrder = entry.sortOrder,
                standardSortOrder = entry.standardSortOrder,
                orderCustomized = entry.orderCustomized,
                legacyModuleId = entry.legacyModuleId,
                addedAtEpochMs = entry.addedAtEpochMs,
                lastRefreshAtEpochMs = current?.lastRefreshAtEpochMs,
                lastRefreshError = current?.lastRefreshError,
            )
        }
        val importedIds = imported.mapTo(hashSetOf()) { it.id }
        val extras = old.values.filter { it.subscribed && it.id !in importedIds }.sortedBy { it.sortOrder }.map { it.id }
        database.importShowView(imported, snapshot.startOrderIds + extras)
    }

    private fun mergeEpisode(
        old: EpisodeEntity?,
        incoming: EpisodeEntity,
        audio: File?,
        artwork: File?,
    ): EpisodeEntity {
        val importedIsNewer = (incoming.lastPlayedAtEpochMs ?: 0L) >= (old?.lastPlayedAtEpochMs ?: 0L)
        return incoming.copy(
            liked = incoming.liked || old?.liked == true,
            positionMs = if (importedIsNewer) incoming.positionMs else old?.positionMs ?: incoming.positionMs,
            playbackDurationMs = if (importedIsNewer) incoming.playbackDurationMs else old?.playbackDurationMs,
            lastPlayedAtEpochMs = maxOf(incoming.lastPlayedAtEpochMs ?: 0L, old?.lastPlayedAtEpochMs ?: 0L).takeIf { it > 0L },
            completedAtEpochMs = maxOf(incoming.completedAtEpochMs ?: 0L, old?.completedAtEpochMs ?: 0L).takeIf { it > 0L },
            downloadStatus = if (audio != null) DownloadStatus.COMPLETE else old?.downloadStatus ?: DownloadStatus.NONE,
            localFilePath = audio?.absolutePath ?: old?.localFilePath,
            localArtworkPath = artwork?.absolutePath ?: old?.localArtworkPath,
            downloadedBytes = audio?.length() ?: old?.downloadedBytes ?: 0L,
            downloadTotalBytes = audio?.length() ?: old?.downloadTotalBytes,
        )
    }

    private fun encodeEpisode(episode: EpisodeEntity, audio: File?, artwork: File?): JSONObject = JSONObject()
        .put("id", episode.id).put("showId", episode.showId).put("title", episode.title)
        .put("description", episode.description).putNullable("pageUrl", episode.pageUrl)
        .putNullable("enclosureUrl", episode.enclosureUrl).put("sourceType", episode.sourceType.name)
        .putNullable("publishedAtEpochMs", episode.publishedAtEpochMs).put("publishedText", episode.publishedText)
        .putNullable("artworkUrl", episode.artworkUrl).putNullable("durationMs", episode.durationMs)
        .put("liked", episode.liked).put("positionMs", episode.positionMs)
        .putNullable("playbackDurationMs", episode.playbackDurationMs)
        .putNullable("lastPlayedAtEpochMs", episode.lastPlayedAtEpochMs)
        .putNullable("completedAtEpochMs", episode.completedAtEpochMs)
        .put("discoveredAtEpochMs", episode.discoveredAtEpochMs)
        .put("availability", episode.availability.name)
        .putNullable("scheduledForEpochMs", episode.scheduledForEpochMs)
        .putNullable("availabilityCheckedAtEpochMs", episode.availabilityCheckedAtEpochMs)
        .putNullable("resolverErrorType", episode.resolverErrorType?.name)
        .putNullable("resolverErrorMessage", episode.resolverErrorMessage)
        .putNullable("audioEntry", audio?.let { audioEntry(episode.id, it) })
        .putNullable("artworkEntry", artwork?.let { artworkEntry(episode.id, it) })

    private fun decodeEpisode(json: JSONObject): EpisodeEntity = EpisodeEntity(
        id = json.getString("id"), showId = json.getString("showId"), title = json.getString("title"),
        description = json.optString("description"), pageUrl = json.nullableString("pageUrl"),
        enclosureUrl = json.nullableString("enclosureUrl"),
        sourceType = enumValue(json, "sourceType", EpisodeSourceType.UNKNOWN_WEBPAGE),
        publishedAtEpochMs = json.nullableLong("publishedAtEpochMs"), publishedText = json.optString("publishedText"),
        artworkUrl = json.nullableString("artworkUrl"), durationMs = json.nullableLong("durationMs"),
        liked = json.optBoolean("liked"), positionMs = json.optLong("positionMs"),
        playbackDurationMs = json.nullableLong("playbackDurationMs"),
        lastPlayedAtEpochMs = json.nullableLong("lastPlayedAtEpochMs"),
        completedAtEpochMs = json.nullableLong("completedAtEpochMs"),
        discoveredAtEpochMs = json.optLong("discoveredAtEpochMs", System.currentTimeMillis()),
        availability = enumValue(json, "availability", EpisodeAvailability.UNKNOWN),
        scheduledForEpochMs = json.nullableLong("scheduledForEpochMs"),
        availabilityCheckedAtEpochMs = json.nullableLong("availabilityCheckedAtEpochMs"),
        resolverErrorType = json.nullableString("resolverErrorType")?.let {
            runCatching { ResolverErrorType.valueOf(it) }.getOrNull()
        },
        resolverErrorMessage = json.nullableString("resolverErrorMessage"),
    )

    private fun encodeSettings(value: AppSettingsState): JSONObject = JSONObject()
        .put("wifiQuality", value.wifiQuality.name).put("mobileQuality", value.mobileQuality.name)
        .put("downloadQuality", value.downloadQuality.name).put("showOrderMode", value.showOrderMode.name)
        .put("bluetoothAutoOpenDevices", JSONArray(value.bluetoothAutoOpenDevices.toList()))
        .put("bluetoothAutoResumeDevices", JSONArray(value.bluetoothAutoResumeDevices.toList()))
        .put("miniPlayerControls", value.miniPlayerControls.name).put("startupScreen", value.startupScreen.name)
        .put("wordPodcastsEnabled", value.wordPodcastsEnabled).put("musicPodcastsEnabled", value.musicPodcastsEnabled)
        .put("wordPodcastsInLatest", value.wordPodcastsInLatest).put("musicPodcastsInLatest", value.musicPodcastsInLatest)
        .put("hideScheduledFromLatest", value.hideScheduledFromLatest).put("refreshOnColdStart", value.refreshOnColdStart)
        .put("exitAfterIdle", value.exitAfterIdle).put("resumeOfferEnabled", value.resumeOfferEnabled)
        .put("miniPlayerImplementation", value.miniPlayerImplementation.name).put("overlaySize", value.overlaySize.name)
        .put("overlayLayout", value.overlayLayout.name)
        .put("autoMiniPlayerOnBackground", value.autoMiniPlayerOnBackground)
        .put("bluetoothLaunchMode", value.bluetoothLaunchMode.name).put("bluetoothAutoplayMode", value.bluetoothAutoplayMode.name)
        .put("bluetoothDisplayMode", value.bluetoothDisplayMode.name).put("autostartOfferMode", value.autostartOfferMode.name)
        .putNullable("autostartShowId", value.autostartShowId)
        .put("appUpdateChecksEnabled", value.appUpdateChecksEnabled).put("newPipeChecksEnabled", value.newPipeChecksEnabled)

    private fun decodeSettings(json: JSONObject): AppSettingsState = AppSettingsState(
        wifiQuality = enumValue(json, "wifiQuality", StreamingQuality.HIGH),
        mobileQuality = enumValue(json, "mobileQuality", StreamingQuality.MEDIUM),
        downloadQuality = enumValue(json, "downloadQuality", StreamingQuality.MAXIMUM),
        showOrderMode = enumValue(json, "showOrderMode", ShowOrderMode.CUSTOM),
        bluetoothAutoOpenDevices = json.stringSet("bluetoothAutoOpenDevices"),
        bluetoothAutoResumeDevices = json.stringSet("bluetoothAutoResumeDevices"),
        miniPlayerControls = enumValue(json, "miniPlayerControls", MiniPlayerControls.SEEK),
        startupScreen = enumValue(json, "startupScreen", StartupScreen.LATEST),
        wordPodcastsEnabled = json.optBoolean("wordPodcastsEnabled", true),
        musicPodcastsEnabled = json.optBoolean("musicPodcastsEnabled", true),
        wordPodcastsInLatest = json.optBoolean("wordPodcastsInLatest", true),
        musicPodcastsInLatest = json.optBoolean("musicPodcastsInLatest", true),
        hideScheduledFromLatest = json.optBoolean("hideScheduledFromLatest", false),
        refreshOnColdStart = json.optBoolean("refreshOnColdStart", true),
        exitAfterIdle = json.optBoolean("exitAfterIdle", true),
        resumeOfferEnabled = json.optBoolean("resumeOfferEnabled", true),
        miniPlayerImplementation = enumValue(json, "miniPlayerImplementation", MiniPlayerImplementation.SYSTEM_PIP),
        overlaySize = enumValue(json, "overlaySize", OverlaySize.SMALL),
        overlayLayout = enumValue(json, "overlayLayout", OverlayLayout.SQUARE_COVER),
        autoMiniPlayerOnBackground = json.optBoolean("autoMiniPlayerOnBackground", false),
        bluetoothLaunchMode = enumValue(json, "bluetoothLaunchMode", BluetoothLaunchMode.FULL_APP),
        bluetoothAutoplayMode = enumValue(json, "bluetoothAutoplayMode", BluetoothAutoplayMode.ACTIVE_ONLY),
        bluetoothDisplayMode = enumValue(json, "bluetoothDisplayMode", BluetoothDisplayMode.OFFER),
        autostartOfferMode = enumValue(json, "autostartOfferMode", AutostartOfferMode.INTERRUPTED_THEN_SELECTED),
        autostartShowId = json.nullableString("autostartShowId"),
        appUpdateChecksEnabled = json.optBoolean("appUpdateChecksEnabled", true),
        newPipeChecksEnabled = json.optBoolean("newPipeChecksEnabled", false),
    )

    private fun zipFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().buffered().use { it.copyTo(zip, 64 * 1024) }
        zip.closeEntry()
    }

    private fun restoreDownloadFile(temp: File, root: File, entry: String, fallbackName: String): File? {
        if (!entry.startsWith("downloads/") || ".." in entry.split('/')) return null
        val source = File(temp, entry.removePrefix("downloads/"))
        if (!source.isFile) return null
        val extension = source.name.substringAfter(fallbackName, source.name.substringAfterLast('.', "audio"))
        val targetName = if (fallbackName.contains('.')) fallbackName else fallbackName +
            source.name.substringAfter(fallbackName, "").takeIf { it.startsWith('.') }.orEmpty()
        val target = File(root, targetName.ifBlank { "$fallbackName.$extension" })
        source.copyTo(target, overwrite = true)
        return target
    }

    private fun audioEntry(id: String, file: File) = "downloads/$id${file.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()}"
    private fun artworkEntry(id: String, file: File) = "downloads/$id.cover${file.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()}"

    private fun java.io.InputStream.readBounded(limit: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Sicherungsbeschreibung ist zu groß" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private inline fun <reified T : Enum<T>> enumValue(json: JSONObject, key: String, fallback: T): T =
        runCatching { enumValueOf<T>(json.optString(key, fallback.name)) }.getOrDefault(fallback)

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    private fun JSONObject.nullableLong(key: String): Long? = if (!has(key) || isNull(key)) null else optLong(key)
    private fun JSONObject.stringSet(key: String): Set<String> = buildSet {
        val array = optJSONArray(key) ?: return@buildSet
        for (index in 0 until array.length()) array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
    }

    private const val MAX_EPISODES = 200_000
    private const val MAX_EXTRACTED_DOWNLOAD_BYTES = 20L * 1024L * 1024L * 1024L
}
