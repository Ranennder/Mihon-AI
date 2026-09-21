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
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ExtensionReinstallFlowTest {

    @TempDir
    lateinit var directory: File

    private val context = mockk<Context>()
    private val preference = mockk<ExtensionInstallerPreference>()
    private val packageManager = mockk<PackageManager>()
    private val extension = mockk<Extension.Available>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val launches = Channel<Pair<Long, File>>(Channel.UNLIMITED)
    private val validated = AtomicBoolean()
    private val restoredFailures = mutableListOf<String>()
    private lateinit var filesDir: File

    @BeforeEach
    fun setUp() {
        filesDir = File(directory, "files").apply { mkdirs() }
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns directory
        every { context.packageManager } returns packageManager
        every { preference.get() } returns BasePreferences.ExtensionInstaller.PACKAGEINSTALLER
        every { extension.pkgName } returns PACKAGE
        every { extension.versionCode } returns VERSION
        every { extension.store.signingKey } returns "repository-key"
        val packageInfo = mockk<PackageInfo>().apply {
            packageName = PACKAGE
            versionCode = VERSION.toInt()
        }
        every { packageManager.getPackageArchiveInfo(any(), 0) } returns packageInfo
        mockkObject(Installer.Companion)
        every { Installer.cancelInstallQueue(any(), any()) } returns Unit
        mockkStatic(::validateReinstallApk)
        every { validateReinstallApk(context, any(), PACKAGE, VERSION, "repository-key") } answers {
            assertEquals("complete APK", secondArg<File>().readText())
            validated.set(true)
            packageInfo
        }
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        unmockkAll()
    }

    @Test
    fun `complete validated download precedes the removal flow`() = runBlocking {
        val installer = installer()
        withTimeout(5_000) {
            val result = async { reinstall(installer) }
            val (id, file) = launches.receive()
            assertEquals(File(filesDir, "extension-reinstall"), file.parentFile)
            assertTrue(file.exists())
            assertTrue(installer.shouldContinueReinstall(id, PACKAGE, installer.processToken))
            installer.updateInstallStep(id, InstallStep.Installed)
            assertEquals(InstallStep.Installed, result.await())
            // The coordinator owns cleanup once the APK has been handed over.
            assertTrue(file.exists())
        }
    }

    @Test
    fun `network failure never opens removal and cleans the partial staging file`() = runBlocking {
        val installer = installer(httpStatus = 503)

        assertEquals(InstallStep.Error, withTimeout(5_000) { reinstall(installer) })
        assertTrue(launches.tryReceive().isFailure)
        assertFalse(validated.get())
        awaitStagingCleanup()
    }

    @Test
    fun `invalid replacement never opens removal`() = runBlocking {
        every { validateReinstallApk(context, any(), PACKAGE, VERSION, "repository-key") } throws
            ReinstallApkValidationException("The repository signing certificate does not match")
        val installer = installer()

        assertEquals(InstallStep.Error, withTimeout(5_000) { reinstall(installer) })
        assertTrue(launches.tryReceive().isFailure)
        assertTrue(installer.getInstallError(PACKAGE)!!.contains("certificate does not match"))
        awaitStagingCleanup()
    }

    @Test
    fun `cancelled removal attempt cannot later install its replacement`() = runBlocking {
        val installer = installer()
        withTimeout(5_000) {
            val result = async { reinstall(installer) }
            val (id, _) = launches.receive()
            installer.cancelInstall(PACKAGE)
            assertEquals(InstallStep.Idle, result.await())
            assertFalse(installer.shouldContinueReinstall(id, PACKAGE, installer.processToken))
        }
    }

    @Test
    fun `a restored request is rejected after a newer attempt even if that attempt finished`() = runBlocking {
        val installer = installer()
        assertTrue(installer.shouldContinueReinstall(42, PACKAGE, "previous-process"))
        withTimeout(5_000) {
            val result = async { reinstall(installer) }
            val (id, _) = launches.receive()
            assertFalse(installer.shouldContinueReinstall(42, PACKAGE, "previous-process"))
            installer.updateInstallStep(id, InstallStep.Installed)
            assertEquals(InstallStep.Installed, result.await())
        }
        assertFalse(installer.shouldContinueReinstall(42, PACKAGE, "previous-process"))
    }

    @Test
    fun `a native error after process recovery remains visible without the old flow collector`() {
        val installer = installer()
        assertTrue(installer.prepareReinstallHandoff(42, PACKAGE, "previous-process"))
        assertTrue(installer.shouldContinueReinstall(42, PACKAGE, "previous-process"))

        installer.updateInstallStep(42, InstallStep.Error, "PackageInstaller: status 5")

        assertEquals("PackageInstaller: status 5", installer.getInstallError(PACKAGE))
        assertEquals(listOf("$PACKAGE\nPackageInstaller: status 5"), restoredFailures)
        assertFalse(installer.shouldContinueReinstall(42, PACKAGE, "previous-process"))
        installer.updateInstallStep(42, InstallStep.Error, "Duplicate late result")
        assertEquals(1, restoredFailures.size)
    }

    @Test
    fun `canceling a recovered install ignores its late result`() {
        val installer = installer()
        assertTrue(installer.prepareReinstallHandoff(42, PACKAGE, "previous-process"))
        installer.cancelInstall(PACKAGE)
        installer.updateInstallStep(42, InstallStep.Error, "A canceled attempt failed")

        verify(exactly = 1) { Installer.cancelInstallQueue(context, 42) }
        assertTrue(restoredFailures.isEmpty())
        assertFalse(installer.shouldContinueReinstall(42, PACKAGE, "previous-process"))
    }

    @Test
    fun `only a detected signing conflict enables the reinstall action`() = runBlocking {
        mockkStatic(::findExtensionSignatureMismatch)
        every { findExtensionSignatureMismatch(context, any(), PACKAGE) } returns "old signer != repository signer"
        mockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
        every { context.stringResource(MR.strings.ext_install_signature_mismatch) } returns "Different signing keys"
        val installer = installer()

        val result = withTimeout(5_000) {
            installer.downloadAndInstall(URL, extension, verifyDownloadedUpdate = true).first { it.isCompleted() }
        }

        assertEquals(InstallStep.Error, result)
        assertTrue(installer.canReinstallExtension(PACKAGE))
        assertTrue(launches.tryReceive().isFailure)
        installer.recordInstallError(PACKAGE, "Some other error mentioning Signature mismatch")
        assertFalse(installer.canReinstallExtension(PACKAGE))
    }

    private suspend fun reinstall(installer: ExtensionInstaller) =
        installer.downloadAndInstall(URL, extension, reinstallForSignatureMismatch = true).first { it.isCompleted() }

    private suspend fun awaitStagingCleanup() = withTimeout(5_000) {
        while (File(filesDir, "extension-reinstall").listFiles().orEmpty().isNotEmpty()) delay(10)
    }

    private fun installer(httpStatus: Int = 200): ExtensionInstaller {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(httpStatus)
                .message("Test response")
                .body("complete APK".toResponseBody())
                .build()
        }.build()
        return ExtensionInstaller(
            context,
            scope,
            client,
            preference,
            notifyRestoredFailure = { restoredFailures.add(it) },
            startReinstall = { id, file, replacement, selectedInstaller ->
                assertTrue(validated.get())
                assertEquals(extension, replacement)
                assertEquals(BasePreferences.ExtensionInstaller.PACKAGEINSTALLER, selectedInstaller)
                check(launches.trySend(id to file).isSuccess)
            },
        )
    }

    private companion object {
        const val PACKAGE = "eu.kanade.tachiyomi.extension.test"
        const val VERSION = 106068L
        const val URL = "https://example.test/extension.apk"
    }
}
