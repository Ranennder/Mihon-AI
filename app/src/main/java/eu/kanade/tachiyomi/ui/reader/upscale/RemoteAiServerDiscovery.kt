package eu.kanade.tachiyomi.ui.reader.upscale

import android.app.Application
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.isConnectedToWifi
import logcat.LogPriority
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Callable
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

    fun resolveBaseUrl(forceRediscovery: Boolean = false): Resolution? {
        if (readerPreferences.remoteAiInternetAccess.get() && (!app.isConnectedToWifi() || forceRediscovery)) {
            internetBaseUrl()?.let { return Resolution(it, isAutoDiscovered = true) }
        }

        manualBaseUrl()?.let {
            if (app.isConnectedToWifi() && readerPreferences.remoteAiInternetAccess.get()) {
                probeCandidate(it)
            }
            return Resolution(it, isAutoDiscovered = false)
        }

        if (!forceRediscovery) {
            cachedDiscoveredBaseUrl()?.let {
                probeCandidate(it)
                return Resolution(it, isAutoDiscovered = true)
            }
        }

        return synchronized(discoveryLock) {
            manualBaseUrl()?.let {
                if (readerPreferences.remoteAiInternetAccess.get()) {
                    probeCandidate(it)
                }
                return@synchronized Resolution(it, isAutoDiscovered = false)
            }

            if (!forceRediscovery) {
                cachedDiscoveredBaseUrl()?.let {
                    probeCandidate(it)
                    return@synchronized Resolution(it, isAutoDiscovered = true)
                }
            }

            val discoveredBaseUrl = discoverOnLocalNetwork()
            if (discoveredBaseUrl != null) {
                readerPreferences.remoteAiDiscoveredBaseUrl.set(discoveredBaseUrl)
                Resolution(discoveredBaseUrl, isAutoDiscovered = true)
            } else {
                readerPreferences.remoteAiDiscoveredBaseUrl.delete()
                internetBaseUrl()?.let { Resolution(it, isAutoDiscovered = true) }
            }
        }
    }

    fun clearCachedBaseUrl() {
        readerPreferences.remoteAiDiscoveredBaseUrl.delete()
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
        return readerPreferences.remoteAiInternetBaseUrl.get().trim().trimEnd('/').takeIf(String::isNotEmpty)
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
        val request = Request.Builder()
            .url("$baseUrl/health")
            .get()
            .build()

        return runCatching {
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

                payload.optString("internet_url").trim().takeIf(String::isNotEmpty)?.let {
                    readerPreferences.remoteAiInternetBaseUrl.set(it.trimEnd('/'))
                }
                payload.optString("pairing_token").trim().takeIf(String::isNotEmpty)?.let {
                    readerPreferences.remoteAiToken.set(it)
                }
                if (readerPreferences.remoteAiInternetAccess.get()) {
                    enableInternetAccess(baseUrl)
                }

                baseUrl
            }
        }
            .onFailure {
                logcat(LogPriority.DEBUG, it) { "Remote AI discovery probe failed for $baseUrl" }
            }
            .getOrNull()
    }

    private fun enableInternetAccess(baseUrl: String) {
        val request = Request.Builder()
            .url("$baseUrl/api/enable-internet")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        runCatching {
            client.newBuilder()
                .readTimeout(INTERNET_PAIRING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(INTERNET_PAIRING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()
                .use { response ->
                    if (!response.isSuccessful) return@use
                    val payload = JSONObject(response.body.string())
                    payload.optString("internet_url").trim().takeIf(String::isNotEmpty)?.let {
                        readerPreferences.remoteAiInternetBaseUrl.set(it.trimEnd('/'))
                    }
                    payload.optString("pairing_token").trim().takeIf(String::isNotEmpty)?.let {
                        readerPreferences.remoteAiToken.set(it)
                    }
                }
        }.onFailure {
            logcat(LogPriority.DEBUG, it) { "Failed to pair with companion internet tunnel" }
        }
    }

    private companion object {
        private const val DEFAULT_DISCOVERY_PORT = 8765
        private const val DISCOVERY_PARALLELISM = 24
        private const val DISCOVERY_CONNECT_TIMEOUT_MILLIS = 250L
        private const val DISCOVERY_READ_TIMEOUT_MILLIS = 250L
        private const val DISCOVERY_CALL_TIMEOUT_MILLIS = 400L
        private const val INTERNET_PAIRING_TIMEOUT_SECONDS = 20L
        private const val HEADER_TRACE_ID = "X-Reader-AI-Trace-Id"
    }
}
