package de.rdoe.weeklydjshows

import de.rdoe.weeklydjshows.database.ShowEntity
import de.rdoe.weeklydjshows.database.LatestMode
import de.rdoe.weeklydjshows.database.PodcastCategory
import de.rdoe.weeklydjshows.database.ShowOrigin
import de.rdoe.weeklydjshows.model.ShowSourceType
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Portable, human-readable snapshot of the Shows start-page configuration. */
internal object ShowViewTransfer {
    const val FORMAT = "weekly-dj-shows-view"
    const val SCHEMA_VERSION = 2
    const val MAX_SHOWS = 2_000

    data class Snapshot(
        val showOrderMode: ShowOrderMode,
        val shows: List<Entry>,
        /** Export IDs in the exact persisted custom order of all subscribed shows. */
        val startOrderIds: List<String>,
        val viewSettings: ViewSettings = ViewSettings(),
    )

    data class ViewSettings(
        val wordPodcastsEnabled: Boolean = true,
        val musicPodcastsEnabled: Boolean = true,
        val wordPodcastsInLatest: Boolean = true,
        val musicPodcastsInLatest: Boolean = true,
        val hideScheduledFromLatest: Boolean = false,
    )

    data class Entry(
        val id: String,
        val title: String,
        val feedUrl: String?,
        val platformUrl: String?,
        val sourceType: ShowSourceType,
        val description: String,
        val artworkUrl: String?,
        val subscribed: Boolean,
        val category: PodcastCategory,
        val categoryUserAssigned: Boolean,
        val origin: ShowOrigin,
        val latestMode: LatestMode,
        val autoPruneMissingEpisodes: Boolean,
        val sortOrder: Int,
        val standardSortOrder: Int?,
        val orderCustomized: Boolean,
        val orderAnchorBeforeId: String?,
        val orderAnchorAfterId: String?,
        val legacyModuleId: Long?,
        val addedAtEpochMs: Long,
    )

    fun encode(
        shows: List<ShowEntity>,
        showOrderMode: ShowOrderMode,
        settings: AppSettingsState,
        appVersion: String,
        exportedAtEpochMs: Long = System.currentTimeMillis(),
    ): String {
        val ordered = shows.sortedWith(compareBy<ShowEntity> { it.sortOrder }.thenBy { it.title.lowercase(Locale.ROOT) })
        val root = JSONObject()
            .put("format", FORMAT)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("appVersion", appVersion)
            .put("exportedAtEpochMs", exportedAtEpochMs)
            .put("showOrderMode", showOrderMode.name)
            .put(
                "viewSettings",
                JSONObject()
                    .put("wordPodcastsEnabled", settings.wordPodcastsEnabled)
                    .put("musicPodcastsEnabled", settings.musicPodcastsEnabled)
                    .put("wordPodcastsInLatest", settings.wordPodcastsInLatest)
                    .put("musicPodcastsInLatest", settings.musicPodcastsInLatest)
                    .put("hideScheduledFromLatest", settings.hideScheduledFromLatest),
            )

        val startOrder = JSONArray()
        ordered.filter { it.subscribed }.forEach { startOrder.put(it.id) }
        root.put("startOrder", startOrder)

        val array = JSONArray()
        ordered.forEach { show ->
            array.put(
                JSONObject()
                    .put("id", show.id)
                    .put("title", show.title)
                    .putNullable("feedUrl", show.feedUrl)
                    .putNullable("platformUrl", show.platformUrl)
                    .put("sourceType", show.sourceType.name)
                    .put("description", show.description)
                    .putNullable("artworkUrl", show.artworkUrl)
                    .put("subscribed", show.subscribed)
                    .put("category", show.category.name)
                    .put("categoryUserAssigned", show.categoryUserAssigned)
                    .put("origin", show.origin.name)
                    // Keep the old boolean for 1.2.7 readers while newer builds retain all three
                    // states through the additive latestMode field.
                    .put("hideFromLatest", show.hideFromLatest)
                    .put("latestMode", show.latestMode.name)
                    .put("autoPruneMissingEpisodes", show.autoPruneMissingEpisodes)
                    .put("sortOrder", show.sortOrder)
                    .putNullable("standardSortOrder", show.standardSortOrder)
                    .put("orderCustomized", show.orderCustomized)
                    .putNullable("orderAnchorBeforeId", show.orderAnchorBeforeId)
                    .putNullable("orderAnchorAfterId", show.orderAnchorAfterId)
                    .putNullable("legacyModuleId", show.legacyModuleId)
                    .put("addedAtEpochMs", show.addedAtEpochMs),
            )
        }
        root.put("shows", array)
        return root.toString(2)
    }

    fun decode(raw: String): Snapshot {
        val root = JSONObject(raw)
        require(root.optString("format") == FORMAT) { "Keine Weekly-DJ-Shows-Ansichtsdatei" }
        val schemaVersion = root.optInt("schemaVersion", -1)
        require(schemaVersion in 1..SCHEMA_VERSION) {
            "Nicht unterstützte Exportversion ${root.optInt("schemaVersion", -1)}"
        }
        val array = root.getJSONArray("shows")
        require(array.length() <= MAX_SHOWS) { "Export enthält zu viele Shows" }
        val seenIds = mutableSetOf<String>()
        val shows = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id").trim()
            require(id.isNotBlank() && seenIds.add(id)) { "Ungültige oder doppelte Show-ID" }
            val title = item.getString("title").trim()
            require(title.isNotBlank()) { "Show ohne Namen" }
            val sourceType = runCatching { ShowSourceType.valueOf(item.getString("sourceType")) }
                .getOrElse { throw IllegalArgumentException("Unbekannte Showquelle") }
            val feedUrl = item.nullableString("feedUrl")
            val platformUrl = item.nullableString("platformUrl")
            val legacyModuleId = if (item.isNull("legacyModuleId")) null else item.optLong("legacyModuleId")
            require(feedUrl != null || platformUrl != null || legacyModuleId != null) {
                "Show ohne gespeicherte Quelle"
            }
            val legacyHidden = item.optBoolean("hideFromLatest", false)
            val latestMode = item.optString("latestMode").takeIf { it.isNotBlank() }?.let { rawMode ->
                runCatching { LatestMode.valueOf(rawMode) }
                    .getOrElse { throw IllegalArgumentException("Unbekannter Neu-Modus") }
            } ?: if (legacyHidden) LatestMode.NONE else LatestMode.ALL
            Entry(
                id = id,
                title = title,
                feedUrl = feedUrl,
                platformUrl = platformUrl,
                sourceType = sourceType,
                description = item.optString("description"),
                artworkUrl = item.nullableString("artworkUrl"),
                subscribed = item.optBoolean("subscribed", true),
                category = runCatching {
                    PodcastCategory.valueOf(item.optString("category", PodcastCategory.MUSIC.name))
                }.getOrDefault(PodcastCategory.MUSIC),
                categoryUserAssigned = item.optBoolean("categoryUserAssigned", false),
                origin = runCatching {
                    ShowOrigin.valueOf(item.optString("origin", ShowOrigin.USER.name))
                }.getOrDefault(ShowOrigin.USER),
                latestMode = latestMode,
                autoPruneMissingEpisodes = item.optBoolean("autoPruneMissingEpisodes", false),
                sortOrder = item.optInt("sortOrder", index),
                standardSortOrder = if (item.isNull("standardSortOrder")) null else item.optInt("standardSortOrder"),
                orderCustomized = item.optBoolean("orderCustomized", false),
                orderAnchorBeforeId = item.nullableString("orderAnchorBeforeId"),
                orderAnchorAfterId = item.nullableString("orderAnchorAfterId"),
                legacyModuleId = legacyModuleId,
                addedAtEpochMs = item.optLong("addedAtEpochMs", System.currentTimeMillis()),
            )
        }
        val fallbackOrder = shows.filter { it.subscribed }.sortedBy { it.sortOrder }.map { it.id }
        val orderArray = root.optJSONArray("startOrder")
        val startOrder = if (orderArray == null) {
            fallbackOrder
        } else {
            buildList {
                for (index in 0 until orderArray.length()) {
                    orderArray.optString(index).takeIf { it in seenIds && it !in this }?.let(::add)
                }
                fallbackOrder.filterNot { it in this }.forEach(::add)
            }
        }
        val orderMode = runCatching { ShowOrderMode.valueOf(root.optString("showOrderMode")) }
            .getOrDefault(ShowOrderMode.CUSTOM)
        val view = root.optJSONObject("viewSettings")
        return Snapshot(
            orderMode,
            shows,
            startOrder,
            ViewSettings(
                wordPodcastsEnabled = view?.optBoolean("wordPodcastsEnabled", true) ?: true,
                musicPodcastsEnabled = view?.optBoolean("musicPodcastsEnabled", true) ?: true,
                wordPodcastsInLatest = view?.optBoolean("wordPodcastsInLatest", true) ?: true,
                musicPodcastsInLatest = view?.optBoolean("musicPodcastsInLatest", true) ?: true,
                hideScheduledFromLatest = view?.optBoolean("hideScheduledFromLatest", false) ?: false,
            ),
        )
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().takeIf { it.isNotBlank() }
}
