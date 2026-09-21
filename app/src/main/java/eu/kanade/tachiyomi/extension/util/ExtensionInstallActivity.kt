package eu.kanade.tachiyomi.extension.util

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.util.system.hasMiuiPackageInstaller
import eu.kanade.tachiyomi.util.system.toast
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

/**
 * Activity used to install extensions, because we can only receive the result of the installation
 * with [startActivityForResult], which we need to update the UI.
 */
class ExtensionInstallActivity : Activity() {

    // MIUI package installer bug workaround
    private var ignoreUntil = 0L
    private var ignoreResult = false
    private var hasIgnoredResult = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            ignoreUntil = savedInstanceState.getLong("ignoreUntil")
            ignoreResult = savedInstanceState.getBoolean("ignoreResult")
            hasIgnoredResult = savedInstanceState.getBoolean("hasIgnoredResult")
            return
        }

        @Suppress("DEPRECATION")
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(intent.data, intent.type)
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        if (hasMiuiPackageInstaller) {
            ignoreResult = true
            ignoreUntil = System.nanoTime() + 1.seconds.inWholeNanoseconds
        }

        try {
            startActivityForResult(installIntent, INSTALL_REQUEST_CODE)
        } catch (error: Exception) {
            setInstallStep(InstallStep.Error)
            toast(error.message)
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("ignoreUntil", ignoreUntil)
        outState.putBoolean("ignoreResult", ignoreResult)
        outState.putBoolean("hasIgnoredResult", hasIgnoredResult)
        super.onSaveInstanceState(outState)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (ignoreResult && System.nanoTime() < ignoreUntil) {
            hasIgnoredResult = true
            return
        }
        if (requestCode == INSTALL_REQUEST_CODE) {
            checkInstallationResult(resultCode)
        }
        finish()
    }

    override fun onStart() {
        super.onStart()
        if (hasIgnoredResult) {
            checkInstallationResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            intent.data?.let { contentResolver.delete(it, null, null) }
        }
    }

    private fun checkInstallationResult(resultCode: Int) {
        val newStep = when (resultCode) {
            RESULT_OK -> InstallStep.Installed
            RESULT_CANCELED -> InstallStep.Idle
            else -> InstallStep.Error
        }
        setInstallStep(newStep)
    }

    private fun setInstallStep(step: InstallStep) {
        val downloadId = intent.getLongExtra(ExtensionInstaller.EXTRA_DOWNLOAD_ID, -1L)
        Injekt.get<ExtensionManager>().updateInstallStep(downloadId, step)
    }
}

private const val INSTALL_REQUEST_CODE = 500
