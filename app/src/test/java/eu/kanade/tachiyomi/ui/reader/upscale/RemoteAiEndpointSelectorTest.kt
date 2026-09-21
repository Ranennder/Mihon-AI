package eu.kanade.tachiyomi.ui.reader.upscale

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RemoteAiEndpointSelectorTest {
    private val local = "http://192.168.1.2:8765"
    private val internet = "https://companion.example"

    @Test
    fun `unreachable saved LAN address falls back on another wifi`() {
        assertEquals(internet, resolve(cached = local).also { assertEquals(true, it?.isAutoDiscovered) }?.baseUrl)
    }

    @Test
    fun `unreachable manual LAN address also falls back`() {
        assertEquals(internet, resolve(manual = local)?.baseUrl)
    }

    @Test
    fun `reachable LAN wins over internet`() {
        assertEquals(local, resolve(cached = local, probe = { it })?.baseUrl)
    }

    @Test
    fun `mobile data selects internet without scanning local addresses`() {
        assertEquals(internet, resolve(manual = local, wifi = false, probe = { error("Must not probe") })?.baseUrl)
    }

    @Test
    fun `disabled internet never uses a previously saved tunnel`() {
        assertNull(resolve(enabled = false))
    }

    @Test
    fun `rediscovery bypasses stale cache after network failure`() {
        assertEquals(internet, resolve(cached = local, force = true, probe = { error("Must not probe") })?.baseUrl)
    }

    @Test
    fun `new LAN address can be discovered before first pairing`() {
        assertEquals(local, resolve(savedInternet = null, discover = { local })?.baseUrl)
    }

    @Test
    fun `manual endpoint works without enabling internet pairing`() {
        assertEquals(local, resolve(manual = local, enabled = false, probe = { error("Must not probe") })?.baseUrl)
    }

    @Test
    fun `manual public endpoint bypasses short LAN probes on wifi`() {
        val manual = "https://my-companion.example:8443"
        val result = resolve(manual = manual, probe = { error("Must not probe public endpoint") })
        assertEquals(manual, result?.baseUrl)
        assertEquals(false, result?.isAutoDiscovered)
    }

    @Test
    fun `manual public endpoint stays authoritative on mobile data`() {
        val manual = "https://my-companion.example"
        assertEquals(manual, resolve(manual = manual, wifi = false)?.baseUrl)
    }

    @Test
    fun `manual public IP endpoints are not treated as local`() {
        listOf("http://203.0.113.2:8765", "https://[2001:db8::2]").forEach { manual ->
            assertEquals(manual, resolve(manual = manual, probe = { error("Must not probe public IP") })?.baseUrl)
        }
    }

    @Test
    fun `unreachable private IP and local hostnames fall back to tunnel`() {
        listOf(
            "http://10.0.0.2:8765",
            "http://172.16.0.2:8765",
            "http://169.254.1.2:8765",
            "http://companion.local:8765",
            "http://desktop:8765",
            "http://[fd00::2]:8765",
            "http://[fe80::2]:8765",
        ).forEach { manual ->
            assertEquals(internet, resolve(manual = manual)?.baseUrl)
        }
    }

    private fun resolve(
        manual: String? = null,
        cached: String? = null,
        savedInternet: String? = internet,
        enabled: Boolean = true,
        wifi: Boolean = true,
        force: Boolean = false,
        probe: (String) -> String? = { null },
        discover: () -> String? = { null },
    ) = selectRemoteAiEndpoint(manual, cached, savedInternet, enabled, wifi, force, probe, discover)
}
