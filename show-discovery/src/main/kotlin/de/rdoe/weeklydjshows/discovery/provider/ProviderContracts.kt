package de.rdoe.weeklydjshows.discovery.provider

import de.rdoe.weeklydjshows.discovery.model.*
import de.rdoe.weeklydjshows.discovery.network.DiscoveryHttpClient

interface SearchProvider {
    val id: ProviderId
    fun search(request: SearchRequest, context: ProviderContext): ProviderResult
}

interface BrowseProvider {
    val id: ProviderId
    val supportedModes: Set<BrowseMode>
    fun browse(request: BrowseRequest, context: ProviderContext): ProviderResult
}

data class ProviderContext(
    val http: DiscoveryHttpClient,
    val secrets: SecretProvider,
    val userAgent: String,
    val nowEpochMillis: () -> Long = { System.currentTimeMillis() }
)

internal fun providerStatusFromHttp(
    provider: ProviderId,
    statusCode: Int,
    durationMillis: Long,
    message: String? = null,
    retryAfterSeconds: Long? = null
): ProviderStatus {
    val state = when (statusCode) {
        401, 403 -> ProviderState.CREDENTIALS_MISSING
        404 -> ProviderState.UNAVAILABLE
        429 -> ProviderState.RATE_LIMITED
        in 200..299 -> ProviderState.SUCCESS
        in 500..599 -> ProviderState.UNAVAILABLE
        else -> ProviderState.FAILED
    }
    return ProviderStatus(provider, state, message = message, httpStatus = statusCode, retryAfterSeconds = retryAfterSeconds, durationMillis = durationMillis)
}
