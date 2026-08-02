package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.TextItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

@Composable
internal fun ColumnScope.AiPage(viewModel: ReaderSettingsViewModel) {
    val upscaleEnabled by viewModel.preferences.upscalePagesX2.collectAsState()
    val rawAiBackendMode by viewModel.preferences.aiBackendMode.collectAsState()
    val remoteAiBaseUrl by viewModel.preferences.remoteAiBaseUrl.collectAsState()
    val remoteAiDiscoveredBaseUrl by viewModel.preferences.remoteAiDiscoveredBaseUrl.collectAsState()
    val remoteAiToken by viewModel.preferences.remoteAiToken.collectAsState()
    val remoteAiModel by viewModel.preferences.remoteAiModel.collectAsState()
    val remoteAiBatchMode by viewModel.preferences.remoteAiBatchMode.collectAsState()
    val remoteAiDirectDownload by viewModel.preferences.remoteAiDirectDownload.collectAsState()
    val aiBackendMode = ReaderPreferences.normalizeAiBackendMode(rawAiBackendMode)
    val remoteAiStatus = remoteAiServerStatusText(
        manualUrl = remoteAiBaseUrl,
        discoveredUrl = remoteAiDiscoveredBaseUrl,
    )

    LaunchedEffect(rawAiBackendMode) {
        viewModel.preferences.migrateLegacyAiBackendMode()
    }

    CheckboxItem(
        label = stringResource(MR.strings.pref_reader_upscale_x2),
        checked = upscaleEnabled,
        onClick = { viewModel.setUpscaleEnabled(!upscaleEnabled) },
    )

    AiBackendSelector(
        selectedMode = aiBackendMode,
        onModeSelected = viewModel::setAiBackendMode,
    )

    if (aiBackendMode == ReaderPreferences.AiBackendMode.REMOTE) {
        SettingsChipRow(MR.strings.pref_reader_ai_remote_model) {
            ReaderPreferences.RemoteAiModel.entries.forEach { model ->
                FilterChip(
                    selected = remoteAiModel == model,
                    onClick = { viewModel.setRemoteAiModel(model) },
                    label = { Text(stringResource(model.titleRes)) },
                )
            }
        }
        SettingsChipRow(MR.strings.pref_reader_ai_remote_batch_mode) {
            ReaderPreferences.RemoteAiBatchMode.entries.forEach { batchMode ->
                FilterChip(
                    selected = remoteAiBatchMode == batchMode,
                    onClick = { viewModel.setRemoteAiBatchMode(batchMode) },
                    label = { Text(stringResource(batchMode.titleRes)) },
                )
            }
        }
        if (remoteAiBatchMode.shouldQueueWholeChapter) {
            CheckboxItem(
                label = stringResource(MR.strings.pref_reader_ai_remote_direct_download),
                checked = remoteAiDirectDownload,
                onClick = { viewModel.preferences.remoteAiDirectDownload.set(!remoteAiDirectDownload) },
            )
        }
        TextItem(
            label = stringResource(MR.strings.pref_reader_ai_remote_url),
            value = remoteAiBaseUrl,
            onChange = viewModel.preferences.remoteAiBaseUrl::set,
            supportingText = remoteAiStatus,
        )
        TextItem(
            label = stringResource(MR.strings.pref_reader_ai_remote_token),
            value = remoteAiToken,
            onChange = viewModel.preferences.remoteAiToken::set,
        )
    }
}
