package de.rdoe.weeklydjshows.resolver

import de.rdoe.weeklydjshows.model.*
import java.util.concurrent.ConcurrentHashMap

interface StreamResolver {
    fun supports(request: PlaybackRequest): Boolean
    suspend fun resolve(request: PlaybackRequest, forceRefresh: Boolean = false): ResolveResult
    suspend fun invalidate(originalUrl: String) = Unit
}

class CompositeStreamResolver(
    private val resolvers: List<StreamResolver>,
) : StreamResolver {
    private val cache = ConcurrentHashMap<String, ResolvedMediaSource>()

    override fun supports(request: PlaybackRequest): Boolean = resolvers.any { it.supports(request) }

    override suspend fun resolve(request: PlaybackRequest, forceRefresh: Boolean): ResolveResult {
        val baseKey = request.localFilePath ?: request.enclosureUrl ?: request.originalPageUrl.orEmpty()
        val key = "$baseKey\u0000quality=${request.preferredQuality?.name ?: "AUTO"}\u0000progressive=${request.requireProgressiveHttp}"
        if (!forceRefresh) {
            cache[key]?.takeIf { source ->
                source.validUntilEpochMs?.let { it > System.currentTimeMillis() + 90_000 } ?: true
            }?.let { return ResolveResult.Success(it) }
        }

        val resolver = resolvers.firstOrNull { it.supports(request) }
            ?: return ResolveResult.Failure(
                ResolverError(
                    ResolverErrorType.UNSUPPORTED_URL,
                    "Diese Quelle wird noch nicht unterstützt.",
                    request.originalPageUrl,
                ),
            )

        return resolver.resolve(request, forceRefresh).also { result ->
            if (result is ResolveResult.Success) cache[key] = result.source
        }
    }

    override suspend fun invalidate(originalUrl: String) {
        cache.keys.removeAll { it.substringBefore('\u0000') == originalUrl }
        resolvers.forEach { it.invalidate(originalUrl) }
    }
}
