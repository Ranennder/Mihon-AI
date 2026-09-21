package eu.kanade.tachiyomi.ui.reader.upscale

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A saved LAN address is a hint: it must still be reachable on the current network. */
internal fun selectRemoteAiEndpoint(
    manualUrl: String?,
    cachedUrl: String?,
    internetUrl: String?,
    internetEnabled: Boolean,
    onWifi: Boolean,
    forceRediscovery: Boolean,
    probe: (String) -> String?,
    discover: () -> String?,
): RemoteAiServerDiscovery.Resolution? {
    // A public manual endpoint may need DNS/TLS time beyond the short LAN probe.
    if (manualUrl != null && !isLocalRemoteAiEndpoint(manualUrl)) {
        return RemoteAiServerDiscovery.Resolution(manualUrl, isAutoDiscovered = false)
    }
    val internet = internetUrl.takeIf { internetEnabled }
    if (internet != null && (!onWifi || forceRediscovery)) {
        return RemoteAiServerDiscovery.Resolution(internet, isAutoDiscovered = true)
    }
    if (manualUrl != null) {
        if (!internetEnabled || !onWifi || probe(manualUrl) != null) {
            return RemoteAiServerDiscovery.Resolution(manualUrl, isAutoDiscovered = false)
        }
    }
    if (onWifi) {
        if (!forceRediscovery && cachedUrl != null && cachedUrl != manualUrl) {
            probe(cachedUrl)?.let { return RemoteAiServerDiscovery.Resolution(it, isAutoDiscovered = true) }
        }
        discover()?.let { return RemoteAiServerDiscovery.Resolution(it, isAutoDiscovered = true) }
    }
    return internet?.let { RemoteAiServerDiscovery.Resolution(it, isAutoDiscovered = true) }
}

/** Classify LAN addresses without performing DNS or blocking endpoint selection. */
internal fun isLocalRemoteAiEndpoint(url: String): Boolean {
    val host = url.toHttpUrlOrNull()?.host ?: return false
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
        host.endsWith(".lan") || host.endsWith(".home") || host.endsWith(".internal")
    ) {
        return true
    }
    if (':' in host) {
        return host == "::1" || host.startsWith("fc") || host.startsWith("fd") ||
            listOf("fe8", "fe9", "fea", "feb").any(host::startsWith)
    }
    if ('.' !in host) return true
    val octets = host.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return octets[0] == 10 || octets[0] == 127 ||
        (octets[0] == 172 && octets[1] in 16..31) ||
        (octets[0] == 192 && octets[1] == 168) ||
        (octets[0] == 169 && octets[1] == 254)
}
