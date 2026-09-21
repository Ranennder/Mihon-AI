package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import eu.kanade.tachiyomi.util.lang.Hash
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Checks the replacement before removing an installed extension. Call again before
 * installation when returning from Android's uninstall confirmation.
 */
internal fun validateReinstallApk(
    context: Context,
    apkFile: File,
    packageName: String,
    minimumVersion: Long,
    signingKey: String,
    sdkInt: Int = Build.VERSION.SDK_INT,
): PackageInfo {
    try {
        checkReinstallApk(packageName.isNotBlank() && packageName != context.packageName) {
            "Invalid extension package for reinstallation: $packageName"
        }
        checkReinstallApk(minimumVersion >= 0) { "Invalid expected extension version: $minimumVersion" }
        val expectedFingerprint = signingKey.trim().lowercase()
        checkReinstallApk(expectedFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "The extension repository has no valid SHA-256 signing key"
        }

        val filesDirectory = context.filesDir.canonicalFile
        val stagingDirectory = File(filesDirectory, "extension-reinstall").canonicalFile
        val stagedApk = apkFile.canonicalFile
        checkReinstallApk(
            stagingDirectory.parentFile == filesDirectory &&
                stagedApk != stagingDirectory && stagedApk.toPath().startsWith(stagingDirectory.toPath()),
        ) { "The replacement APK is outside the extension reinstallation directory" }
        checkReinstallApk(stagedApk.isFile && stagedApk.canRead() && stagedApk.length() > 0) {
            "The replacement extension APK is missing or unreadable"
        }

        @Suppress("DEPRECATION")
        val signingFlags = if (sdkInt >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val packageInfo = context.packageManager.getPackageArchiveInfo(
            stagedApk.absolutePath,
            signingFlags or PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA,
        ) ?: throw ReinstallApkValidationException("Android could not inspect the replacement extension APK")
        checkReinstallApk(packageInfo.packageName == packageName) {
            "Replacement APK package ${packageInfo.packageName} does not match $packageName"
        }
        @Suppress("DEPRECATION")
        val versionCode = if (sdkInt >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        checkReinstallApk(versionCode >= minimumVersion) {
            "Replacement APK version $versionCode is older than expected $minimumVersion"
        }
        // Keep this feature check aligned with ExtensionLoader.isPackageAnExtension.
        checkReinstallApk(packageInfo.reqFeatures.orEmpty().any { it.name == "tachiyomi.extension" }) {
            "The replacement APK is not a Tachiyomi extension"
        }
        val applicationInfo = packageInfo.applicationInfo
            ?: throw ReinstallApkValidationException("The replacement APK has no application metadata")
        checkReinstallApk(applicationInfo.minSdkVersion in 1..sdkInt) {
            "Replacement APK requires Android API ${applicationInfo.minSdkVersion}; this device uses API $sdkInt"
        }
        val libVersion = ExtensionLibVersion.parse(packageInfo)
        checkReinstallApk(libVersion != null && ExtensionLibVersion.isSupported(libVersion)) {
            "Replacement APK extension library version $libVersion is unsupported; supported versions: " +
                ExtensionLibVersion.supportedVersions.joinToString()
        }

        val certificates = if (sdkInt >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo
                ?: throw ReinstallApkValidationException("The replacement APK has no signing metadata")
            // Match ExtensionLoader/TrustExtension: a trusted certificate in a valid
            // single-signer rotation history remains trusted after rotation.
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        }
        checkReinstallApk(!certificates.isNullOrEmpty()) { "The replacement APK has no signing certificates" }
        val fingerprints = certificates!!.map { signature ->
            val bytes = signature.toByteArray()
            checkReinstallApk(bytes.isNotEmpty()) { "The replacement APK has an empty signing certificate" }
            Hash.sha256(bytes)
        }
        checkReinstallApk(expectedFingerprint in fingerprints) {
            "Replacement APK signing certificates do not match the selected repository: " +
                "expected SHA-256=$expectedFingerprint, downloaded SHA-256=[${fingerprints.joinToString()}]"
        }
        return packageInfo
    } catch (error: CancellationException) {
        throw error
    } catch (error: ReinstallApkValidationException) {
        throw error
    } catch (error: Exception) {
        throw ReinstallApkValidationException(
            "Could not validate the replacement extension APK: ${error.message ?: error.javaClass.simpleName}",
            error,
        )
    }
}

internal class ReinstallApkValidationException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

private inline fun checkReinstallApk(condition: Boolean, message: () -> String) {
    if (!condition) throw ReinstallApkValidationException(message())
}
