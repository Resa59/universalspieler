package de.rdoe.weeklydjshows

import android.content.Context
import de.rdoe.weeklydjshows.database.LatestMode
import de.rdoe.weeklydjshows.database.PodcastCategory
import de.rdoe.weeklydjshows.database.ShowEntity
import de.rdoe.weeklydjshows.database.ShowOrigin
import de.rdoe.weeklydjshows.database.WeeklyDjDatabase
import de.rdoe.weeklydjshows.model.ShowSourceType
import java.text.Collator
import java.util.Locale

/**
 * Catalogue/layout handoff for the clean 1.3 data model, based on the user's 2026-08-08 view.
 *
 * The export itself remains bundled verbatim as an asset. Runtime refresh/playback state is kept
 * for matching IDs; only the requested catalogue fields, visibility and ordering are applied.
 */
object BundledShowLayoutV128 {
    private const val ASSET = "curated-show-layout-v128.json"
    private const val PREF_FILE = "weekly_dj_internal"
    private const val PREF_APPLIED_VERSION = "curated_show_catalog_version"
    private const val FIXED_PODCAST_COUNT = 27
    private const val AUTO_CLEANUP_PODCAST_COUNT = 17
    private const val WORD_PODCAST_COUNT = 17
    private const val NEWS_LATEST_ONLY_COUNT = 2

    internal const val CATALOG_VERSION = 130

    internal const val TRACKLISTS_ID = "9beab86eefec44d59823e3baf46d47ad"
    private const val TRACKLISTS_LEGACY_MODULE_ID = 31138801L
    internal const val TRACKLISTS_URL = "https://www.1001tracklists.com/"

    fun needsApply(context: Context): Boolean = appliedVersion(context) == 0

    fun hasPendingUpdate(context: Context): Boolean = appliedVersion(context) in 1 until CATALOG_VERSION

    private fun appliedVersion(context: Context): Int = context
        .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        .getInt(PREF_APPLIED_VERSION, 0)

    suspend fun applyIfNeeded(context: Context, database: WeeklyDjDatabase): Boolean {
        if (!needsApply(context)) return false

        val raw = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        val snapshot = ShowViewTransfer.decode(raw)
        require(snapshot.startOrderIds.size >= FIXED_PODCAST_COUNT) {
            "Gebündelte Show-Reihenfolge ist unvollständig"
        }
        val exportedById = snapshot.shows.associateBy { it.id }
        val fixedIds = snapshot.startOrderIds.take(FIXED_PODCAST_COUNT)
        require(fixedIds.all { exportedById[it]?.subscribed == true }) {
            "Eine der 27 fest angeordneten Shows fehlt"
        }
        val latestOnlyIds = snapshot.startOrderIds.take(NEWS_LATEST_ONLY_COUNT).toSet()
        val autoCleanupIds = snapshot.startOrderIds.take(AUTO_CLEANUP_PODCAST_COUNT).toSet()
        val wordPodcastIds = snapshot.startOrderIds.take(WORD_PODCAST_COUNT).toSet() + ADDITIONAL_WORD_PODCAST_IDS

        val existing = database.showDao().getAll()
        val existingById = existing.associateBy { it.id }
        val merged = linkedMapOf<String, ShowEntity>()
        existing.forEach { merged[it.id] = it }

        snapshot.shows.forEach { entry ->
            val current = existingById[entry.id]
            val latestMode = when {
                entry.id in latestOnlyIds -> LatestMode.LATEST_ONLY
                entry.sourceType == ShowSourceType.SPOTIFY_PLAYLIST -> LatestMode.NONE
                else -> entry.latestMode
            }
            val autoCleanup = entry.id in autoCleanupIds &&
                entry.sourceType == ShowSourceType.RSS && entry.feedUrl != null
            val standardCategory = if (entry.id in wordPodcastIds) PodcastCategory.WORD else PodcastCategory.MUSIC
            merged[entry.id] = if (current == null) {
                ShowEntity(
                    id = entry.id,
                    title = entry.title,
                    feedUrl = entry.feedUrl,
                    platformUrl = entry.platformUrl,
                    sourceType = entry.sourceType,
                    description = entry.description,
                    artworkUrl = entry.artworkUrl,
                    subscribed = entry.subscribed,
                    origin = ShowOrigin.BUNDLED,
                    standardCatalogVersion = CATALOG_VERSION,
                    hideFromLatest = latestMode == LatestMode.NONE,
                    latestMode = latestMode,
                    category = standardCategory,
                    autoPruneMissingEpisodes = autoCleanup,
                    legacyModuleId = entry.legacyModuleId,
                    addedAtEpochMs = entry.addedAtEpochMs,
                )
            } else {
                current.copy(
                    title = entry.title,
                    feedUrl = entry.feedUrl,
                    platformUrl = entry.platformUrl,
                    sourceType = entry.sourceType,
                    description = entry.description.ifBlank { current.description },
                    artworkUrl = entry.artworkUrl ?: current.artworkUrl,
                    subscribed = entry.subscribed,
                    origin = ShowOrigin.BUNDLED,
                    standardCatalogVersion = CATALOG_VERSION,
                    hideFromLatest = latestMode == LatestMode.NONE,
                    latestMode = latestMode,
                    category = if (current.categoryUserAssigned) current.category else standardCategory,
                    categoryUserAssigned = current.categoryUserAssigned,
                    autoPruneMissingEpisodes = autoCleanup,
                    legacyModuleId = entry.legacyModuleId ?: current.legacyModuleId,
                )
            }
        }

        val currentTracklists = existingById[TRACKLISTS_ID]
        merged[TRACKLISTS_ID] = (currentTracklists ?: ShowEntity(
            id = TRACKLISTS_ID,
            title = "1001Tracklists",
            platformUrl = TRACKLISTS_URL,
            sourceType = ShowSourceType.PLATFORM_LINK,
            description = "1001Tracklists – Tracklists für DJ-Sets, Radioshows und Mixes.",
            subscribed = true,
            origin = ShowOrigin.BUNDLED,
            standardCatalogVersion = CATALOG_VERSION,
            hideFromLatest = true,
            latestMode = LatestMode.NONE,
            legacyModuleId = TRACKLISTS_LEGACY_MODULE_ID,
        )).copy(
            title = "1001Tracklists",
            feedUrl = null,
            platformUrl = TRACKLISTS_URL,
            sourceType = ShowSourceType.PLATFORM_LINK,
            subscribed = true,
            origin = ShowOrigin.BUNDLED,
            standardCatalogVersion = CATALOG_VERSION,
            hideFromLatest = true,
            latestMode = LatestMode.NONE,
            category = PodcastCategory.MUSIC,
            autoPruneMissingEpisodes = false,
            legacyModuleId = TRACKLISTS_LEGACY_MODULE_ID,
        )

        val fixedSet = fixedIds.toSet()
        val collator = Collator.getInstance(Locale.GERMAN).apply { strength = Collator.PRIMARY }
        val alphabetical = Comparator<ShowEntity> { left, right ->
            val byTitle = collator.compare(left.title, right.title)
            if (byTitle != 0) byTitle else left.id.compareTo(right.id)
        }
        val fixed = fixedIds.map { id -> requireNotNull(merged[id]) }
        val visibleRemainder = merged.values
            .filter { it.subscribed && it.id != TRACKLISTS_ID && it.id !in fixedSet }
            .sortedWith(alphabetical)
        val hidden = merged.values
            .filterNot { it.subscribed }
            .sortedWith(alphabetical)
        val ordered = (listOf(requireNotNull(merged[TRACKLISTS_ID])) + fixed + visibleRemainder + hidden)
            .mapIndexed { index, show ->
                show.copy(
                    sortOrder = index,
                    standardSortOrder = index.takeIf { show.origin == ShowOrigin.BUNDLED },
                    orderCustomized = show.orderCustomized.takeIf { show.origin == ShowOrigin.USER } ?: false,
                )
            }

        database.importShowView(
            shows = ordered,
            subscribedOrder = ordered.filter { it.subscribed }.map { it.id },
        )
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_APPLIED_VERSION, CATALOG_VERSION)
            .commit()
        return true
    }

    data class CatalogUpdatePreview(
        val version: Int,
        val addedTitles: List<String>,
        val reorderedTitles: List<String>,
        val retiredTitles: List<String>,
    )

    /** Returns a user-facing diff only for an already installed older bundled catalogue. */
    suspend fun previewUpdate(context: Context, database: WeeklyDjDatabase): CatalogUpdatePreview? {
        if (!hasPendingUpdate(context)) return null
        val current = database.showDao().getAll()
        val desired = desiredCatalog(context)
        val currentBundled = current.filter { it.origin == ShowOrigin.BUNDLED }
        val currentById = current.associateBy { it.id }
        val desiredById = desired.associateBy { it.id }
        val added = desired.filter { it.id !in currentById }.map { it.title }
        val retired = currentBundled.filter { it.id !in desiredById }.map { it.title }

        val common = currentBundled.map { it.id }.intersect(desiredById.keys)
        val oldOrder = currentBundled
            .filter { it.id in common && !it.orderCustomized }
            .sortedBy { it.standardSortOrder ?: it.sortOrder }
            .map { it.id }
        val newOrder = desired.filter { it.id in common && currentById[it.id]?.orderCustomized != true }.map { it.id }
        fun neighbours(order: List<String>, id: String): Pair<String?, String?> {
            val index = order.indexOf(id)
            return order.getOrNull(index - 1) to order.getOrNull(index + 1)
        }
        val reorderedIds = oldOrder.filter { id ->
            id in newOrder && neighbours(oldOrder, id) != neighbours(newOrder, id)
        }.toSet()
        val reordered = desired.filter { it.id in reorderedIds }.map { it.title }
        return CatalogUpdatePreview(CATALOG_VERSION, added, reordered, retired)
    }

    /**
     * Merges a newer bundled catalogue after the two explicit user choices. New standard blocks
     * may follow the new order, while customized/user rows are reinserted between their previous
     * neighbours. Rejecting the standard order keeps the current order and appends new shows.
     */
    suspend fun applyCatalogUpdate(
        context: Context,
        database: WeeklyDjDatabase,
        acceptStandardOrder: Boolean,
        removeRetired: Boolean,
    ): Boolean {
        if (!hasPendingUpdate(context)) return false
        val showDao = database.showDao()
        val current = showDao.getAll().sortedBy { it.sortOrder }
        val currentById = current.associateBy { it.id }
        val desired = desiredCatalog(context)
        val desiredById = desired.associateBy { it.id }
        val retired = current.filter { it.origin == ShowOrigin.BUNDLED && it.id !in desiredById }

        if (removeRetired) {
            retired.forEach { show ->
                database.episodeDao().getForShow(show.id).forEach { episode ->
                    episode.localFilePath?.let { java.io.File(it) }?.takeIf { it.isFile }?.delete()
                    episode.localArtworkPath?.let { java.io.File(it) }?.takeIf { it.isFile }?.delete()
                }
                showDao.delete(show.id)
            }
        }

        val retainedRetired = if (removeRetired) emptyList() else retired.map { show ->
            show.copy(
                origin = ShowOrigin.USER,
                standardCatalogVersion = null,
                standardSortOrder = null,
                orderCustomized = true,
            )
        }
        val surviving = current
            .filterNot { it.id in retired.mapTo(hashSetOf()) { retiredShow -> retiredShow.id } }
            .associateByTo(linkedMapOf()) { it.id }
        retainedRetired.forEach { surviving[it.id] = it }

        desired.forEach { standard ->
            val old = currentById[standard.id]
            surviving[standard.id] = if (old == null) {
                standard
            } else {
                old.copy(
                    // Keep the local display name. Renaming is an explicit user action and is
                    // safer to preserve than guessing whether a changed catalogue title matters.
                    feedUrl = standard.feedUrl,
                    platformUrl = standard.platformUrl,
                    sourceType = standard.sourceType,
                    description = standard.description.ifBlank { old.description },
                    artworkUrl = standard.artworkUrl ?: old.artworkUrl,
                    subscribed = old.subscribed,
                    origin = ShowOrigin.BUNDLED,
                    standardCatalogVersion = CATALOG_VERSION,
                    standardSortOrder = standard.standardSortOrder,
                    category = if (old.categoryUserAssigned) old.category else standard.category,
                )
            }
        }

        val all = surviving.values.toList()
        val newIds = desired.filter { it.id !in currentById }.mapTo(hashSetOf()) { it.id }
        val subscribedOrder = buildList {
            surviving[TRACKLISTS_ID]?.takeIf { it.subscribed }?.let { add(it.id) }
            PodcastCategory.entries.forEach { category ->
                val oldCategory = current.filter {
                    it.subscribed && it.id != TRACKLISTS_ID &&
                        (surviving[it.id]?.category ?: it.category) == category && it.id in surviving
                }
                val categoryRows = all.filter {
                    it.subscribed && it.id != TRACKLISTS_ID && it.category == category
                }
                if (!acceptStandardOrder) {
                    oldCategory.map { it.id }.filter { id -> categoryRows.any { it.id == id } }.forEach(::add)
                    desired.filter { it.id in newIds && surviving[it.id]?.subscribed == true && surviving[it.id]?.category == category }
                        .map { it.id }
                        .filterNot { it in this }
                        .forEach(::add)
                    categoryRows.map { it.id }.filterNot { it in this }.forEach(::add)
                } else {
                    val customizedIds = categoryRows.filter {
                        it.origin == ShowOrigin.USER || it.orderCustomized
                    }.mapTo(hashSetOf()) { it.id }
                    val placed = desired
                        .filter { standard ->
                            standard.category == category && standard.id !in customizedIds &&
                                surviving[standard.id]?.subscribed == true
                        }
                        .map { it.id }
                        .toMutableList()
                    val oldIds = oldCategory.map { it.id }
                    val pins = categoryRows.filter { it.id in customizedIds }.sortedBy { row ->
                        oldIds.indexOf(row.id).takeIf { it >= 0 } ?: Int.MIN_VALUE
                    }
                    pins.forEach { pin ->
                        val oldIndex = oldIds.indexOf(pin.id)
                        val previousCandidates = buildList {
                            pin.orderAnchorBeforeId?.let(::add)
                            if (oldIndex > 0) oldIds.take(oldIndex).asReversed().forEach(::add)
                        }
                        val nextCandidates = buildList {
                            pin.orderAnchorAfterId?.let(::add)
                            if (oldIndex >= 0) oldIds.drop(oldIndex + 1).forEach(::add)
                        }
                        val previous = previousCandidates.firstOrNull { it in placed }
                        val next = nextCandidates.firstOrNull { it in placed }
                        val target = when {
                            previous != null -> placed.indexOf(previous) + 1
                            next != null -> placed.indexOf(next)
                            else -> 0
                        }.coerceIn(0, placed.size)
                        placed.add(target, pin.id)
                    }
                    categoryRows.map { it.id }.filterNot { it in placed }.forEach { placed.add(0, it) }
                    placed.filterNot { it in this }.forEach(::add)
                }
            }
        }.distinct()

        database.importShowView(all, subscribedOrder)
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().putInt(PREF_APPLIED_VERSION, CATALOG_VERSION).commit()
        return true
    }

    /** Constructs the app-defined standard list without any local runtime/user state. */
    private fun desiredCatalog(context: Context): List<ShowEntity> {
        val snapshot = context.assets.open(ASSET).bufferedReader().use { ShowViewTransfer.decode(it.readText()) }
        val fixedIds = snapshot.startOrderIds.take(FIXED_PODCAST_COUNT)
        val latestOnly = snapshot.startOrderIds.take(NEWS_LATEST_ONLY_COUNT).toSet()
        val cleanup = snapshot.startOrderIds.take(AUTO_CLEANUP_PODCAST_COUNT).toSet()
        val word = snapshot.startOrderIds.take(WORD_PODCAST_COUNT).toSet() + ADDITIONAL_WORD_PODCAST_IDS
        val rows = snapshot.shows.associate { entry ->
            val mode = when {
                entry.id in latestOnly -> LatestMode.LATEST_ONLY
                entry.sourceType == ShowSourceType.SPOTIFY_PLAYLIST -> LatestMode.NONE
                else -> entry.latestMode
            }
            entry.id to ShowEntity(
                id = entry.id,
                title = entry.title,
                feedUrl = entry.feedUrl,
                platformUrl = entry.platformUrl,
                sourceType = entry.sourceType,
                description = entry.description,
                artworkUrl = entry.artworkUrl,
                subscribed = entry.subscribed,
                origin = ShowOrigin.BUNDLED,
                standardCatalogVersion = CATALOG_VERSION,
                hideFromLatest = mode == LatestMode.NONE,
                latestMode = mode,
                category = if (entry.id in word) PodcastCategory.WORD else PodcastCategory.MUSIC,
                autoPruneMissingEpisodes = entry.id in cleanup && entry.feedUrl != null,
                legacyModuleId = entry.legacyModuleId,
                addedAtEpochMs = entry.addedAtEpochMs,
            )
        }.toMutableMap()
        rows[TRACKLISTS_ID] = ShowEntity(
            id = TRACKLISTS_ID,
            title = "1001Tracklists",
            platformUrl = TRACKLISTS_URL,
            sourceType = ShowSourceType.PLATFORM_LINK,
            description = "1001Tracklists – Tracklists für DJ-Sets, Radioshows und Mixes.",
            subscribed = true,
            origin = ShowOrigin.BUNDLED,
            standardCatalogVersion = CATALOG_VERSION,
            hideFromLatest = true,
            latestMode = LatestMode.NONE,
            category = PodcastCategory.MUSIC,
            legacyModuleId = TRACKLISTS_LEGACY_MODULE_ID,
        )
        val fixedSet = fixedIds.toSet()
        val collator = Collator.getInstance(Locale.GERMAN).apply { strength = Collator.PRIMARY }
        val alphabetical = Comparator<ShowEntity> { left, right ->
            collator.compare(left.title, right.title).takeIf { it != 0 } ?: left.id.compareTo(right.id)
        }
        val ordered = buildList {
            add(requireNotNull(rows[TRACKLISTS_ID]))
            fixedIds.mapNotNull(rows::get).forEach(::add)
            rows.values.filter {
                it.subscribed && it.id != TRACKLISTS_ID && it.id !in fixedSet
            }.sortedWith(alphabetical).forEach(::add)
            rows.values.filterNot { it.subscribed }.sortedWith(alphabetical).forEach(::add)
        }
        return ordered.mapIndexed { index, show ->
            show.copy(sortOrder = index, standardSortOrder = index)
        }
    }

    private val ADDITIONAL_WORD_PODCAST_IDS = setOf(
        "570b9cd50bc3abb6a90aa9fa73d2cb2389793cf574d9fcac99016d6fc4708adf", // Der Wandel des Markus Lanz
        "bb8fcf0e91e30f12ea8d966e4628856f354e43ba80e82639f8f064ecd10a4ffa", // Hart aber fair
    )
}
