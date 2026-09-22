package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun AiChapterModeSelector(
    selectedMode: ReaderPreferences.RemoteAiChapterMode,
    enabled: Boolean = true,
    onModeSelected: (ReaderPreferences.RemoteAiChapterMode) -> Unit,
) {
    Column {
        SettingsChipRow(MR.strings.pref_reader_ai_remote_chapter_mode) {
            ReaderPreferences.RemoteAiChapterMode.entries.forEach { mode ->
                FilterChip(
                    selected = selectedMode == mode,
                    enabled = enabled,
                    onClick = { onModeSelected(mode) },
                    label = { Text(stringResource(mode.titleRes)) },
                )
            }
        }
        Text(
            text = stringResource(selectedMode.descriptionRes),
            modifier = Modifier.padding(
                start = SettingsItemsPaddings.Horizontal,
                end = SettingsItemsPaddings.Horizontal,
                bottom = SettingsItemsPaddings.Vertical,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
