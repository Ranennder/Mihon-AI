package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.base.ExtensionInstallerPreference
import eu.kanade.tachiyomi.extension.installer.Installer
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ExtensionInstallerTest {

    @TempDir
    lateinit var cacheDir: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context = mockk<Context>()
    private val preference = mockk<ExtensionInstallerPreference>()
    private val extension = mockk<Extension.Available>()

    @BeforeEach
    fun setUp() {
        every { context.cacheDir } returns cacheDir
        every { preference.get() } returns BasePreferences.ExtensionInstaller.PRIVATE
        every { extension.pkgName } returns "extension.test"
        mockkObject(Installer.Companion)
        every { Installer.cancelInstallQueue(any(), any()) } returns Unit
        mockkObject(ExtensionLoader)
        every { ExtensionLoader.installPrivateExtensionFile(any(), any()) } returns true
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        unmockkAll()
    }

    @Test
    fun `a fresh update does not cancel another installer session`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("apk".toResponseBody())
                    .build()
            }
            .build()
        val installer = ExtensionInstaller(context, scope, client, preference)

        val result = withTimeout(5_000) {
            installer.downloadAndInstall("https://example.test/extension.apk", extension)
                .first { it.isCompleted() }
        }

        assertEquals(InstallStep.Installed, result)
        verify(exactly = 0) { Installer.cancelInstallQueue(any(), any()) }
        verify(exactly = 1) { ExtensionLoader.installPrivateExtensionFile(context, any()) }
    }

    @Test
    fun `completion of a cancelled attempt cannot detach its replacement`() = runBlocking {
        val started = List(2) { CompletableDeferred<Unit>() }
        val release = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val cancelledIds = mutableListOf<Long>()
        every { Installer.cancelInstallQueue(context, capture(cancelledIds)) } returns Unit
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val attempt = chain.request().url.queryParameter("attempt")!!.toInt()
                started[attempt].complete(Unit)
                try {
                    check(release.await(5, TimeUnit.SECONDS))
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("apk".toResponseBody())
                        .build()
                } finally {
                    finished.countDown()
                }
            }
            .build()
        val installer = ExtensionInstaller(context, scope, client, preference)

        try {
            withTimeout(5_000) {
                val first = async {
                    installer.downloadAndInstall("https://example.test/extension.apk?attempt=0", extension)
                        .first { it.isCompleted() }
                }
                started[0].await()
                val replacement = async {
                    installer.downloadAndInstall("https://example.test/extension.apk?attempt=1", extension)
                        .first { it.isCompleted() }
                }
                started[1].await()
                assertEquals(InstallStep.Idle, first.await())

                installer.cancelInstall(extension.pkgName)

                assertEquals(InstallStep.Idle, replacement.await())
            }

            assertEquals(2, cancelledIds.size)
            assertEquals(2, cancelledIds.toSet().size)
            assertTrue(cancelledIds.all { it > 0 })
            verify(exactly = 0) { ExtensionLoader.installPrivateExtensionFile(any(), any()) }
        } finally {
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `legacy update all waits for each Android installer result`() = runBlocking {
        every { preference.get() } returns BasePreferences.ExtensionInstaller.LEGACY
        val launches = Channel<Long>(Channel.UNLIMITED)
        val installer = ExtensionInstaller(context, scope, successfulDownloadClient(), preference) { id, _ ->
            check(launches.trySend(id).isSuccess)
        }
        val otherExtension = mockk<Extension.Available>()
        every { otherExtension.pkgName } returns "extension.other"

        withTimeout(5_000) {
            val first = async {
                installer.downloadAndInstall("https://example.test/first.apk", extension)
                    .first { it.isCompleted() }
            }
            val firstId = launches.receive()
            val second = async {
                installer.downloadAndInstall("https://example.test/second.apk", otherExtension)
                    .first { it.isCompleted() }
            }

            assertNull(withTimeoutOrNull(200) { launches.receive() })
            installer.updateInstallStep(firstId, InstallStep.Installed)
            assertEquals(InstallStep.Installed, first.await())

            val secondId = launches.receive()
            installer.updateInstallStep(secondId, InstallStep.Installed)
            assertEquals(InstallStep.Installed, second.await())
        }
    }

    @Test
    fun `cancelling a legacy install releases the next confirmation`() = runBlocking {
        every { preference.get() } returns BasePreferences.ExtensionInstaller.LEGACY
        val launches = Channel<Long>(Channel.UNLIMITED)
        val installer = ExtensionInstaller(context, scope, successfulDownloadClient(), preference) { id, _ ->
            check(launches.trySend(id).isSuccess)
        }
        val otherExtension = mockk<Extension.Available>()
        every { otherExtension.pkgName } returns "extension.other"

        withTimeout(5_000) {
            val first = async {
                installer.downloadAndInstall("https://example.test/first.apk", extension)
                    .first { it.isCompleted() }
            }
            launches.receive()
            val second = async {
                installer.downloadAndInstall("https://example.test/second.apk", otherExtension)
                    .first { it.isCompleted() }
            }

            installer.cancelInstall(extension.pkgName)
            assertEquals(InstallStep.Idle, first.await())
            val secondId = launches.receive()
            installer.updateInstallStep(secondId, InstallStep.Installed)
            assertEquals(InstallStep.Installed, second.await())
        }
    }

    @Test
    fun `an APK older than the requested update is never installed`() = runBlocking {
        val packageManager = mockk<PackageManager>()
        val downloaded = mockk<PackageInfo>().apply {
            packageName = "extension.test"
            versionCode = 65
        }
        every { context.packageManager } returns packageManager
        every { packageManager.getPackageArchiveInfo(any(), 0) } returns downloaded
        every { extension.versionCode } returns 106_068
        val installer = ExtensionInstaller(context, scope, successfulDownloadClient(), preference)

        val result = withTimeout(5_000) {
            installer.downloadAndInstall(
                "https://example.test/extension.apk",
                extension,
                verifyDownloadedUpdate = true,
            ).first { it.isCompleted() }
        }

        assertEquals(InstallStep.Error, result)
        verify(exactly = 0) { ExtensionLoader.installPrivateExtensionFile(any(), any()) }
    }

    @Test
    fun `an APK for a different package is never installed`() = runBlocking {
        val packageManager = mockk<PackageManager>()
        val downloaded = mockk<PackageInfo>().apply { packageName = "extension.other" }
        every { context.packageManager } returns packageManager
        every { packageManager.getPackageArchiveInfo(any(), 0) } returns downloaded
        val installer = ExtensionInstaller(context, scope, successfulDownloadClient(), preference)

        val result = withTimeout(5_000) {
            installer.downloadAndInstall(
                "https://example.test/extension.apk",
                extension,
                verifyDownloadedUpdate = true,
            ).first { it.isCompleted() }
        }

        assertEquals(InstallStep.Error, result)
        verify(exactly = 0) { ExtensionLoader.installPrivateExtensionFile(any(), any()) }
    }

    @Test
    fun `an APK matching the requested update is installed`() = runBlocking {
        val packageManager = mockk<PackageManager>()
        val downloaded = mockk<PackageInfo>().apply {
            packageName = "extension.test"
            versionCode = 106_068
        }
        every { context.packageManager } returns packageManager
        every { packageManager.getPackageArchiveInfo(any(), 0) } returns downloaded
        every { extension.versionCode } returns 106_068
        val installer = ExtensionInstaller(context, scope, successfulDownloadClient(), preference)

        val result = withTimeout(5_000) {
            installer.downloadAndInstall(
                "https://example.test/extension.apk",
                extension,
                verifyDownloadedUpdate = true,
            ).first { it.isCompleted() }
        }

        assertEquals(InstallStep.Installed, result)
        verify(exactly = 1) { ExtensionLoader.installPrivateExtensionFile(context, any()) }
    }

    private fun successfulDownloadClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("apk".toResponseBody())
                .build()
        }
        .build()
}
