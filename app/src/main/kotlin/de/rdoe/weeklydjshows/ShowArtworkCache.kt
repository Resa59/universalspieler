package de.rdoe.weeklydjshows

import android.content.Context
import android.net.ConnectivityManager
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Keeps the small, reusable show covers ahead of the UI. Episode artwork deliberately does not
 * use this path: only explicit downloads persist episode-specific images.
 */
object ShowArtworkCache {
    private const val MEMORY_WARM_SIZE_PX = 512
    private const val DISK_PREFETCH_SIZE_PX = 160

    /** Bulk-prefetch is deliberately Wi-Fi/unmetered only so 100+ covers cannot surprise mobile data. */
    suspend fun prefetchSubscribed(context: Context) {
        if (!hasUnmeteredNetwork(context)) return
        val urls = AppGraph.database.showDao().getSubscribed().mapNotNull { it.artworkUrl }
        load(context, urls, warmMemory = false, allowNetwork = true)
    }

    /** Warm only the covers just ahead of the current grid position. */
    suspend fun warmMemory(context: Context, urls: List<String>) {
        if (urls.isEmpty()) return
        load(
            context = context,
            urls = urls,
            warmMemory = true,
            // On metered connections, pre-warming may read disk but never fan out network calls.
            allowNetwork = hasUnmeteredNetwork(context),
        )
    }

    /** A newly subscribed/restored show is only one image, so it may be prefetched on any network. */
    suspend fun prefetchShow(context: Context, showId: String) {
        val url = AppGraph.database.showDao().get(showId)?.artworkUrl ?: return
        load(context, listOf(url), warmMemory = true, allowNetwork = true)
    }

    @OptIn(ExperimentalCoilApi::class)
    private suspend fun load(
        context: Context,
        urls: List<String>,
        warmMemory: Boolean,
        allowNetwork: Boolean,
    ) = coroutineScope {
        val loader = context.imageLoader
        val semaphore = Semaphore(if (warmMemory) 4 else 6)
        urls.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .map { url ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        runCatching {
                            loader.execute(
                                ImageRequest.Builder(context)
                                    .data(url)
                                    // Explicit keys let the UI and this preloader share exactly the
                                    // same persistent source entry even when display sizes differ.
                                    .diskCacheKey(url)
                                    .memoryCacheKey(url)
                                    .size(if (warmMemory) MEMORY_WARM_SIZE_PX else DISK_PREFETCH_SIZE_PX)
                                    .memoryCachePolicy(if (warmMemory) CachePolicy.ENABLED else CachePolicy.DISABLED)
                                    .diskCachePolicy(CachePolicy.ENABLED)
                                    .networkCachePolicy(if (allowNetwork) CachePolicy.ENABLED else CachePolicy.DISABLED)
                                    .crossfade(false)
                                    .build(),
                            )
                        }
                    }
                }
            }
            .toList()
            .awaitAll()
    }

    private fun hasUnmeteredNetwork(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return connectivity.activeNetwork != null && !connectivity.isActiveNetworkMetered
    }
}
