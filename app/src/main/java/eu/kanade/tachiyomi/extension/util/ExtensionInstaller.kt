package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.base.ExtensionInstallerPreference
import eu.kanade.tachiyomi.extension.installer.Installer
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * The installer which installs, updates and uninstalls the extensions.
 *
 * @param context The application context.
 */
internal class ExtensionInstaller(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val httpClient: OkHttpClient = Injekt.get<NetworkHelper>().client,
    private val extensionInstaller: ExtensionInstallerPreference = Injekt.get<BasePreferences>().extensionInstaller,
    private val notifyRestoredFailure: (String) -> Unit = { message ->
        scope.launch(Dispatchers.Main) {
            context.toast("${context.stringResource(MR.strings.ext_install_error_title)}\n$message", Toast.LENGTH_LONG)
        }
    },
    private val startReinstall: (Long, File, Extension.Available, BasePreferences.ExtensionInstaller) -> Unit =
        { downloadId, tempFile, extension, selectedInstaller ->
            context.startActivity(
                ExtensionReinstallActivity.getIntent(context, downloadId, tempFile, extension, selectedInstaller),
            )
        },
    private val startLegacyInstall: (Long, File) -> Unit = { downloadId, tempFile ->
        val intent = Intent(context, ExtensionInstallActivity::class.java)
            .setDataAndType(tempFile.getUriCompat(context), APK_MIME)
            .putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    },
) {

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeSteps = ConcurrentHashMap<Long, MutableStateFlow<InstallStep>>()
    private val activeDownloadIds = ConcurrentHashMap<String, Long>()
    private val activeCalls = ConcurrentHashMap<Long, Call>()
    private val installErrors = ConcurrentHashMap<String, InstallFailure>()
    private val attemptedPackages = ConcurrentHashMap.newKeySet<String>()
    private val restoredInstalls = ConcurrentHashMap<Long, String>()

    // A restored confirmation activity must not reuse a new process's download ID.
    private val downloadIds = AtomicLong(UUID.randomUUID().mostSignificantBits ushr 1)
    val processToken: String = UUID.randomUUID().toString()
    private val legacyInstallMutex = Mutex()

    /**
     * Adds the given extension to the downloads queue and returns an observable containing its
     * step in the installation process.
     *
     * @param url The url of the apk.
     * @param extension The extension to install.
     * @param isUpdateForPrivatelyInstalled If this is an update for a privately installed extension
     */
    @Synchronized
    fun downloadAndInstall(
        url: String,
        extension: Extension,
        isUpdateForPrivatelyInstalled: Boolean = false,
        verifyDownloadedUpdate: Boolean = false,
        reinstallForSignatureMismatch: Boolean = false,
    ): Flow<InstallStep> {
        cancelInstall(extension.pkgName)
        installErrors.remove(extension.pkgName)
        val downloadId = downloadIds.incrementAndGet()

        val step = MutableStateFlow(InstallStep.Pending)
        activeSteps[downloadId] = step
        activeDownloadIds[extension.pkgName] = downloadId

        val job = scope.launch(start = CoroutineStart.LAZY) {
            val directory = if (reinstallForSignatureMismatch) {
                File(context.filesDir, "extension-reinstall")
            } else {
                context.cacheDir
            }
            val tmpFile = File(directory, "extension_${extension.pkgName}_$downloadId.apk")
            var handedToInstaller = false
            try {
                check(directory.isDirectory || directory.mkdirs()) { "Could not prepare extension download directory" }
                step.value = InstallStep.Downloading
                val request = Request.Builder()
                    .url(url)
                    .apply {
                        if (verifyDownloadedUpdate || reinstallForSignatureMismatch) {
                            cacheControl(CacheControl.FORCE_NETWORK)
                        }
                    }
                    .build()
                val call = httpClient.newCall(request)
                activeCalls[downloadId] = call
                ensureActive()
                call.awaitSuccess().use { response ->
                    response.body.byteStream().use { input ->
                        tmpFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                ensureActive()
                if (verifyDownloadedUpdate || reinstallForSignatureMismatch) {
                    val downloadedPackage = context.packageManager
                        .getPackageArchiveInfo(tmpFile.absolutePath, 0)
                        ?: error("Downloaded extension APK could not be read")
                    check(downloadedPackage.packageName == extension.pkgName) {
                        "Downloaded extension package ${downloadedPackage.packageName} " +
                            "does not match ${extension.pkgName}"
                    }
                    val downloadedVersion = PackageInfoCompat.getLongVersionCode(downloadedPackage)
                    check(downloadedVersion >= extension.versionCode) {
                        "Downloaded extension ${extension.pkgName} has version $downloadedVersion, " +
                            "but the update requires ${extension.versionCode}"
                    }
                    if (!reinstallForSignatureMismatch && !isUpdateForPrivatelyInstalled &&
                        extensionInstaller.get() != BasePreferences.ExtensionInstaller.PRIVATE
                    ) {
                        findExtensionSignatureMismatch(context, tmpFile, extension.pkgName)?.let { mismatch ->
                            throw SignatureMismatchException(
                                context.stringResource(MR.strings.ext_install_signature_mismatch) + "\n" + mismatch,
                            )
                        }
                    }
                }

                val replacement = if (reinstallForSignatureMismatch) {
                    check(!isUpdateForPrivatelyInstalled) { "Private extensions do not use system reinstallation" }
                    val available = extension as? Extension.Available ?: error("Missing extension update")
                    validateReinstallApk(
                        context,
                        tmpFile,
                        available.pkgName,
                        available.versionCode,
                        available.store.signingKey,
                    )
                    available
                } else {
                    null
                }

                val startInstallation = {
                    synchronized(this@ExtensionInstaller) {
                        ensureActive()
                        step.value = InstallStep.Installing
                        if (replacement != null) {
                            startReinstall(downloadId, tmpFile, replacement, extensionInstaller.get())
                        } else {
                            installApk(downloadId, tmpFile, isUpdateForPrivatelyInstalled)
                        }
                        handedToInstaller = true
                    }
                }
                if (reinstallForSignatureMismatch ||
                    (
                        !isUpdateForPrivatelyInstalled &&
                            extensionInstaller.get() == BasePreferences.ExtensionInstaller.LEGACY
                        )
                ) {
                    // Legacy installs launch a separate Android confirmation activity. Keep
                    // one active until its result arrives so Update all cannot replace dialogs.
                    legacyInstallMutex.withLock {
                        startInstallation()
                        step.first { it.isCompleted() }
                    }
                } else {
                    startInstallation()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                logcat(LogPriority.ERROR, e)
                updateInstallStep(
                    downloadId,
                    InstallStep.Error,
                    e.message ?: e.javaClass.simpleName,
                    signatureMismatch = e is SignatureMismatchException,
                )
            } finally {
                activeCalls.remove(downloadId)?.cancel()
                if (!handedToInstaller) tmpFile.delete()
            }
        }

        activeJobs[extension.pkgName] = job
        job.start()

        return step.asStateFlow()
            .onCompletion {
                synchronized(this@ExtensionInstaller) {
                    activeJobs.remove(extension.pkgName, job)
                    activeDownloadIds.remove(extension.pkgName, downloadId)
                    if (!step.value.isCompleted()) {
                        Installer.cancelInstallQueue(context, downloadId)
                    }
                }
                activeSteps.remove(downloadId)
                activeCalls.remove(downloadId)?.cancel()
                job.cancel()
            }
    }

    /**
     * Starts an intent to install the extension at the given uri.
     *
     * @param tempFile The file of the extension to install. Delete after use.
     * @param isUpdateForPrivatelyInstalled If this install is an update for a privately installed extension
     */
    private fun installApk(downloadId: Long, tempFile: File, isUpdateForPrivatelyInstalled: Boolean = false) {
        if (isUpdateForPrivatelyInstalled) {
            installApkPrivately(downloadId, tempFile)
            return
        }

        when (val installer = extensionInstaller.get()) {
            BasePreferences.ExtensionInstaller.LEGACY -> {
                startLegacyInstall(downloadId, tempFile)
            }

            BasePreferences.ExtensionInstaller.PRIVATE -> {
                installApkPrivately(downloadId, tempFile)
            }

            else -> {
                val intent = ExtensionInstallService.getIntent(
                    context,
                    downloadId,
                    tempFile.getUriCompat(context),
                    installer,
                )
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    private fun installApkPrivately(downloadId: Long, tempFile: File) {
        try {
            if (ExtensionLoader.installPrivateExtensionFile(context, tempFile)) {
                updateInstallStep(downloadId, InstallStep.Installed)
            } else {
                updateInstallStep(
                    downloadId,
                    InstallStep.Error,
                    "Private installer rejected the extension APK. See the crash log for details.",
                )
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to read downloaded extension file." }
            updateInstallStep(downloadId, InstallStep.Error, e.message ?: e.javaClass.simpleName)
        }

        tempFile.delete()
    }

    /**
     * Cancels extension install and remove from download manager and installer.
     */
    @Synchronized
    fun cancelInstall(pkgName: String) {
        attemptedPackages.add(pkgName)
        val restoredIds = restoredInstalls.filterValues { it == pkgName }.keys
        restoredIds.forEach { downloadId ->
            restoredInstalls.remove(downloadId)
            Installer.cancelInstallQueue(context, downloadId)
        }
        activeJobs.remove(pkgName)?.cancel()
        val downloadId = activeDownloadIds.remove(pkgName) ?: return
        activeCalls.remove(downloadId)?.cancel()
        activeSteps.remove(downloadId)?.value = InstallStep.Idle
        Installer.cancelInstallQueue(context, downloadId)
    }

    /**
     * Starts an intent to uninstall the extension by the given package name.
     *
     * @param pkgName The package name of the extension to uninstall
     */
    fun uninstallApk(pkgName: String) {
        cancelInstall(pkgName)
        if (context.isPackageInstalled(pkgName)) {
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, "package:$pkgName".toUri())
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            ExtensionLoader.uninstallPrivateExtension(context, pkgName)
            ExtensionInstallReceiver.notifyRemoved(context, pkgName)
        }
    }

    /**
     * Sets the step of the installation of an extension.
     *
     * @param downloadId The id of the download.
     * @param step New install step.
     */
    @Synchronized
    fun updateInstallStep(
        downloadId: Long,
        step: InstallStep,
        errorMessage: String? = null,
        signatureMismatch: Boolean = false,
    ) {
        val activeStep = activeSteps[downloadId]
        if (activeStep == null) {
            val restoredPackage = restoredInstalls[downloadId] ?: return
            if (step.isCompleted()) {
                restoredInstalls.remove(downloadId)
                if (step == InstallStep.Error) {
                    val message = errorMessage ?: "Android did not complete the extension installation"
                    recordInstallError(restoredPackage, message)
                    notifyRestoredFailure("$restoredPackage\n$message")
                }
            }
            return
        }
        if (step == InstallStep.Error && errorMessage != null) {
            val pkgName = activeDownloadIds.entries.firstOrNull { it.value == downloadId }?.key
            if (pkgName != null) recordInstallError(pkgName, errorMessage, signatureMismatch)
        }
        activeStep.value = step
    }

    fun recordInstallError(pkgName: String, message: String, signatureMismatch: Boolean = false) {
        installErrors[pkgName] = InstallFailure(message, signatureMismatch)
        logcat(LogPriority.ERROR) { "Extension install failed for $pkgName: $message" }
    }

    fun getInstallError(pkgName: String): String? = installErrors[pkgName]?.message

    fun canReinstallExtension(pkgName: String): Boolean = installErrors[pkgName]?.signatureMismatch == true

    @Synchronized
    fun shouldContinueReinstall(downloadId: Long, pkgName: String, originalProcessToken: String): Boolean {
        return if (originalProcessToken == processToken) {
            activeDownloadIds[pkgName] == downloadId
        } else {
            restoredInstalls[downloadId] == pkgName || pkgName !in attemptedPackages
        }
    }

    @Synchronized
    fun prepareReinstallHandoff(downloadId: Long, pkgName: String, originalProcessToken: String): Boolean {
        if (!shouldContinueReinstall(downloadId, pkgName, originalProcessToken)) return false
        if (originalProcessToken != processToken) {
            attemptedPackages.add(pkgName)
            restoredInstalls[downloadId] = pkgName
        }
        return true
    }

    private data class InstallFailure(val message: String, val signatureMismatch: Boolean)

    private class SignatureMismatchException(message: String) : IllegalStateException(message)

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val EXTRA_DOWNLOAD_ID = "ExtensionInstaller.extra.DOWNLOAD_ID"
    }
}
