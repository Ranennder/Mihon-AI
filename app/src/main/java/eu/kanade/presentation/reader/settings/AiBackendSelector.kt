package eu.kanade.presentation.reader.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun AiBackendSelector(
    selectedMode: ReaderPreferences.AiBackendMode,
    enabled: Boolean = true,
    onModeSelected: (ReaderPreferences.AiBackendMode) -> Unit,
) {
    var showGpuWarning by remember { mutableStateOf(false) }

    SettingsChipRow(MR.strings.pref_reader_ai_backend) {
        ReaderPreferences.SelectableAiBackendModes.forEach { mode ->
            FilterChip(
                selected = selectedMode == mode,
                enabled = enabled,
                onClick = {
                    if (selectedMode == mode) {
                        return@FilterChip
                    }

                    if (mode == ReaderPreferences.AiBackendMode.GPU) {
                        showGpuWarning = true
                    } else {
                        onModeSelected(mode)
                    }
                },
                label = { Text(stringResource(mode.titleRes)) },
            )
        }
    }

    if (showGpuWarning) {
        AlertDialog(
            onDismissRequest = { showGpuWarning = false },
            title = { Text(text = stringResource(MR.strings.label_warning)) },
            text = { Text(text = stringResource(MR.strings.reader_ai_gpu_beta_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showGpuWarning = false
                        onModeSelected(ReaderPreferences.AiBackendMode.GPU)
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showGpuWarning = false }) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}
