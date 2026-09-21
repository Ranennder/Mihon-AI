package eu.kanade.tachiyomi.ui.reader.upscale

import android.app.Application
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.isConnectedToWifi
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class RemoteAiServerDiscovery(
    private val app: Application,
    private val readerPreferences: ReaderPreferences,
    networkHelper: NetworkHelper,
) {

    data class Resolution(
        val baseUrl: String,
        val isAutoDiscovered: Boolean,
    )

    private val client = networkHelper.client.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header(HEADER_TRACE_ID, UUID.randomUUID().toString())
                    .build(),
            )
        }
        .eventListener(AiPerformanceLog.eventListener(app))
        .connectTimeout(DISCOVERY_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .readTimeout(DISCOVERY_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .callTimeout(DISCOVERY_CALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val discoveryLock = Any()
    private val readyInternetCompanions = ConcurrentHashMap.newKeySet<String>()
    private var lastImplicitPairingUrl: String? = null
    private var nextImplicitPairingAttemptAt = 0L

    fun resolveBaseUrl(forceRediscovery: Boolean = false): Resolution? = synchronized(discoveryLock) {
        val internetEnabled = readerPreferences.remoteAiInternetAccess.get()
        val resolution = selectRemoteAiEndpoint(
            manualUrl = manualBaseUrl(),
            cachedUrl = cachedDiscoveredBaseUrl(),
            internetUrl = internetBaseUrl().takeIf { internetEnabled },
            internetEnabled = internetEnabled,
            onWifi = app.isConnectedToWifi(),
            forceRediscovery = forceRediscovery,
            probe = ::probeCandidate,
            discover = ::discoverOnLocalNetwork,
        )
        if (resolution == null) {
            readerPreferences.remoteAiDiscoveredBaseUrl.delete()
        } else if (resolution.baseUrl != internetBaseUrl()) {
            if (resolution.isAutoDiscovered) {
                readerPreferences.remoteAiDiscoveredBaseUrl.set(resolution.baseUrl)
            }
            if (internetEnabled && app.isConnectedToWifi()) {
                pairInternetAccessIfNeeded(resolution.baseUrl)
            }
        }
        resolution
    }

    fun pairInternetAccess(): String {
        check(app.isConnectedToWifi()) { "Connect the phone and PC to the same Wi-Fi to pair internet access" }
        synchronized(discoveryLock) {
            val localUrl = manualBaseUrl()?.takeIf(::isLocalRemoteAiEndpoint)?.let(::probeCandidate)
                ?: cachedDiscoveredBaseUrl()?.takeIf(::isLocalRemoteAiEndpoint)?.let(::probeCandidate)
                ?: discoverOnLocalNetwork()
                ?: error("Remote AI companion was not found on local Wi-Fi")
            readerPreferences.remoteAiDiscoveredBaseUrl.set(localUrl)
            return enableInternetAccess(localUrl)
        }
    }

    fun clearCachedBaseUrl() {
        readerPreferences.remoteAiDiscoveredBaseUrl.delete()
    }

    private fun pairInternetAccessIfNeeded(baseUrl: String) {
        if (!isLocalRemoteAiEndpoint(baseUrl) || baseUrl in readyInternetCompanions) return
        if (baseUrl == lastImplicitPairingUrl && System.nanoTime() < nextImplicitPairingAttemptAt) return
        lastImplicitPairingUrl = baseUrl
        runCatching { enableInternetAccess(baseUrl) }
            .onFailure {
                nextImplicitPairingAttemptAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                logcat(LogPriority.WARN, it) { "Failed to pair companion internet access" }
            }
    }

    private fun manualBaseUrl(): String? {
        return readerPreferences.remoteAiBaseUrl.get()
            .trim()
            .trimEnd('/')
            .takeIf { it.isNotEmpty() }
    }

    private fun cachedDiscoveredBaseUrl(): String? {
        return readerPreferences.remoteAiDiscoveredBaseUrl.get()
            .trim()
            .trimEnd('/')
            .takeIf { it.isNotEmpty() }
    }

    private fun internetBaseUrl(): String? {
        return readerPreferences.remoteAiInternetBaseUrl.get().trim().trimEnd('/')
            .takeIf { it.toHttpUrlOrNull()?.isHttps == true }
    }

    private fun discoverOnLocalNetwork(): String? {
        if (!app.isConnectedToWifi()) {
            return null
        }

        val candidateBaseUrls = candidateBaseUrls()
        if (candidateBaseUrls.isEmpty()) {
            return null
        }

        val executor = Executors.newFixedThreadPool(minOf(DISCOVERY_PARALLELISM, candidateBaseUrls.size))
        return try {
            candidateBaseUrls.chunked(DISCOVERY_PARALLELISM).forEach { batch ->
                val tasks = batch.map { candidate ->
                    Callable { probeCandidate(candidate) }
                }
                val results = executor.invokeAll(tasks)
                results.forEach { future ->
                    val discovered = runCatching { future.get() }.getOrNull()
                    if (discovered != null) {
                        logcat { "Auto-discovered Mihon AI companion at $discovered" }
                        return discovered
                    }
                }
            }
            null
        } finally {
            executor.shutdownNow()
        }
    }

    private fun candidateBaseUrls(): List<String> {
        val localIpv4s = (NetworkInterface.getNetworkInterfaces()?.let(Collections::list) ?: emptyList())
            .asSequence()
            .filter {
                runCatching { it.isUp && !it.isLoopback && !it.isVirtual }
                    .getOrDefault(false)
            }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { address ->
                address.hostAddress
                    ?.takeIf { address.isSiteLocalAddress && !address.isLoopbackAddress }
            }
            .distinct()
            .toList()

        val localSet = localIpv4s.toHashSet()
        return buildList {
            localIpv4s.forEach { localIp ->
                val subnetPrefix = localIp.substringBeforeLast('.', missingDelimiterValue = "")
                if (subnetPrefix.isBlank()) {
                    return@forEach
                }

                for (host in 1..254) {
                    val candidateHost = "$subnetPrefix.$host"
                    if (candidateHost in localSet) {
                        continue
                    }
                    add("http://$candidateHost:$DEFAULT_DISCOVERY_PORT")
                }
            }
        }
            .distinct()
    }

    private fun probeCandidate(baseUrl: String): String? {
        return runCatching {
            val request = Request.Builder()
                .url("$baseUrl/health")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use null
                }

                val responseBody = response.body.string()
                if (responseBody.isBlank()) {
                    return@use null
                }

                val payload = JSONObject(responseBody)
                if (!payload.optBoolean("ok")) {
                    return@use null
                }

                val internetUrl = payload.optString("internet_url").trim()
                    .takeIf { it.toHttpUrlOrNull()?.isHttps == true }
                internetUrl?.let {
                    readerPreferences.remoteAiInternetBaseUrl.set(it.trimEnd('/'))
                }
                val token = payload.optString("pairing_token").trim()
                    .takeIf { !payload.isNull("pairing_token") && it.isNotEmpty() }
                token?.let { readerPreferences.remoteAiToken.set(it) }
                if (internetUrl != null && token != null) {
                    readyInternetCompanions.add(baseUrl)
                } else {
                    readyInternetCompanions.remove(baseUrl)
                }
                baseUrl
            }
        }
            .onFailure {
                logcat(LogPriority.DEBUG, it) { "Remote AI discovery probe failed for $baseUrl" }
            }
            .getOrNull()
    }

    private fun enableInternetAccess(baseUrl: String): String {
        val request = Request.Builder()
            .url("$baseUrl/api/enable-internet")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(INTERNET_PAIRING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(INTERNET_PAIRING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                check(response.isSuccessful) {
                    response.body.string().take(500).ifBlank {
                        "Internet pairing returned HTTP ${response.code}"
                    }
                }
                val payload = JSONObject(response.body.string())
                val url = payload.optString("internet_url").trim().trimEnd('/')
                val token = payload.optString("pairing_token").trim()
                check(
                    url.toHttpUrlOrNull()?.isHttps == true && !payload.isNull("pairing_token") && token.isNotBlank(),
                ) {
                    "Incomplete internet pairing response"
                }
                readerPreferences.remoteAiInternetBaseUrl.set(url)
                readerPreferences.remoteAiToken.set(token)
                readyInternetCompanions.add(baseUrl)
                url
            }
    }

    private companion object {
        private const val DEFAULT_DISCOVERY_PORT = 8765
        private const val DISCOVERY_PARALLELISM = 24
        private const val DISCOVERY_CONNECT_TIMEOUT_MILLIS = 250L
        private const val DISCOVERY_READ_TIMEOUT_MILLIS = 250L
        private const val DISCOVERY_CALL_TIMEOUT_MILLIS = 400L
        private const val INTERNET_PAIRING_TIMEOUT_SECONDS = 60L
        private const val HEADER_TRACE_ID = "X-Reader-AI-Trace-Id"
    }
}
