package com.jh.proj.coroutineviz

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("ClientAddress")

/** `rateLimit.trustedProxyHops` (env `TRUSTED_PROXY_HOPS`) as [configureTrustedProxy] read it. */
private val TrustedProxyHopsKey = AttributeKey<Int>("TrustedProxyHops")

/** What an address in `X-Forwarded-For` may look like: an IPv4 or IPv6 literal, nothing longer. */
private val IP_LITERAL = Regex("^[0-9a-fA-F:.]{2,45}$")

/**
 * Reads `rateLimit.trustedProxyHops` (env `TRUSTED_PROXY_HOPS`): how many reverse proxies stand
 * between the visitor and this server, each of which appends the address it received the request
 * from to `X-Forwarded-For`. 0, the default, trusts nothing: the header is never read. Anything
 * that is not a whole number of at least 0 counts as 0, with a warning. Returns the value in use.
 */
internal fun Application.configureTrustedProxy(): Int {
    val configured =
        environment.config
            .propertyOrNull("rateLimit.trustedProxyHops")
            ?.getString()
            ?.trim()
    val hops = configured?.toIntOrNull()?.takeIf { it >= 0 }
    if (hops == null && !configured.isNullOrEmpty()) {
        logger.warn("rateLimit.trustedProxyHops (TRUSTED_PROXY_HOPS) must be a whole number of at least 0, got '{}'; using 0", configured)
    }
    attributes.put(TrustedProxyHopsKey, hops ?: 0)
    return hops ?: 0
}

/**
 * The address rate limits count an anonymous caller by: the socket's, unless
 * `rateLimit.trustedProxyHops` says proxies stand in front ([forwardedClientAddress]).
 */
internal fun ApplicationCall.clientAddress(): String {
    val socketAddress = request.local.remoteAddress
    val hops = application.attributes.getOrNull(TrustedProxyHopsKey) ?: 0
    if (hops == 0) return socketAddress
    return forwardedClientAddress(request.headers.getAll(HttpHeaders.XForwardedFor).orEmpty(), hops) ?: socketAddress
}

/**
 * The visitor's address according to `X-Forwarded-For` behind [hops] trusted proxies, or null when
 * the header does not say.
 *
 * Each proxy appends the address it got the request from, so the last [hops] entries were written
 * by the trusted proxies and the first of those is the visitor as the outermost proxy saw them.
 * Everything to the left of it came with the request and can be made up, so it is never read.
 * Repeated headers count as one list. Fewer than [hops] entries, or an entry that is not an IP
 * address, give null: the caller then falls back to the socket address.
 */
internal fun forwardedClientAddress(
    headerValues: List<String>,
    hops: Int,
): String? {
    val entries = headerValues.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
    val entry = if (hops > 0) entries.getOrNull(entries.size - hops) else null
    return entry
        ?.removePrefix("[")
        ?.removeSuffix("]")
        ?.lowercase()
        ?.takeIf { IP_LITERAL.matches(it) }
}
