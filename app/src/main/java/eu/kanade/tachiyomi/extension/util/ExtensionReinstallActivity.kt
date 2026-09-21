package eu.kanade.tachiyomi.extension.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.toast
import logcat.LogPriority
import rikka.shizuku.Shizuku
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** Coordinates the user-confirmed removal and installation of an already validated APK. */
class ExtensionReinstallActivity : Activity() {

    private val extensionManager: ExtensionManager by lazy { Injekt.get() }
    private var request: ReinstallRequest? = null
    private var stagedFile: File? = null
    private var waitingForUninstall = false
    private var handedToInstaller = false
    private var resultHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        waitingForUninstall = savedInstanceState?.getBoolean(STATE_WAITING) ?: false
        handedToInstaller = savedInstanceState?.getBoolean(STATE_HANDED_OFF) ?: false
        resultHandled = savedInstanceState?.getBoolean(STATE_RESULT_HANDLED) ?: false
        try {
            val current = readRequest().also { request = it }
            // Android restores the pending activity result after rotation/process recreation.
            // Do not launch removal again or bypass its result by treating absence as success.
            when (reinstallOnCreate(waitingForUninstall, handedToInstaller, resultHandled, mayContinue(current))) {
                ReinstallOnCreate.FINISH -> {
                    finish()
                    return
                }
                ReinstallOnCreate.WAIT -> return
                ReinstallOnCreate.START -> Unit
            }

            val apkUri = prepareInstallation(current)
            if (!mayContinue(current)) {
                finish()
                return
            }
            if (findExtensionSignatureMismatch(this, current.file, current.pkgName) == null) {
                handOff(current, apkUri)
                return
            }
            if (!mayContinue(current)) {
                finish()
                return
            }
            @Suppress("DEPRECATION")
            val uninstallIntent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, "package:${current.pkgName}".toUri())
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            waitingForUninstall = true
            startActivityForResult(uninstallIntent, UNINSTALL_REQUEST_CODE)
        } catch (error: Exception) {
            fail(error)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_WAITING, waitingForUninstall)
        outState.putBoolean(STATE_HANDED_OFF, handedToInstaller)
        outState.putBoolean(STATE_RESULT_HANDLED, resultHandled)
        super.onSaveInstanceState(outState)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != UNINSTALL_REQUEST_CODE) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        if (handedToInstaller || resultHandled) return
        waitingForUninstall = false
        resultHandled = true
        try {
            val current = checkNotNull(request) { "The staged reinstall request is missing" }
            val allowed = mayContinue(current)
            val stillInstalled = allowed && isPackageStillInstalled(current.pkgName)
            when (reinstallAfterRemoval(resultCode, stillInstalled, allowed)) {
                ReinstallAfterRemoval.ABANDON -> finish()
                ReinstallAfterRemoval.CANCEL -> finishWithStep(InstallStep.Idle)
                ReinstallAfterRemoval.ERROR -> error(
                    "Android did not remove ${current.pkgName}; the existing extension was kept",
                )
                ReinstallAfterRemoval.INSTALL -> handOff(current, prepareInstallation(current))
            }
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun readRequest(): ReinstallRequest {
        val path = checkNotNull(intent.getStringExtra(EXTRA_APK_PATH)) { "The staged APK path is missing" }
        val file = File(path).canonicalFile
        val stagingDir = File(filesDir, "extension-reinstall").canonicalFile
        check(stagingDir.parentFile == filesDir.canonicalFile) { "The reinstall directory is outside app storage" }
        check(file.parentFile == stagingDir && file.name.endsWith(".apk")) {
            "The staged APK is outside the reinstall directory"
        }
        // Only a file in our dedicated staging directory can be deleted during cleanup.
        stagedFile = file
        val pkgName = checkNotNull(intent.getStringExtra(EXTRA_PACKAGE_NAME)) { "The extension package is missing" }
        check(pkgName != packageName) { "The application itself cannot be reinstalled as an extension" }
        return ReinstallRequest(
            downloadId = intent.getLongExtra(ExtensionInstaller.EXTRA_DOWNLOAD_ID, -1L).also {
                check(it >= 0) { "The reinstall attempt ID is missing" }
            },
            file = file,
            pkgName = pkgName,
            versionCode = intent.getLongExtra(EXTRA_VERSION_CODE, -1L).also {
                check(it >= 0) { "The expected extension version is missing" }
            },
            signingKey = checkNotNull(intent.getStringExtra(EXTRA_SIGNING_KEY)) {
                "The expected signing key is missing"
            },
            installer = BasePreferences.ExtensionInstaller.valueOf(
                checkNotNull(intent.getStringExtra(EXTRA_INSTALLER)) { "The selected installer is missing" },
            ),
            processToken = checkNotNull(intent.getStringExtra(EXTRA_PROCESS_TOKEN)) {
                "The reinstall process token is missing"
            },
        )
    }

    private fun prepareInstallation(current: ReinstallRequest): Uri {
        validateReinstallApk(this, current.file, current.pkgName, current.versionCode, current.signingKey)
        when (current.installer) {
            BasePreferences.ExtensionInstaller.PRIVATE -> error(
                "Select the Android or Shizuku installer before reinstalling this extension",
            )
            BasePreferences.ExtensionInstaller.PACKAGEINSTALLER,
            BasePreferences.ExtensionInstaller.LEGACY,
            -> check(packageManager.canRequestPackageInstalls()) {
                "Allow this app to install unknown apps in Android settings, then retry."
            }
            BasePreferences.ExtensionInstaller.SHIZUKU -> check(
                Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED,
            ) { "Start Shizuku and grant this app permission before retrying the reinstall" }
        }
        // Resolve the FileProvider URI before any removal, not only after the old APK is gone.
        return current.file.getUriCompat(this)
    }

    private fun mayContinue(current: ReinstallRequest): Boolean =
        extensionManager.shouldContinueReinstall(current.downloadId, current.pkgName, current.processToken)

    @Suppress("DEPRECATION")
    private fun isPackageStillInstalled(pkgName: String): Boolean = try {
        packageManager.getPackageInfo(pkgName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun handOff(current: ReinstallRequest, apkUri: Uri) {
        if (!extensionManager.prepareReinstallHandoff(current.downloadId, current.pkgName, current.processToken)) {
            finish()
            return
        }
        when (current.installer) {
            BasePreferences.ExtensionInstaller.LEGACY -> startActivity(
                Intent(this, ExtensionInstallActivity::class.java)
                    .setDataAndType(apkUri, ExtensionInstaller.APK_MIME)
                    .putExtra(ExtensionInstaller.EXTRA_DOWNLOAD_ID, current.downloadId)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            BasePreferences.ExtensionInstaller.PACKAGEINSTALLER,
            BasePreferences.ExtensionInstaller.SHIZUKU,
            -> checkNotNull(
                ContextCompat.startForegroundService(
                    this,
                    ExtensionInstallService.getIntent(this, current.downloadId, apkUri, current.installer),
                ),
            ) { "The extension installation service could not start" }
            BasePreferences.ExtensionInstaller.PRIVATE -> error(
                "Private installation does not support this reinstall flow",
            )
        }
        handedToInstaller = true
        finish()
    }

    private fun fail(error: Exception) {
        val message = "Extension reinstall failed: ${error.message ?: error.javaClass.simpleName}"
        logcat(LogPriority.ERROR, error) { message }
        toast("${stringResource(MR.strings.ext_install_error_title)}\n$message", Toast.LENGTH_LONG)
        finishWithStep(InstallStep.Error, message)
    }

    private fun finishWithStep(step: InstallStep, message: String? = null) {
        request?.takeIf(::mayContinue)?.let { extensionManager.updateInstallStep(it.downloadId, step, message) }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !handedToInstaller) stagedFile?.delete()
    }

    private data class ReinstallRequest(
        val downloadId: Long,
        val file: File,
        val pkgName: String,
        val versionCode: Long,
        val signingKey: String,
        val installer: BasePreferences.ExtensionInstaller,
        val processToken: String,
    )

    companion object {
        fun getIntent(
            context: Context,
            downloadId: Long,
            tempFile: File,
            extension: Extension.Available,
            installer: BasePreferences.ExtensionInstaller,
        ): Intent = Intent(context, ExtensionReinstallActivity::class.java)
            .putExtra(ExtensionInstaller.EXTRA_DOWNLOAD_ID, downloadId)
            .putExtra(EXTRA_APK_PATH, tempFile.absolutePath)
            .putExtra(EXTRA_PACKAGE_NAME, extension.pkgName)
            .putExtra(EXTRA_VERSION_CODE, extension.versionCode)
            .putExtra(EXTRA_SIGNING_KEY, extension.store.signingKey)
            .putExtra(EXTRA_INSTALLER, installer.name)
            .putExtra(EXTRA_PROCESS_TOKEN, Injekt.get<ExtensionManager>().reinstallProcessToken)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

internal enum class ReinstallOnCreate {
    FINISH,
    WAIT,
    START,
}

internal fun reinstallOnCreate(
    waitingForUninstall: Boolean,
    handedToInstaller: Boolean,
    resultHandled: Boolean,
    mayContinue: Boolean,
): ReinstallOnCreate = when {
    handedToInstaller || resultHandled || !mayContinue -> ReinstallOnCreate.FINISH
    waitingForUninstall -> ReinstallOnCreate.WAIT
    else -> ReinstallOnCreate.START
}

internal enum class ReinstallAfterRemoval {
    ABANDON,
    CANCEL,
    ERROR,
    INSTALL,
}

internal fun reinstallAfterRemoval(
    resultCode: Int,
    packageStillInstalled: Boolean,
    mayContinue: Boolean,
): ReinstallAfterRemoval = when {
    !mayContinue -> ReinstallAfterRemoval.ABANDON
    !packageStillInstalled -> ReinstallAfterRemoval.INSTALL
    resultCode == Activity.RESULT_CANCELED -> ReinstallAfterRemoval.CANCEL
    else -> ReinstallAfterRemoval.ERROR
}

private const val UNINSTALL_REQUEST_CODE = 501
private const val EXTRA_APK_PATH = "ExtensionReinstallActivity.extra.APK_PATH"
private const val EXTRA_PACKAGE_NAME = "ExtensionReinstallActivity.extra.PACKAGE_NAME"
private const val EXTRA_VERSION_CODE = "ExtensionReinstallActivity.extra.VERSION_CODE"
private const val EXTRA_SIGNING_KEY = "ExtensionReinstallActivity.extra.SIGNING_KEY"
private const val EXTRA_INSTALLER = "ExtensionReinstallActivity.extra.INSTALLER"
private const val EXTRA_PROCESS_TOKEN = "ExtensionReinstallActivity.extra.PROCESS_TOKEN"
private const val STATE_WAITING = "waitingForUninstall"
private const val STATE_HANDED_OFF = "handedToInstaller"
private const val STATE_RESULT_HANDLED = "resultHandled"
