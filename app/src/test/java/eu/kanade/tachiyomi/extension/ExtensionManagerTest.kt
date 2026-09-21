package eu.kanade.tachiyomi.extension

import android.content.Context
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.extension.api.ExtensionApi
import eu.kanade.tachiyomi.extension.api.ExtensionUpdateNotifier
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mihon.domain.extension.model.ExtensionStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

class ExtensionManagerTest {
    private val originalInjekt = Injekt
    private val context = mockk<Context>()
    private val preferences = mockk<SourcePreferences>(relaxed = true)
    private val api = mockk<ExtensionApi>()
    private val installer = mockk<ExtensionInstaller>()
    private lateinit var manager: ExtensionManager

    private val installed = Extension.Installed(
        name = "AllHentai",
        pkgName = "eu.kanade.tachiyomi.extension.ru.allhentai",
        versionName = "1.4.65",
        versionCode = 65,
        libVersion = 1.4,
        lang = "ru",
        isNsfw = true,
        pkgFactory = null,
        sources = emptyList(),
        icon = null,
        isShared = true,
    )
    private val available = Extension.Available(
        name = installed.name,
        pkgName = installed.pkgName,
        versionName = "1.6.68",
        versionCode = 106_068,
        libVersion = 1.6,
        lang = "ru",
        isNsfw = true,
        sources = emptyList(),
        apkUrl = "https://example.test/allhentai.apk",
        iconUrl = "https://example.test/allhentai.png",
        store = ExtensionStore(
            indexUrl = "https://example.test/index.pb",
            name = "Store",
            badgeLabel = "STORE",
            signingKey = "key",
            contact = ExtensionStore.Contact("https://example.test", null),
            isLegacy = false,
            extensionListUrl = null,
        ),
    )
    private val updated = installed.copy(
        versionName = available.versionName,
        versionCode = available.versionCode,
        libVersion = available.libVersion,
    )

    @BeforeEach
    fun setUp() {
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(mockk<SecurityPreferences>())
        mockkConstructor(ExtensionUpdateNotifier::class)
        every { anyConstructed<ExtensionUpdateNotifier>().dismiss() } returns Unit
        mockkObject(ExtensionLoader)
        every { ExtensionLoader.loadExtensions(context) } returns listOf(LoadResult.Success(installed))
        coEvery { ExtensionLoader.loadExtensionFromPkgName(context, installed.pkgName) } returns
            LoadResult.Success(installed)
        every { preferences.enabledLanguages.isSet() } returns true
        coEvery { api.findExtensions() } returns listOf(available)
        every { installer.downloadAndInstall(available.apkUrl, available, false, true) } returns
            flowOf(InstallStep.Installing, InstallStep.Installed)
        manager = ExtensionManager(context, preferences, mockk<TrustExtension>(), api, { installer }, {})
    }

    @AfterEach
    fun tearDown() {
        if (::manager.isInitialized) manager.scope.cancel()
        Injekt = originalInjekt
        unmockkAll()
    }

    @Test
    fun `successful installer result with the old installed version reports an error`() = runBlocking {
        manager.findAvailableExtensions()

        val result = manager.updateExtension(installed).first { it.isCompleted() }

        assertEquals(InstallStep.Error, result)
        val current = withTimeout(5_000) {
            manager.installedExtensionsFlow.first { it.singleOrNull()?.hasUpdate == true }.single()
        }
        assertEquals(65L, current.versionCode)
        verify { installer.downloadAndInstall(available.apkUrl, available, false, true) }
    }

    @Test
    fun `an updated APK that fails to load reports an error`() = runBlocking {
        manager.findAvailableExtensions()
        coEvery { ExtensionLoader.loadExtensionFromPkgName(context, installed.pkgName) } returns LoadResult.Error

        assertEquals(InstallStep.Error, manager.updateExtension(installed).first { it.isCompleted() })
    }

    @Test
    fun `the requested version is registered before success is emitted`() = runBlocking {
        manager.findAvailableExtensions()
        coEvery { ExtensionLoader.loadExtensionFromPkgName(context, installed.pkgName) } returns
            LoadResult.Success(updated)

        assertEquals(InstallStep.Installed, manager.updateExtension(installed).first { it.isCompleted() })
        verify { preferences.extensionUpdatesCount.set(0) }
        val current = withTimeout(5_000) {
            manager.installedExtensionsFlow.first { it.singleOrNull()?.versionCode == available.versionCode }.single()
        }
        assertFalse(current.hasUpdate)
    }

    @Test
    fun `an untrusted new version replaces the old installed entry before success`() = runBlocking {
        manager.findAvailableExtensions()
        withTimeout(5_000) { manager.installedExtensionsFlow.first { it.isNotEmpty() } }
        val untrusted = Extension.Untrusted(
            updated.name,
            updated.pkgName,
            updated.versionName,
            updated.versionCode,
            updated.libVersion,
            "new-signature",
        )
        coEvery { ExtensionLoader.loadExtensionFromPkgName(context, installed.pkgName) } returns
            LoadResult.Untrusted(untrusted)

        assertEquals(InstallStep.Installed, manager.updateExtension(installed).first { it.isCompleted() })
        withTimeout(5_000) {
            assertTrue(manager.installedExtensionsFlow.first { it.isEmpty() }.isEmpty())
            assertEquals(untrusted, manager.untrustedExtensionsFlow.first { it.isNotEmpty() }.single())
        }
    }

    @Test
    fun `refresh discovers an update installed without a package broadcast`() = runBlocking {
        manager.findAvailableExtensions()
        coEvery { ExtensionLoader.loadExtensionFromPkgName(context, installed.pkgName) } returns
            LoadResult.Success(updated)

        manager.findAvailableExtensions()

        val current = withTimeout(5_000) {
            manager.installedExtensionsFlow.first { it.singleOrNull()?.versionCode == available.versionCode }.single()
        }
        assertFalse(current.hasUpdate)
        verify { preferences.extensionUpdatesCount.set(0) }
    }
}
