package eu.kanade.tachiyomi.extension.util

import android.content.Context
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
}
