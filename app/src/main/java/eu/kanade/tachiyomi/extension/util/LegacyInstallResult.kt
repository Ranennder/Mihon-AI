package eu.kanade.tachiyomi.extension.util

import android.app.Activity
import eu.kanade.tachiyomi.extension.model.InstallStep

internal data class LegacyInstallResult(val step: InstallStep, val errorMessage: String? = null)

// MIUI can emit an empty cancellation while opening the real installer. Explicit results
// must still reach the normal result handler, even when they arrive within the first second.
internal fun isEmptyLegacyInstallCancellation(activityResult: Int, installResult: Int?): Boolean =
    activityResult == Activity.RESULT_CANCELED && installResult == null

internal fun parseLegacyInstallResult(activityResult: Int, installResult: Int?): LegacyInstallResult {
    val step = when {
        // A native failure can accompany RESULT_CANCELED on vendor installers.
        installResult != null && installResult < 0 && installResult != INSTALL_FAILED_ABORTED -> InstallStep.Error
        activityResult == Activity.RESULT_OK -> InstallStep.Installed
        activityResult == Activity.RESULT_CANCELED -> InstallStep.Idle
        else -> InstallStep.Error
    }
    if (step != InstallStep.Error) return LegacyInstallResult(step)

    val nativeResult = when (installResult) {
        null -> "not provided"
        -1 -> "-1 (INSTALL_FAILED_ALREADY_EXISTS)"
        -2 -> "-2 (INSTALL_FAILED_INVALID_APK)"
        -3 -> "-3 (INSTALL_FAILED_INVALID_URI)"
        -4 -> "-4 (INSTALL_FAILED_INSUFFICIENT_STORAGE)"
        -7 -> "-7 (INSTALL_FAILED_UPDATE_INCOMPATIBLE)"
        -12 -> "-12 (INSTALL_FAILED_OLDER_SDK)"
        -25 -> "-25 (INSTALL_FAILED_VERSION_DOWNGRADE)"
        -29 -> "-29 (INSTALL_FAILED_DEPRECATED_SDK_VERSION)"
        -111 -> "-111 (INSTALL_FAILED_USER_RESTRICTED)"
        INSTALL_FAILED_ABORTED -> "$INSTALL_FAILED_ABORTED (INSTALL_FAILED_ABORTED)"
        else -> installResult.toString()
    }
    return LegacyInstallResult(
        step,
        "Legacy installer: activity result $activityResult, install result $nativeResult",
    )
}

private const val INSTALL_FAILED_ABORTED = -115
