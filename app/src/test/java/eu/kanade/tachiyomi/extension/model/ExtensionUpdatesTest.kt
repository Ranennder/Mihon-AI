package eu.kanade.tachiyomi.extension.model

import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExtensionUpdatesTest {

    @Test
    fun `duplicate stores use the newest APK regardless of store order`() {
        val latest = extension(versionCode = 42, storeUrl = "https://latest.example")
        val stale = extension(versionCode = 41, storeUrl = "https://stale.example")

        assertEquals(listOf(latest), listOf(latest, stale).withLatestVersions())
        assertEquals(listOf(latest), listOf(stale, latest).withLatestVersions())
    }

    @Test
    fun `packages without duplicates are retained`() {
        val first = extension(pkgName = "extension.first", versionCode = 42)
        val second = extension(pkgName = "extension.second", versionCode = 10)

        assertEquals(listOf(first, second), listOf(first, second).withLatestVersions())
    }

    @Test
    fun `higher Android version code wins over library version to avoid a downgrade`() {
        val latest = extension(versionCode = 42, libVersion = 1.4)
        val downgrade = extension(versionCode = 41, libVersion = 1.6)

        assertEquals(listOf(latest), listOf(downgrade, latest).withLatestVersions())
    }

    private fun extension(
        pkgName: String = "extension.test",
        versionCode: Long,
        libVersion: Double = 1.4,
        storeUrl: String = "https://store.example",
    ) = Extension.Available(
        name = pkgName,
        pkgName = pkgName,
        versionName = "$libVersion.$versionCode",
        versionCode = versionCode,
        libVersion = libVersion,
        lang = "en",
        isNsfw = false,
        sources = emptyList(),
        apkUrl = "$storeUrl/apk/$pkgName.apk",
        iconUrl = "$storeUrl/icon/$pkgName.png",
        store = ExtensionStore(
            indexUrl = "$storeUrl/index.json",
            name = storeUrl,
            badgeLabel = storeUrl,
            signingKey = "test",
            contact = ExtensionStore.Contact(storeUrl, null),
            isLegacy = false,
            extensionListUrl = null,
        ),
    )
}
