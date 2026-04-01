package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.TextItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
internal fun ColumnScope.AiPage(screenModel: ReaderSettingsScreenModel) {
    val upscaleEnabled by screenModel.preferences.upscalePagesX2.collectAsState()
    val rawAiBackendMode by screenModel.preferences.aiBackendMode.collectAsState()
    val remoteAiBaseUrl by screenModel.preferences.remoteAiBaseUrl.collectAsState()
    val remoteAiDiscoveredBaseUrl by screenModel.preferences.remoteAiDiscoveredBaseUrl.collectAsState()
    val remoteAiToken by screenModel.preferences.remoteAiToken.collectAsState()
    val remoteAiModel by screenModel.preferences.remoteAiModel.collectAsState()
    val remoteAiBatchMode by screenModel.preferences.remoteAiBatchMode.collectAsState()
    val aiBackendMode = ReaderPreferences.normalizeAiBackendMode(rawAiBackendMode)
    val remoteAiStatus = remoteAiServerStatusText(
        manualUrl = remoteAiBaseUrl,
        discoveredUrl = remoteAiDiscoveredBaseUrl,
    )

    LaunchedEffect(rawAiBackendMode) {
        screenModel.preferences.migrateLegacyAiBackendMode()
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_reader_upscale_x2),
        checked = upscaleEnabled,
        onClick = { screenModel.setUpscaleEnabled(!upscaleEnabled) },
    )

    AiBackendSelector(
        selectedMode = aiBackendMode,
        onModeSelected = screenModel::setAiBackendMode,
    )

    if (aiBackendMode == ReaderPreferences.AiBackendMode.REMOTE) {
        SettingsChipRow(MR.strings.pref_reader_ai_remote_model) {
            ReaderPreferences.RemoteAiModel.entries.forEach { model ->
                FilterChip(
                    selected = remoteAiModel == model,
                    onClick = { screenModel.setRemoteAiModel(model) },
                    label = { Text(stringResource(model.titleRes)) },
                )
            }
        }
        SettingsChipRow(MR.strings.pref_reader_ai_remote_batch_mode) {
            ReaderPreferences.RemoteAiBatchMode.entries.forEach { batchMode ->
                FilterChip(
                    selected = remoteAiBatchMode == batchMode,
                    onClick = { screenModel.preferences.remoteAiBatchMode.set(batchMode) },
                    label = { Text(stringResource(batchMode.titleRes)) },
                )
            }
        }
        TextItem(
            label = stringResource(MR.strings.pref_reader_ai_remote_url),
            value = remoteAiBaseUrl,
            onChange = screenModel.preferences.remoteAiBaseUrl::set,
            supportingText = remoteAiStatus,
        )
        TextItem(
            label = stringResource(MR.strings.pref_reader_ai_remote_token),
            value = remoteAiToken,
            onChange = screenModel.preferences.remoteAiToken::set,
        )
    }
}
