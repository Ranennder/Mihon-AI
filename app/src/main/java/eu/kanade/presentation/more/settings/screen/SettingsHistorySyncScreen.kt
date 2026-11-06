package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.PrefsHorizontalPadding
import eu.kanade.tachiyomi.data.historysync.HistorySyncCredentials
import eu.kanade.tachiyomi.data.historysync.HistorySyncManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DateFormat
import java.util.Date

object SettingsHistorySyncScreen : SearchableSettings {
    @Composable
    @ReadOnlyComposable
    override fun getTitleRes() = MR.strings.history_sync_title

    @Composable
    override fun getPreferences(): List<Preference> {
        val manager = remember { Injekt.get<HistorySyncManager>() }
        val status by manager.status.collectAsState()
        return listOf(
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
                enabled = status.phase != HistorySyncManager.Phase.SYNCING,
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
                    HistorySyncManager.Phase.DISABLED -> stringResource(MR.strings.history_sync_disabled)
                },
            ),
        )
    }

    @Composable
    private fun CredentialsForm(manager: HistorySyncManager) {
        val scope = rememberCoroutineScope()
        var root by remember { mutableStateOf("https://webdav.yandex.ru/") }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var existing by remember { mutableStateOf<HistorySyncCredentials?>(null) }
        var saving by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        var saved by remember { mutableStateOf(false) }
        LaunchedEffect(manager) {
            try {
                existing = manager.readCredentials()
                existing?.let {
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
