package eu.kanade.tachiyomi.extension.installer

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentSanitizer
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.util.lang.use
import eu.kanade.tachiyomi.util.system.getParcelableExtraCompat
import eu.kanade.tachiyomi.util.system.getUriSize
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class PackageInstallerInstaller(private val service: Service) : Installer(service) {

    private val packageInstaller = service.packageManager.packageInstaller

    private val packageActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = activeSession ?: return
            if (intent.hasExtra(PackageInstaller.EXTRA_SESSION_ID) &&
                intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != session.second
            ) {
                logcat(LogPriority.WARN) { "Ignoring installer result for an inactive session" }
                return
            }
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    try {
                        val userAction = intent.getParcelableExtraCompat<Intent>(Intent.EXTRA_INTENT)
                            ?.run {
                                // The receiver is not exported, but sanitize the confirmation intent too.
                                IntentSanitizer.Builder()
                                    .allowAction(this.action!!)
                                    .allowExtra(PackageInstaller.EXTRA_SESSION_ID) { id -> id == session.second }
                                    .allowAnyComponent()
                                    .allowPackage {
                                        // There is no way to check the actual installer name so allow all.
                                        true
                                    }
                                    .build()
                                    .sanitizeByFiltering(this)
                            }
                            ?: error("Android did not provide an installation confirmation intent")
                        userAction.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        service.startActivity(userAction)
                    } catch (error: Exception) {
                        val detail = "PackageInstaller could not open confirmation: " +
                            (error.message ?: error.javaClass.simpleName)
                        logcat(LogPriority.ERROR, error) { detail }
                        abandonActiveSession()
                        completeSession(InstallStep.Error, detail)
                    }
                }
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    logcat(LogPriority.INFO) {
                        "PackageInstaller aborted session ${session.second}: $message"
                    }
                    completeSession(InstallStep.Idle)
                }
                PackageInstaller.STATUS_SUCCESS -> completeSession(InstallStep.Installed)
                else -> {
                    val detail = buildString {
                        append("PackageInstaller: status $status")
                        if (!message.isNullOrBlank()) append(", $message")
                    }
                    logcat(LogPriority.ERROR) { detail }
                    completeSession(InstallStep.Error, detail)
                }
            }
        }
    }

    private fun completeSession(step: InstallStep, errorMessage: String? = null) {
        activeSession = null
        continueQueue(step, errorMessage)
    }

    private fun abandonActiveSession() {
        activeSession?.let { (_, sessionId) ->
            try {
                packageInstaller.abandonSession(sessionId)
            } catch (error: Exception) {
                logcat(LogPriority.WARN, error) { "Could not abandon installer session $sessionId" }
            }
        }
    }

    private var activeSession: Pair<Entry, Int>? = null

    // Always ready
    override var ready = true

    override fun processEntry(entry: Entry) {
        super.processEntry(entry)
        activeSession = null
        try {
            val installParams = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                installParams.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            activeSession = entry to packageInstaller.createSession(installParams)
            val fileSize = service.getUriSize(entry.uri) ?: throw IllegalStateException()
            installParams.setSize(fileSize)

            val inputStream = service.contentResolver.openInputStream(entry.uri) ?: throw IllegalStateException()
            val session = packageInstaller.openSession(activeSession!!.second)
            val outputStream = session.openWrite(entry.downloadId.toString(), 0, fileSize)
            session.use {
                arrayOf(inputStream, outputStream).use {
                    inputStream.copyTo(outputStream)
                    session.fsync(outputStream)
                }
                service.contentResolver.delete(entry.uri, null, null)

                val intentSender = PendingIntent.getBroadcast(
                    service,
                    activeSession!!.second,
                    Intent(INSTALL_ACTION).setPackage(service.packageName),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0,
                ).intentSender
                @SuppressLint("RequestInstallPackagesPolicy")
                session.commit(intentSender)
            }
        } catch (e: Exception) {
            val detail = "PackageInstaller failed: ${e.message ?: e.javaClass.simpleName}"
            logcat(LogPriority.ERROR, e) { "${entry.downloadId}: $detail" }
            abandonActiveSession()
            completeSession(InstallStep.Error, detail)
        }
    }

    override fun cancelEntry(entry: Entry): Boolean {
        activeSession?.let { (activeEntry, sessionId) ->
            if (activeEntry == entry) {
                return try {
                    packageInstaller.abandonSession(sessionId)
                    false
                } catch (_: SecurityException) {
                    // Highly likely the session has succeeded
                    true
                }
            }
        }
        return true
    }

    override fun onDestroy() {
        service.unregisterReceiver(packageActionReceiver)
        super.onDestroy()
    }

    init {
        ContextCompat.registerReceiver(
            service,
            packageActionReceiver,
            IntentFilter(INSTALL_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}

private const val INSTALL_ACTION = "PackageInstallerInstaller.INSTALL_ACTION"
