package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import eu.kanade.tachiyomi.util.lang.Hash
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class ExtensionSignatureCheckTest {

    private val packageName = "eu.kanade.tachiyomi.extension.ru.allhentai"
    private val apkFile = File("extension-update.apk")
    private val packageManager = mockk<PackageManager>()
    private val context = mockk<Context>().also { every { it.packageManager } returns packageManager }

    @Test
    fun `matching current certificates allow the system update`() {
        packages(modernPackage("current"), modernPackage("current"))

        assertNull(check())
        verify(exactly = 1) { packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES) }
        verify(exactly = 1) {
            packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    }

    @Test
    fun `different nonrotated certificates report both fingerprints`() {
        packages(modernPackage("old-store"), modernPackage("new-store"))

        val diagnostic = check()!!

        assertTrue(diagnostic.contains(packageName))
        assertTrue(diagnostic.contains("installed SHA-256=[${Hash.sha256("old-store")}]"))
        assertTrue(diagnostic.contains("downloaded SHA-256=[${Hash.sha256("new-store")}]"))
    }

    @Test
    fun `downloaded certificate rotation is left to Android`() {
        packages(modernPackage("old"), modernPackage("new", history = listOf("old", "new")))

        assertNull(check())
    }

    @Test
    fun `installed certificate history makes the check inconclusive`() {
        for (downloaded in listOf("old", "current", "other")) {
            packages(modernPackage("current", history = listOf("old", "current")), modernPackage(downloaded))

            assertNull(check())
        }
    }

    @Test
    fun `history is respected even if the OEM rotation flag is false`() {
        packages(modernPackage("old"), modernPackage("new", history = listOf("old", "new"), hasPast = false))

        assertNull(check())
    }

    @Test
    fun `multiple signer sets are compared without ordering`() {
        packages(modernPackage("first", "second"), modernPackage("second", "first"))

        assertNull(check())
    }

    @Test
    fun `sharing one signer does not make different multiple signer identities match`() {
        for (downloaded in listOf(listOf("first", "third"), listOf("first"))) {
            packages(modernPackage("first", "second"), modernPackage(*downloaded.toTypedArray()))

            assertTrue(check()!!.contains("Signature mismatch"))
        }
    }

    @Test
    fun `missing or empty signing metadata does not reject an update`() {
        val missingInfo = mockk<PackageInfo>().apply {
            packageName = this@ExtensionSignatureCheckTest.packageName
            signingInfo = null
        }
        for (incomplete in listOf(missingInfo, modernPackage(), modernPackage(""))) {
            packages(incomplete, modernPackage("valid"))
            assertNull(check())
            packages(modernPackage("valid"), incomplete)
            assertNull(check())
        }
    }

    @Test
    fun `older Android reads legacy signing certificates and detects a mismatch`() {
        val flags = PackageManager.GET_SIGNATURES
        packages(legacyPackage("old"), legacyPackage("new"), flags)

        assertTrue(check(sdkInt = 27)!!.contains("Signature mismatch"))
        verify(exactly = 1) { packageManager.getPackageInfo(packageName, flags) }
        verify(exactly = 1) { packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags) }

        packages(legacyPackage("same"), legacyPackage("same"), flags)
        assertNull(check(sdkInt = 27))
    }

    @Test
    fun `a package unavailable to PackageManager leaves the check inconclusive`() {
        every { packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES) } throws
            mockk<PackageManager.NameNotFoundException>()

        assertNull(check())
    }

    @Test
    fun `an unreadable archive leaves the check inconclusive`() {
        packages(modernPackage("old"), null)

        assertNull(check())
    }

    @Test
    fun `package manager access failures do not prevent installation`() {
        packages(modernPackage("old"), modernPackage("new"))
        every { packageManager.getPackageArchiveInfo(any(), any<Int>()) } throws SecurityException("Access denied")

        assertNull(check())
    }

    @Test
    fun `cancellation during package inspection is propagated`() {
        packages(modernPackage("old"), modernPackage("new"))
        val cancellation = CancellationException("Installation cancelled")
        every { packageManager.getPackageArchiveInfo(any(), any<Int>()) } throws cancellation

        assertEquals(cancellation, assertThrows(CancellationException::class.java) { check() })
    }

    @Test
    fun `a different APK package is not misreported as a signature mismatch`() {
        val downloaded = modernPackage("new").apply { packageName = "another.extension" }
        packages(modernPackage("old"), downloaded)

        assertNull(check())
    }

    private fun check(sdkInt: Int = 28): String? =
        findExtensionSignatureMismatch(context, apkFile, packageName, sdkInt)

    private fun packages(
        installed: PackageInfo,
        downloaded: PackageInfo?,
        flags: Int = PackageManager.GET_SIGNING_CERTIFICATES,
    ) {
        every { packageManager.getPackageInfo(packageName, flags) } returns installed
        every { packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags) } returns downloaded
    }

    private fun modernPackage(
        vararg current: String,
        history: List<String> = if (current.size == 1) current.toList() else emptyList(),
        hasPast: Boolean = history.size > 1,
    ) = mockk<PackageInfo>().apply {
        packageName = this@ExtensionSignatureCheckTest.packageName
        signingInfo = mockk<SigningInfo>().also { info ->
            every { info.apkContentsSigners } returns current.map(::signature).toTypedArray()
            every { info.signingCertificateHistory } returns history.map(::signature).toTypedArray()
            every { info.hasPastSigningCertificates() } returns hasPast
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyPackage(vararg signers: String) = mockk<PackageInfo>().apply {
        packageName = this@ExtensionSignatureCheckTest.packageName
        signatures = signers.map(::signature).toTypedArray()
    }

    private fun signature(value: String) = mockk<Signature>().also {
        every { it.toByteArray() } returns value.toByteArray()
    }
}
