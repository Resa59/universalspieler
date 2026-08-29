package de.rdoe.weeklydjshows.discovery.provider

import de.rdoe.weeklydjshows.discovery.internal.*
import de.rdoe.weeklydjshows.discovery.model.*
import de.rdoe.weeklydjshows.discovery.network.HttpResponse

internal fun parseJsonObject(response: HttpResponse): Map<String, JsonValue> {
    return Json.parse(response.text()).asObject()
        ?: throw JsonParseException("Expected JSON object")
}

internal fun parseJsonArray(response: HttpResponse): List<JsonValue> {
    return Json.parse(response.text()).asArray()
        ?: throw JsonParseException("Expected JSON array")
}

internal fun categoriesFrom(value: JsonValue?): Set<String> {
    if (value == null) return emptySet()
    value.asArray()?.let { return it.mapNotNull { item -> item.asString() }.filter { it.isNotBlank() }.toSet() }
    value.asObject()?.let { obj ->
        return obj.mapNotNull { (key, v) ->
            v.asString()?.takeIf { it.isNotBlank() } ?: key.takeIf { it.isNotBlank() }
        }.toSet()
    }
    return value.asString()?.let { setOf(it) }.orEmpty()
}

internal fun artworkFrom(vararg urls: String?): String? = urls.firstOrNull { !it.isNullOrBlank() }

internal fun statusForException(provider: ProviderId, start: Long, error: Throwable): ProviderStatus {
    val state = when (error) {
        is java.net.SocketTimeoutException -> ProviderState.TIMEOUT
        is java.net.UnknownHostException, is java.net.ConnectException -> ProviderState.UNAVAILABLE
        is JsonParseException -> ProviderState.INVALID_RESPONSE
        else -> ProviderState.FAILED
    }
    return ProviderStatus(
        provider = provider,
        state = state,
        message = error.message ?: error.javaClass.simpleName,
        durationMillis = System.currentTimeMillis() - start
    )
}
