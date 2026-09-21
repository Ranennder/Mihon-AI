package eu.kanade.tachiyomi.extension.api

import eu.kanade.tachiyomi.extension.model.Extension
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifySequence
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.extension.interactor.UpdateExtensionStores
import mihon.domain.extension.model.ExtensionStore
import mihon.domain.extension.repository.ExtensionStoreRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExtensionApiTest {

    private val repository = mockk<ExtensionStoreRepository>()
    private val api = ExtensionApi(repository, UpdateExtensionStores(repository))

    @Test
    fun `manual listing waits for a saved legacy endpoint to migrate`() = runBlocking {
        val legacyStore = store("https://legacy.example/repo.json", isLegacy = true)
        val migratedStore = store("https://current.example/index.json").copy(
            extensionListUrl = "https://current.example/extensions.json",
        )
        val available = extension("extension.migrated", 42, migratedStore)
        var savedStore = legacyStore
        val refreshStarted = CompletableDeferred<Unit>()
        val finishRefresh = CompletableDeferred<Unit>()
        coEvery { repository.refreshAll() } coAnswers {
            refreshStarted.complete(Unit)
            finishRefresh.await()
            savedStore = migratedStore
        }
        coEvery { repository.fetchExtensions() } coAnswers {
            if (savedStore.extensionListUrl == migratedStore.extensionListUrl) {
                listOf(available)
            } else {
                emptyList()
            }
        }

        withTimeout(5_000) {
            val result = async { api.findExtensions() }
            try {
                refreshStarted.await()
                coVerify(exactly = 0) { repository.fetchExtensions() }
                finishRefresh.complete(Unit)

                assertEquals(listOf(available), result.await())
                assertEquals(migratedStore, savedStore)
            } finally {
                finishRefresh.complete(Unit)
            }
        }
        coVerifySequence {
            repository.refreshAll()
            repository.fetchExtensions()
        }
    }

    @Test
    fun `listing keeps extensions from an unchanged store`() = runBlocking {
        val currentStore = store("https://current.example/index.json")
        val available = listOf(
            extension("extension.first", 42, currentStore),
            extension("extension.second", 10, currentStore),
        )
        coEvery { repository.refreshAll() } returns Unit
        coEvery { repository.fetchExtensions() } returns available

        assertEquals(available, api.findExtensions())

        coVerifySequence {
            repository.refreshAll()
            repository.fetchExtensions()
        }
    }

    @Test
    fun `refreshed listing selects the newest duplicate regardless of store order`() = runBlocking {
        val latest = extension("extension.shared", 42, store("https://current.example/index.json"))
        val stale = extension("extension.shared", 41, store("https://stale.example/repo.json", isLegacy = true))
        val other = extension("extension.other", 10, latest.store)
        coEvery { repository.refreshAll() } returns Unit

        for (duplicates in listOf(listOf(latest, stale), listOf(stale, latest))) {
            coEvery { repository.fetchExtensions() } returns duplicates + other

            assertEquals(listOf(latest, other), api.findExtensions())
        }

        coVerifySequence {
            repository.refreshAll()
            repository.fetchExtensions()
            repository.refreshAll()
            repository.fetchExtensions()
        }
    }

    private fun store(indexUrl: String, isLegacy: Boolean = false) = ExtensionStore(
        indexUrl = indexUrl,
        name = indexUrl,
        badgeLabel = "test",
        signingKey = "test",
        contact = ExtensionStore.Contact(indexUrl, null),
        isLegacy = isLegacy,
        extensionListUrl = null,
    )

    private fun extension(pkgName: String, versionCode: Long, store: ExtensionStore) = Extension.Available(
        name = pkgName,
        pkgName = pkgName,
        versionName = "1.4.$versionCode",
        versionCode = versionCode,
        libVersion = 1.4,
        lang = "en",
        isNsfw = false,
        sources = emptyList(),
        apkUrl = "https://example.test/apk/$pkgName.apk",
        iconUrl = "https://example.test/icon/$pkgName.png",
        store = store,
    )
}
