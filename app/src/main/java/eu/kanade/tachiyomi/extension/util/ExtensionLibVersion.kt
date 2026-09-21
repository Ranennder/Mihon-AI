package eu.kanade.tachiyomi.extension.util

import android.content.pm.PackageInfo

/** Reads compatibility metadata without loading code from the extension. */
internal object ExtensionLibVersion {

    val supportedVersions = listOf(1.4, 1.6)

    fun parse(packageInfo: PackageInfo): Double? {
        val versionName = packageInfo.versionName?.takeUnless { it.isEmpty() } ?: return null
        // String conversion avoids promoting Float rounding errors to Double.
        return packageInfo.applicationInfo?.metaData?.getFloat("tachiyomix.extensionLib")
            ?.takeUnless { it == 0.0f }
            ?.toString()
            ?.toDouble()
            ?: versionName.substringBeforeLast('.').toDoubleOrNull()
    }

    fun isSupported(version: Double): Boolean = version in supportedVersions
}
