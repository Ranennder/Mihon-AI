package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import eu.kanade.tachiyomi.util.lang.Hash
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reports only a certain mismatch between non-rotated signing identities. Android
 * remains responsible for deciding whether an APK with signing history can update.
 */
internal fun findExtensionSignatureMismatch(
    context: Context,
    apkFile: File,
    packageName: String,
    sdkInt: Int = Build.VERSION.SDK_INT,
): String? {
    @Suppress("DEPRECATION")
    val flags = if (sdkInt >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }

    return try {
        val packageManager = context.packageManager
        // A private extension can shadow the system package. Only the latter is
        // relevant when Android's installer checks an APK update.
        val installed = packageManager.getPackageInfo(packageName, flags)
        val downloaded = packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return null
        if (installed.packageName != packageName || downloaded.packageName != packageName) return null

        val installedSigners = unrotatedExtensionSigners(installed, sdkInt) ?: return null
        val downloadedSigners = unrotatedExtensionSigners(downloaded, sdkInt) ?: return null
        if (installedSigners == downloadedSigners) return null

        val diagnostic = "Signature mismatch for $packageName: " +
            "installed SHA-256=[${installedSigners.sorted().joinToString()}], " +
            "downloaded SHA-256=[${downloadedSigners.sorted().joinToString()}]"
        context.logcat(LogPriority.ERROR) { diagnostic }
        diagnostic
    } catch (e: CancellationException) {
        throw e
    } catch (_: PackageManager.NameNotFoundException) {
        // Missing visibility or a package removed during the check is inconclusive.
        null
    } catch (e: Exception) {
        // Incomplete OEM signing metadata or a failed package-manager read must
        // not prevent Android from attempting an otherwise valid installation.
        context.logcat(LogPriority.WARN, e) { "Could not check extension signatures for $packageName" }
        null
    }
}

private fun unrotatedExtensionSigners(packageInfo: PackageInfo, sdkInt: Int): Set<String>? {
    val signatures = if (sdkInt >= Build.VERSION_CODES.P) {
        val signingInfo = packageInfo.signingInfo ?: return null
        if (signingInfo.hasPastSigningCertificates() || signingInfo.signingCertificateHistory.orEmpty().size > 1) {
            return null
        }
        signingInfo.apkContentsSigners
    } else {
        @Suppress("DEPRECATION")
        packageInfo.signatures
    }
    if (signatures.isNullOrEmpty()) return null

    return signatures.map { signature ->
        val bytes = signature.toByteArray()
        if (bytes.isEmpty()) return null
        Hash.sha256(bytes)
    }.toSet()
}
