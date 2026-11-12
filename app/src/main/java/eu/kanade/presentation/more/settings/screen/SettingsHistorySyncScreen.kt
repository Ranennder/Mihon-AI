package eu.kanade.presentation.more.settings.screen

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.tachiyomi.data.historysync.GoogleDriveAuthorization
import eu.kanade.tachiyomi.data.historysync.HistorySyncCredentials
import eu.kanade.tachiyomi.data.historysync.HistorySyncManager
import eu.kanade.tachiyomi.data.historysync.HistorySyncProvider
import eu.kanade.tachiyomi.util.system.openInBrowser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DateFormat
import java.util.Date

object SettingsHistorySyncScreen : SearchableSettings {
    private const val GOOGLE_PRIVACY_URL =
        "https://github.com/Ranennder/Aidoku-AI-GBox/blob/main/docs/GOOGLE_SYNC_PRIVACY.md"

    @Composable
    @ReadOnlyComposable
    override fun getTitleRes() = MR.strings.history_sync_title

    @Composable
    override fun getPreferences(): List<Preference> {
        val manager = remember { Injekt.get<HistorySyncManager>() }
        val status by manager.status.collectAsState()
        val enabled by manager.preferences.enabled.collectAsState()
        return listOfNotNull(
            Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.history_sync_description)),
            Preference.PreferenceItem.CustomPreference(title = stringResource(MR.strings.history_sync_account)) {
                CredentialsForm(manager)
            },
            Preference.PreferenceItem.SwitchPreference(
                preference = manager.preferences.enabled,
                title = stringResource(MR.strings.history_sync_enable),
                onValueChanged = {
                    manager.setEnabled(it)
                    true
                },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.history_sync_now),
                enabled = enabled && status.phase != HistorySyncManager.Phase.SYNCING,
                onClick = manager::requestSync,
            ),
            Preference.PreferenceItem.InfoPreference(
                when (status.phase) {
                    HistorySyncManager.Phase.IDLE -> stringResource(MR.strings.history_sync_idle)
                    HistorySyncManager.Phase.SYNCING -> stringResource(MR.strings.history_sync_working)
                    HistorySyncManager.Phase.SUCCESS -> stringResource(
                        MR.strings.history_sync_success,
                        DateFormat.getDateTimeInstance().format(Date(status.lastSuccess ?: 0)),
                        status.applied,
                        status.unmatched,
                    )
                    HistorySyncManager.Phase.FAILED -> stringResource(MR.strings.history_sync_failed)
                    HistorySyncManager.Phase.NEEDS_CREDENTIALS -> stringResource(MR.strings.history_sync_needs_account)
                    HistorySyncManager.Phase.NEEDS_GOOGLE_RECONNECT ->
                        stringResource(MR.strings.history_sync_google_reconnect)
                    HistorySyncManager.Phase.DISABLED -> stringResource(MR.strings.history_sync_disabled)
                },
            ),
            if (status.phase == HistorySyncManager.Phase.SUCCESS) {
                Preference.PreferenceItem.InfoPreference(
                    stringResource(
                        MR.strings.history_sync_library_status,
                        status.libraryApplied,
                        status.libraryPending,
                    ) +
                        (
                            if (status.libraryClockExhausted) {
                                "\n" + stringResource(
                                    MR.strings.history_sync_library_clock,
                                )
                            } else {
                                ""
                            }
                            ) +
                        status.libraryIssues.take(20).joinToString(separator = "\n", prefix = "\n"),
                )
            } else {
                null
            },
        )
    }

    @Composable
    private fun CredentialsForm(manager: HistorySyncManager) {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val google = remember(context) { GoogleDriveAuthorization(context) }
        val googleAvailable = remember(google) { google.isAvailable() }
        var root by remember { mutableStateOf("https://app.koofr.net/dav/Koofr/") }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var existing by remember { mutableStateOf<HistorySyncCredentials?>(null) }
        var saving by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        var saved by remember { mutableStateOf(false) }
        var googleFailed by remember { mutableStateOf(false) }
        val googleResult = rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK || result.data == null) {
                saving = false
            } else {
                scope.launch {
                    try {
                        val value = google.verify(google.finish(result.data!!))
                        manager.saveCredentials(value)
                        manager.setEnabled(true)
                        existing = value
                        saved = true
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        googleFailed = true
                    } finally {
                        saving = false
                    }
                }
            }
        }
        LaunchedEffect(manager) {
            try {
                existing = manager.readCredentials()
                existing?.takeIf { it.provider == HistorySyncProvider.WEBDAV }?.let {
                    root = it.root
                    username = it.username
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failed = true
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = PrefsHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(MR.strings.history_sync_google_title))
            Text(stringResource(MR.strings.history_sync_google_scope))
            TextButton(onClick = { context.openInBrowser(GOOGLE_PRIVACY_URL) }) {
                Text(stringResource(MR.strings.history_sync_google_privacy))
            }
            existing?.takeIf { it.provider == HistorySyncProvider.GOOGLE_DRIVE }?.let {
                Text(stringResource(MR.strings.history_sync_google_connected, it.googleEmail))
                Button(
                    enabled = !saving,
                    onClick = {
                        scope.launch {
                            saving = true
                            try {
                                manager.disconnect()
                                existing = null
                                saved = false
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                googleFailed = true
                            } finally {
                                saving = false
                            }
                        }
                    },
                ) { Text(stringResource(MR.strings.history_sync_google_disconnect)) }
            }
            Button(
                enabled = !saving && googleAvailable && GoogleDriveAuthorization.isConfigured,
                onClick = {
                    scope.launch {
                        saving = true
                        saved = false
                        googleFailed = false
                        try {
                            val result = google.begin()
                            if (result.hasResolution()) {
                                googleResult.launch(
                                    IntentSenderRequest.Builder(checkNotNull(result.pendingIntent)).build(),
                                )
                            } else {
                                val value = google.verify(result)
                                manager.saveCredentials(value)
                                manager.setEnabled(true)
                                existing = value
                                saved = true
                                saving = false
                            }
                        } catch (error: CancellationException) {
                            saving = false
                            throw error
                        } catch (_: Exception) {
                            saving = false
                            googleFailed = true
                        }
                    }
                },
            ) { Text(stringResource(MR.strings.history_sync_google_sign_in)) }
            if (!GoogleDriveAuthorization.isConfigured) {
                Text(stringResource(MR.strings.history_sync_google_unconfigured))
            } else if (!googleAvailable) {
                Text(stringResource(MR.strings.history_sync_google_unavailable))
            }
            if (googleFailed) Text(stringResource(MR.strings.history_sync_google_failed))
            Text(stringResource(MR.strings.history_sync_webdav_title))
            OutlinedTextField(
                value = root,
                onValueChange = {
                    root = it
                    saved = false
                },
                label = { Text(stringResource(MR.strings.history_sync_server)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it
                    saved = false
                },
                label = { Text(stringResource(MR.strings.history_sync_login)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    saved = false
                },
                label = { Text(stringResource(MR.strings.history_sync_password)) },
                supportingText = { Text(stringResource(MR.strings.history_sync_password_hint)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = !saving,
                onClick = {
                    scope.launch {
                        saving = true
                        failed = false
                        saved = false
                        try {
                            val old = existing?.takeIf {
                                it.provider == HistorySyncProvider.WEBDAV &&
                                    it.root.trimEnd('/') == root.trim().trimEnd('/') &&
                                    it.username == username.trim()
                            }
                            val value =
                                HistorySyncCredentials(
                                    root.trim(),
                                    username.trim(),
                                    password.ifEmpty {
                                        old?.password.orEmpty()
                                    },
                                )
                            manager.saveCredentials(value)
                            existing = value
                            password = ""
                            saved = true
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            failed = true
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text(stringResource(MR.strings.action_save)) }
            if (failed) Text(stringResource(MR.strings.history_sync_save_failed))
            if (saved) Text(stringResource(MR.strings.history_sync_saved))
        }
    }
}
