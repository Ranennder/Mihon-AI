package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Bundle
import eu.kanade.tachiyomi.util.lang.Hash
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException

class ExtensionReinstallApkValidationTest {

    @TempDir
    lateinit var filesDir: File

    private val packageName = "eu.kanade.tachiyomi.extension.ru.allhentai"
    private val signingKey = Hash.sha256("repository-signer")
    private val packageManager = mockk<PackageManager>()
    private val context = mockk<Context>()
    private lateinit var apkFile: File

    @BeforeEach
    fun setUp() {
        every { context.packageName } returns "app.mihon.remote"
        every { context.filesDir } returns filesDir
        every { context.packageManager } returns packageManager
        apkFile = File(filesDir, "extension-reinstall/update.apk").apply {
            parentFile!!.mkdirs()
            writeText("staged APK")
        }
    }

    @Test
    fun `a verified replacement is accepted with feature and certificate flags`() {
        val archive = modernPackage()
        stubArchive(archive)

        assertSame(archive, validate())
        verify(exactly = 1) {
            packageManager.getPackageArchiveInfo(
                apkFile.canonicalPath,
                PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_CONFIGURATIONS or
                    PackageManager.GET_META_DATA,
            )
        }
    }

    @Test
    fun `valid certificate rotation preserves the repository trust`() {
        val archive = modernPackage(current = "rotated-signer", history = listOf("repository-signer", "rotated-signer"))
        stubArchive(archive)

        assertSame(archive, validate())
    }

    @Test
    fun `multiple signers use current certificates instead of rotation history`() {
        val archive = modernPackage(multiple = true, history = emptyList())
        stubArchive(archive)

        assertSame(archive, validate())
    }

    @Test
    @Suppress("DEPRECATION")
    fun `Android 26 validates legacy certificates and integer version code`() {
        val archive = modernPackage().apply {
            signatures = arrayOf(signature("repository-signer"))
            versionCode = 106068
        }
        stubArchive(archive)

        assertSame(archive, validate(sdkInt = 26))
        verify(exactly = 1) {
            packageManager.getPackageArchiveInfo(
                apkFile.canonicalPath,
                PackageManager.GET_SIGNATURES or PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA,
            )
        }
    }

    @Test
    fun `a wrong package or the host app cannot be reinstalled`() {
        stubArchive(modernPackage().apply { packageName = "another.extension" })
        assertRejected("does not match")

        assertTrue(
            assertThrows(ReinstallApkValidationException::class.java) {
                validate(packageName = "app.mihon.remote")
            }.message!!.contains("Invalid extension package"),
        )
    }

    @Test
    fun `a stale downloaded version is rejected`() {
        stubArchive(modernPackage(versionCode = 106067))

        assertRejected("older than expected")
    }

    @Test
    fun `modern Android compares the full 64 bit version code`() {
        val archive = modernPackage(versionCode = Int.MAX_VALUE.toLong() + 1)
        stubArchive(archive)

        assertSame(archive, validate())
    }

    @Test
    fun `an ordinary Android app or unknown feature metadata is rejected`() {
        val featureSets: List<Array<FeatureInfo>?> = listOf(
            null,
            emptyArray(),
            arrayOf(mockk<FeatureInfo>().apply { name = "another.feature" }),
        )
        for (features in featureSets) {
            stubArchive(modernPackage().apply { reqFeatures = features })

            assertRejected("not a Tachiyomi extension")
        }
    }

    @Test
    fun `incompatible or missing SDK metadata cannot precede uninstall`() {
        stubArchive(modernPackage(minSdk = 29))
        assertRejected("requires Android API 29")

        stubArchive(modernPackage(minSdk = 0))
        assertRejected("requires Android API 0")

        stubArchive(modernPackage().apply { applicationInfo = null })
        assertRejected("no application metadata")
    }

    @Test
    fun `legacy library versions 1 point 4 and 1 point 6 remain supported`() {
        for (versionName in listOf("1.4.65", "1.6.68")) {
            val archive = modernPackage().apply { this.versionName = versionName }
            stubArchive(archive)

            assertSame(archive, validate())
        }
    }

    @Test
    fun `unsupported library versions cannot precede uninstall even with a trusted certificate`() {
        stubArchive(modernPackage().apply { versionName = "1.7.1" })

        assertRejected("extension library version 1.7 is unsupported")
    }

    @Test
    fun `modern metadata normalizes its float and takes precedence over the version name`() {
        val archive = modernPackage().apply {
            versionName = "68"
            applicationInfo!!.metaData = mockk<Bundle>().also {
                every { it.getFloat("tachiyomix.extensionLib") } returns 1.6f
            }
        }
        stubArchive(archive)

        assertEquals(1.6, ExtensionLibVersion.parse(archive))
        assertSame(archive, validate())
    }

    @Test
    fun `unsupported modern metadata cannot fall back to a supported version name`() {
        stubArchive(
            modernPackage().apply {
                applicationInfo!!.metaData = mockk<Bundle>().also {
                    every { it.getFloat("tachiyomix.extensionLib") } returns 1.7f
                }
            },
        )

        assertRejected("extension library version 1.7 is unsupported")
    }

    @Test
    fun `a missing version name is rejected even when modern library metadata is supported`() {
        for (versionName in listOf(null, "")) {
            stubArchive(
                modernPackage().apply {
                    this.versionName = versionName
                    applicationInfo!!.metaData = mockk<Bundle>().also {
                        every { it.getFloat("tachiyomix.extensionLib") } returns 1.6f
                    }
                },
            )

            assertRejected("extension library version null is unsupported")
        }
    }

    @Test
    fun `wrong or missing signing metadata cannot precede uninstall`() {
        stubArchive(modernPackage(current = "other-store"))
        assertRejected("do not match the selected repository")

        stubArchive(modernPackage().apply { signingInfo = null })
        assertRejected("no signing metadata")

        stubArchive(modernPackage(history = emptyList()))
        assertRejected("no signing certificates")

        stubArchive(modernPackage(history = listOf("")))
        assertRejected("empty signing certificate")
    }

    @Test
    fun `an empty or malformed repository key is rejected before reading the APK`() {
        for (key in listOf("", "1234", "z".repeat(64))) {
            assertThrows(ReinstallApkValidationException::class.java) { validate(signingKey = key) }
        }

        verify(exactly = 0) { packageManager.getPackageArchiveInfo(any(), any<Int>()) }
    }

    @Test
    fun `uppercase repository fingerprints retain their identity`() {
        stubArchive(modernPackage())

        validate(signingKey = signingKey.uppercase())
    }

    @Test
    fun `a path traversal or sibling prefix cannot escape the staging directory`() {
        val outside = File(filesDir, "outside.apk").apply { writeText("outside") }
        val sibling = File(filesDir, "extension-reinstall-other/update.apk").apply {
            parentFile!!.mkdirs()
            writeText("outside")
        }
        for (file in listOf(outside, sibling, File(apkFile.parentFile, "../outside.apk"), apkFile.parentFile!!)) {
            assertTrue(
                assertThrows(ReinstallApkValidationException::class.java) { validate(apkFile = file) }
                    .message!!.contains("outside"),
            )
        }

        verify(exactly = 0) { packageManager.getPackageArchiveInfo(any(), any<Int>()) }
    }

    @Test
    fun `a symlink outside the staging directory is rejected`() {
        val outside = File(filesDir, "outside.apk").apply { writeText("outside") }
        val link = File(apkFile.parentFile, "linked.apk")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        assertTrue(
            assertThrows(ReinstallApkValidationException::class.java) { validate(apkFile = link) }
                .message!!.contains("outside"),
        )
        verify(exactly = 0) { packageManager.getPackageArchiveInfo(any(), any<Int>()) }
    }

    @Test
    fun `the staging directory itself cannot be redirected outside the files directory`() {
        val actualFiles = File(filesDir, "actual-files").apply { mkdirs() }
        every { context.filesDir } returns actualFiles
        Files.createSymbolicLink(File(actualFiles, "extension-reinstall").toPath(), apkFile.parentFile!!.toPath())

        assertRejected("outside")
        verify(exactly = 0) { packageManager.getPackageArchiveInfo(any(), any<Int>()) }
    }

    @Test
    fun `missing and empty staged files cannot precede uninstall`() {
        apkFile.delete()
        assertRejected("missing or unreadable")

        apkFile.writeBytes(byteArrayOf())
        assertRejected("missing or unreadable")

        verify(exactly = 0) { packageManager.getPackageArchiveInfo(any(), any<Int>()) }
    }

    @Test
    fun `null archive inspection and package manager failures reject the replacement`() {
        stubArchive(null)
        assertRejected("could not inspect")

        every { packageManager.getPackageArchiveInfo(any(), any<Int>()) } throws SecurityException("Access denied")
        val failure = assertThrows(ReinstallApkValidationException::class.java) { validate() }
        assertTrue(failure.message!!.contains("Access denied"))
        assertTrue(failure.cause is SecurityException)
    }

    @Test
    fun `cancellation is propagated unchanged`() {
        val cancellation = CancellationException("Reinstallation cancelled")
        every { packageManager.getPackageArchiveInfo(any(), any<Int>()) } throws cancellation

        assertEquals(cancellation, assertThrows(CancellationException::class.java) { validate() })
    }

    private fun validate(
        apkFile: File = this.apkFile,
        packageName: String = this.packageName,
        signingKey: String = this.signingKey,
        sdkInt: Int = 28,
    ) = validateReinstallApk(context, apkFile, packageName, 106068L, signingKey, sdkInt)

    private fun assertRejected(message: String) {
        assertTrue(
            assertThrows(ReinstallApkValidationException::class.java) { validate() }.message!!.contains(message),
        )
    }

    private fun stubArchive(packageInfo: PackageInfo?) {
        every { packageManager.getPackageArchiveInfo(any(), any<Int>()) } returns packageInfo
    }

    private fun modernPackage(
        current: String = "repository-signer",
        history: List<String> = listOf(current),
        multiple: Boolean = false,
        versionCode: Long = 106068L,
        minSdk: Int = 26,
    ) = mockk<PackageInfo>().apply {
        packageName = this@ExtensionReinstallApkValidationTest.packageName
        versionName = "1.6.68"
        every { longVersionCode } returns versionCode
        reqFeatures = arrayOf(mockk<FeatureInfo>().apply { name = "tachiyomi.extension" })
        applicationInfo = mockk<ApplicationInfo>().apply { minSdkVersion = minSdk }
        signingInfo = mockk<SigningInfo>().also { info ->
            every { info.hasMultipleSigners() } returns multiple
            every { info.apkContentsSigners } returns arrayOf(signature(current))
            every { info.signingCertificateHistory } returns history.map(::signature).toTypedArray()
        }
    }

    private fun signature(value: String) = mockk<Signature>().also {
        every { it.toByteArray() } returns value.toByteArray()
    }
}
